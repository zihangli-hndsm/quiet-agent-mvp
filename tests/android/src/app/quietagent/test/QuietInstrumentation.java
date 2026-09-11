package app.quietagent.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import app.quietagent.DemoSource;
import app.quietagent.Store;
import app.quietagent.TaskService;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Dependency-free instrumentation runner. Invoke with:
 * am instrument -w -e scenario smoke app.quietagent.test/app.quietagent.test.QuietInstrumentation
 */
public final class QuietInstrumentation extends Instrumentation {
    private static final String TAG = "QuietTest";
    private static final long LIMIT_MS = 60_000L;
    private static final int QA_FILES = 24;
    private static final int QA_BYTES = 2 * 1024 * 1024;
    private static final String REQUEST = "去重，按类型归档";
    private Bundle arguments;
    private Activity qaActivity;
    private final List<File> createdQaFiles = new ArrayList<File>();

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        super.onStart();
        String scenario = arguments == null ? "smoke" : arguments.getString("scenario", "smoke");
        final String selected = scenario == null ? "smoke" : scenario.toLowerCase(Locale.ROOT);
        JSONObject result = new JSONObject();
        int code = 0;
        try {
            if (!"smoke".equals(selected) && !"cancel".equals(selected) && !"external".equals(selected) && !"export".equals(selected) && !"ui".equals(selected)) {
                throw new IllegalArgumentException("scenario must be smoke or cancel");
            }
            if ("smoke".equals(selected)) runSmoke(result);
            else if("external".equals(selected)) runExternal(result);
            else if("export".equals(selected)) runExport(result);
            else if("ui".equals(selected)) runUi(result);
            else runCancel(result);
            result.put("ok", true);
        } catch (Throwable error) {
            code = 1;
            try {
                result.put("ok", false);
                result.put("error", error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
            } catch (Exception ignored) { }
            Log.e(TAG, "scenario=" + selected + " failed", error);
        } finally {
            closeQaActivity();
            if(arguments == null || !"true".equals(arguments.getString("keepFixtures"))) cleanupQaFiles();
        }
        Bundle output = new Bundle();
        output.putString("result", result.toString());
        output.putString("scenario", selected);
        Log.i(TAG, result.toString());
        finish(code, output);
    }

    private void runSmoke(JSONObject out) throws Exception {
        Context target = getTargetContext();
        Activity activity = openQaActivity();
        recordKeyguard(out);
        final EditText[] probeHolder = new EditText[1];
        runOnMainSync(() -> probeHolder[0] = findEditText(activity, "qa-input"));
        EditText probe = probeHolder[0];
        if (probe == null) throw new AssertionError("qa-input was not found");
        focusProbe(activity, probe);

        ProbeEvidence evidence = new ProbeEvidence();
        evidence.capture(probe);
        Uri source = prepareQaSource(target, out);
        Store store = new Store(target);
        String before = optStatus(store.readStatus(), "id");
        startRun(target, source);
        String id = waitForRunning(store, before, LIMIT_MS);
        out.put("jobId", id);
        out.put("source", "DemoSource（仅本测试写入 qa_*.bin）");
        long deadline = SystemClock.elapsedRealtime() + LIMIT_MS;
        int injected = 0;
        int lastRunningLength = readLength(probe);
        while (SystemClock.elapsedRealtime() < deadline) {
            String state = optStatus(store.readStatus(), "state");
            if ("RUNNING".equals(state)) {
                evidence.capture(probe);
                int beforeLength = readLength(probe);
                if(beforeLength > lastRunningLength) { evidence.inputChangeDuringRun=true; evidence.inputChanges++; }
                lastRunningLength=beforeLength;
                String beforeInputState = state;
                if (injected < 18) {
                    sendStringSync(String.valueOf((char) ('a' + (injected % 26))) + " ");
                    injected++;
                    SystemClock.sleep(50);
                }
                int length = readLength(probe);
                String afterInputState = optStatus(store.readStatus(), "state");
                if ("RUNNING".equals(beforeInputState) && "RUNNING".equals(afterInputState) && length > beforeLength) {
                    evidence.inputChangeDuringRun = true;
                }
            } else if ("SUCCEEDED".equals(state)) {
                break;
            } else if ("FAILED".equals(state) || "CANCELLED".equals(state) || "INTERRUPTED".equals(state)) {
                throw new AssertionError("smoke ended in " + state + ": " + optStatus(store.readStatus(), "message"));
            }
            SystemClock.sleep(50L);
        }
        JSONObject finalStatus = new JSONObject(store.readStatus());
        if (!"SUCCEEDED".equals(finalStatus.optString("state"))) {
            throw new AssertionError("smoke did not finish within 60s: " + finalStatus.optString("state"));
        }
        verifyOutput(store, id, out);
        evidence.capture(probe);
        out.put("probeLength", readLength(probe));
        out.put("inputChanges", evidence.inputChanges);
        out.put("inputDuringRunning", evidence.inputChangeDuringRun);
        out.put("focusSamples", evidence.samples);
        out.put("focusLostSamples", evidence.focusLostSamples);
        out.put("inactiveSamples", evidence.inactiveSamples);
        out.put("imeHiddenSamples", evidence.imeHiddenSamples);
        out.put("probeWindowFocusSeen", evidence.focusSeen);
        out.put("inputMethodActiveSeen", evidence.inputMethodActiveSeen);
        out.put("imeVisibleSeen", evidence.imeVisibleSeen);
        out.put("rootInsetsSeen", evidence.rootInsetsSeen);
        out.put("automationInputNote", "sendStringSync 注入只证明自动化输入路径；不能冒充真实用户操作");
        out.put("statusState", finalStatus.optString("state"));
        out.put("selected", finalStatus.optInt("selected", 0));
        out.put("unique", finalStatus.optInt("unique", 0));
        out.put("duplicates", finalStatus.optInt("duplicates", 0));
        if (!evidence.inputChangeDuringRun || evidence.inputChanges <= 0) throw new AssertionError("no keyboard change observed between RUNNING samples");
        if (evidence.focusLostSamples != 0 || evidence.inactiveSamples != 0 || evidence.imeHiddenSamples != 0) throw new AssertionError("focus or IME was not continuously available");
    }

    private void runCancel(JSONObject out) throws Exception {
        Context target = getTargetContext();
        openQaActivity();
        recordKeyguard(out);
        Uri source = prepareQaSource(target, out);
        Store store = new Store(target);
        String before = optStatus(store.readStatus(), "id");
        startRun(target, source);
        String id = waitForRunning(store, before, LIMIT_MS);
        out.put("jobId", id);
        target.startService(new Intent(target, TaskService.class).setAction(TaskService.ACTION_CANCEL));
        JSONObject terminal = waitForTerminal(store, id, LIMIT_MS);
        String state = terminal.optString("state");
        if ("SUCCEEDED".equals(state)) throw new AssertionError("cancel raced with completion; cancelpass is invalid");
        if (!"CANCELLED".equals(state)) throw new AssertionError("expected CANCELLED, got " + state);
        File marker = new File(store.getJobFile(id, "archive.zip").getParentFile(), ".complete");
        if (marker.exists()) throw new AssertionError("cancelled task published .complete");
        out.put("statusState", state);
        out.put("completeMarker", false);
        out.put("cancelNote", "取消任务不得发布可导出的完成标记");
    }

    private volatile int externalLength,externalUid;
    private volatile Intent exportResult;
    private View findControl(View view,String name){
        if(name.equals(view.getContentDescription()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){View match=findControl(group.getChildAt(n),name);if(match!=null)return match;}}
        return null;
    }
    private void click(Activity a,String name){runOnMainSync(()->{View v=findControl(a.getWindow().getDecorView(),name);if(v==null||!v.performClick())throw new AssertionError("button missing: "+name);});}
    private void runUi(JSONObject out)throws Exception{
        Context target=getTargetContext();Store store=new Store(target);
        if(!store.getSourceUri().equals("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FQuietAgentMVP-SAF-20260911"))throw new AssertionError("authorize the synthetic SAF folder first");
        Activity main=openQaActivity();String before=optStatus(store.readStatus(),"id");
        click(main,"preview-plan");click(main,"start-task");
        long until=SystemClock.elapsedRealtime()+LIMIT_MS;JSONObject status;
        do{SystemClock.sleep(50);status=new JSONObject(store.readStatus());}while((before.equals(status.optString("id"))||!isTerminal(status.optString("state")))&&SystemClock.elapsedRealtime()<until);
        if(!"SUCCEEDED".equals(status.optString("state"))||before.equals(status.optString("id")))throw new AssertionError("UI task failed");
        out.put("jobId",status.getString("id")).put("selected",status.getInt("selected")).put("unique",status.getInt("unique")).put("duplicates",status.getInt("duplicates"));
        ActivityMonitor monitor=addMonitor("app.quietagent.ReportActivity",null,false);
        try{SystemClock.sleep(800);click(main,"view-report");Activity report=waitForMonitorWithTimeout(monitor,5000);if(report==null)throw new AssertionError("report not opened");
            try{SystemClock.sleep(800);android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot!=null)try(FileOutputStream file=new FileOutputStream(new File(target.getExternalFilesDir(null),"qa-report.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file);}out.put("reportOpened",true);}
            finally{runOnMainSync(()->report.finish());}
        }finally{removeMonitor(monitor);}
    }
    private void runExport(JSONObject out)throws Exception {
        Context target=getTargetContext();openQaActivity();
        Store store=new Store(target);JSONObject status=new JSONObject(store.readStatus());
        if(!"SUCCEEDED".equals(status.optString("state")))throw new AssertionError("run external or smoke first");
        String id=status.getString("id");
        android.net.Uri uri=app.quietagent.ShareProvider.uriFor(target,id,"archive.zip");
        android.content.BroadcastReceiver receiver=new android.content.BroadcastReceiver(){public void onReceive(Context c,Intent i){exportResult=i;}};
        target.registerReceiver(receiver,new android.content.IntentFilter("app.quietagent.TEST_EXPORT"));
        try {
            Intent share=new Intent(Intent.ACTION_SEND).setClassName("app.quietagent.test","app.quietagent.test.ProbeActivity")
              .setType("application/zip").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(android.content.ClipData.newRawUri("archive.zip",uri));target.startActivity(share);
            long until=SystemClock.elapsedRealtime()+15000;while(exportResult==null&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);
            if(exportResult==null)throw new AssertionError("export receiver timed out");
            out.put("jobId",id).put("hash",exportResult.getStringExtra("hash")).put("bytes",exportResult.getLongExtra("bytes",0))
               .put("receiverUid",exportResult.getIntExtra("uid",0)).put("writeDenied",exportResult.getBooleanExtra("writeDenied",false));
            if(!status.getString("archiveSha256").equals(exportResult.getStringExtra("hash"))||!exportResult.getBooleanExtra("writeDenied",false)||exportResult.getIntExtra("uid",0)==android.os.Process.myUid())throw new AssertionError("export contract failed: "+exportResult.getStringExtra("error"));
        }finally{target.unregisterReceiver(receiver);}
    }
    private volatile boolean externalFocus,externalIme;
    private void runExternal(JSONObject out) throws Exception {
        Context target=getTargetContext();openQaActivity();recordKeyguard(out);
        Uri source=prepareQaSource(target,out);Store store=new Store(target);
        android.content.BroadcastReceiver receiver=new android.content.BroadcastReceiver(){public void onReceive(Context c,Intent i){
            externalLength=i.getIntExtra("length",0);externalUid=i.getIntExtra("uid",0);
            externalFocus=i.getBooleanExtra("focus",false);externalIme=i.getBooleanExtra("ime",false);
        }};
        target.registerReceiver(receiver,new android.content.IntentFilter("app.quietagent.TEST_PROBE"));
        try {
            String before=optStatus(store.readStatus(),"id");startRun(target,source);
            String id=waitForRunning(store,before,LIMIT_MS);out.put("jobId",id);
            target.startActivity(new Intent().setClassName("app.quietagent.test","app.quietagent.test.ProbeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long until=SystemClock.elapsedRealtime()+5000;
            while(!(externalFocus&&externalIme)&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(25);
            if(!(externalFocus&&externalIme))throw new AssertionError("external foreground not ready");
            int samples=0,lost=0,changes=0,previous=externalLength;
            until=SystemClock.elapsedRealtime()+LIMIT_MS;
            while("RUNNING".equals(optStatus(store.readStatus(),"state"))&&SystemClock.elapsedRealtime()<until){
                samples++;if(!externalFocus||!externalIme)lost++;
                if(externalLength>previous)changes++;previous=externalLength;
                android.view.KeyEvent[] events=android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents("x ".toCharArray());
                for(android.view.KeyEvent event:events)getUiAutomation().injectInputEvent(event,true);
                SystemClock.sleep(75);
            }
            JSONObject status=waitForTerminal(store,id,LIMIT_MS);
            out.put("state",status.optString("state")).put("foregroundUid",externalUid).put("agentUid",android.os.Process.myUid())
              .put("samples",samples).put("focusOrImeLost",lost).put("inputChangeSamples",changes).put("inputLength",externalLength);
            if(!"SUCCEEDED".equals(status.optString("state"))||changes<1||lost!=0||externalUid==android.os.Process.myUid())throw new AssertionError("independent foreground contract failed");
            verifyOutput(store,id,out);
            android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
            if(screenshot!=null)try(FileOutputStream file=new FileOutputStream(new File(target.getExternalFilesDir(null),"qa-external.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file);}
            out.put("note","独立应用进程接收自动注入输入；不等价于真人长期使用");
        }finally{target.sendBroadcast(new Intent("app.quietagent.test.FINISH").setPackage("app.quietagent.test"));target.unregisterReceiver(receiver);}
    }

    private Activity openQaActivity() {
        Intent launch = getTargetContext().getPackageManager().getLaunchIntentForPackage("app.quietagent");
        if (launch == null) throw new IllegalStateException("main launcher activity not found");
        launch.putExtra("qa_test", true);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Activity activity = startActivitySync(launch);
        if (activity == null) throw new IllegalStateException("QA Activity did not start");
        qaActivity = activity;
        runOnMainSync(() -> {
            if (Build.VERSION.SDK_INT >= 27) {
                activity.setShowWhenLocked(true);
                activity.setTurnScreenOn(true);
            }
        });
        waitForWindow(activity, 5_000L);
        return activity;
    }

    private void closeQaActivity() {
        final Activity activity = qaActivity;
        qaActivity = null;
        if (activity == null) return;
        try {
            runOnMainSync(() -> {
                if (Build.VERSION.SDK_INT >= 27) {
                    activity.setShowWhenLocked(false);
                    activity.setTurnScreenOn(false);
                }
                activity.finish();
            });
        } catch (RuntimeException ignored) { }
    }

    private void recordKeyguard(JSONObject out) throws Exception {
        KeyguardManager keyguard = (KeyguardManager) getTargetContext().getSystemService(Context.KEYGUARD_SERVICE);
        out.put("deviceSecure", keyguard != null && keyguard.isDeviceSecure());
        out.put("keyguardLocked", keyguard != null && keyguard.isKeyguardLocked());
        out.put("keyguardNote", "仅记录设备安全锁状态；测试不请求或绕过解锁凭据");
    }

    private void focusProbe(Activity activity, EditText probe) {
        runOnMainSync(() -> {
            probe.requestFocusFromTouch();
            probe.requestRectangleOnScreen(new android.graphics.Rect(0,0,probe.getWidth(),probe.getHeight()),true);
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(probe, InputMethodManager.SHOW_IMPLICIT);
        });
        long deadline = SystemClock.elapsedRealtime() + 5_000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            final boolean[] focused = new boolean[] {false};
            runOnMainSync(() -> focused[0] = probe.hasFocus() && probe.hasWindowFocus() && probe.getRootWindowInsets()!=null && probe.getRootWindowInsets().isVisible(WindowInsets.Type.ime()));
            if (focused[0]) { SystemClock.sleep(300); return; }
            SystemClock.sleep(50L);
        }
    }

    private Uri prepareQaSource(Context target, JSONObject out) throws Exception {
        Uri source = DemoSource.prepare(target);
        File root = DemoSource.directory(target);
        removeStaleQaFiles(root);
        String run = Long.toString(System.currentTimeMillis());
        SecureRandom random = new SecureRandom();
        Map<String, String> expected = new LinkedHashMap<String, String>();
        for (int i = 0; i < QA_FILES; i++) {
            String name = String.format(Locale.ROOT, "qa_%s_%02d.bin", run, i);
            File file = new File(root, name);
            String hash = writeRandomFile(file, random);
            createdQaFiles.add(file);
            expected.put(name, hash);
        }
        out.put("qaFiles", QA_FILES);
        out.put("qaBytesEach", QA_BYTES);
        out.put("qaPrefix", "qa_" + run + "_");
        out.put("qaExpectedHashes", new JSONObject(expected));
        return source;
    }

    private String writeRandomFile(File file, SecureRandom random) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int remaining = QA_BYTES;
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file), buffer.length)) {
            while (remaining > 0) {
                int amount = Math.min(remaining, buffer.length);
                random.nextBytes(buffer);
                out.write(buffer, 0, amount);
                digest.update(buffer, 0, amount);
                remaining -= amount;
            }
        }
        return hex(digest.digest());
    }

    private void startRun(Context target, Uri source) {
        Intent run = new Intent(target, TaskService.class).setAction(TaskService.ACTION_RUN)
                .putExtra("request", REQUEST).putExtra("source", source.toString());
        if (Build.VERSION.SDK_INT >= 26) target.startForegroundService(run); else target.startService(run);
    }

    private void removeStaleQaFiles(File root) {
        File[] files = root.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith("qa_") && name.endsWith(".bin")) file.delete();
        }
    }

    private void cleanupQaFiles() {
        for (File file : createdQaFiles) if (file != null) file.delete();
        createdQaFiles.clear();
    }

    private String waitForRunning(Store store, String previousId, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject status = new JSONObject(store.readStatus());
            if ("RUNNING".equals(status.optString("state")) && !previousId.equals(status.optString("id"))) {
                return status.optString("id");
            }
            SystemClock.sleep(50L);
        }
        throw new AssertionError("task did not enter RUNNING within 60s");
    }

    private JSONObject waitForTerminal(Store store, String id, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject status = new JSONObject(store.readStatus());
            if (id.equals(status.optString("id")) && isTerminal(status.optString("state"))) return status;
            SystemClock.sleep(50L);
        }
        throw new AssertionError("task did not reach a terminal state within 60s");
    }

    private boolean isTerminal(String state) {
        return "SUCCEEDED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state) || "INTERRUPTED".equals(state);
    }

    private void verifyOutput(Store store, String id, JSONObject out) throws Exception {
        File archive = store.getJobFile(id, "archive.zip");
        File manifest = store.getJobFile(id, "manifest.json");
        File summary = store.getJobFile(id, "summary.html");
        File marker = new File(archive.getParentFile(), ".complete");
        if (!archive.isFile() || !manifest.isFile() || !summary.isFile() || !marker.isFile()) {
            throw new AssertionError("completed output contract is incomplete");
        }
        String expectedPrefix = out.optString("qaPrefix");
        int chunks = 0;
        JSONObject expected = out.optJSONObject("qaExpectedHashes");
        Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        if (expected != null) {
            java.util.Iterator<String> keys = expected.keys();
            while (keys.hasNext()) seen.put(keys.next(), false);
        }
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive), 64 * 1024))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".bin") && name.contains(expectedPrefix)) {
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    String want = expected.optString(base, "");
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long size = 0;
                    int read;
                    while ((read = zip.read(buffer)) != -1) { digest.update(buffer, 0, read); size += read; }
                    if (!want.equals(hex(digest.digest())) || size != QA_BYTES) throw new AssertionError("QA chunk mismatch: " + base);
                    seen.put(base, true);
                    chunks++;
                }
            }
        }
        if (chunks != QA_FILES) throw new AssertionError("expected " + QA_FILES + " QA chunks, found " + chunks);
        for (Boolean value : seen.values()) if (!value) throw new AssertionError("QA chunk missing from archive");
        out.put("archive", true);
        out.put("manifest", true);
        out.put("summary", true);
        out.put("completeMarker", true);
        out.put("verifiedChunks", chunks);
    }

    private int readLength(EditText probe) {
        final int[] length = new int[] {0};
        runOnMainSync(() -> length[0] = probe.getText() == null ? 0 : probe.getText().length());
        return length[0];
    }

    private final class ProbeEvidence {
        boolean focusSeen;
        boolean inputMethodActiveSeen;
        boolean imeVisibleSeen;
        boolean rootInsetsSeen;
        boolean inputChangeDuringRun;
        int inputChanges;
        int samples;
        int focusLostSamples;
        int inactiveSamples;
        int imeHiddenSamples;

        void capture(EditText probe) {
            runOnMainSync(() -> {
                samples++;
                boolean focused = probe.hasWindowFocus();
                if (focused) focusSeen = true; else focusLostSamples++;
                if (!probe.isShown() || !probe.isEnabled() || !probe.hasFocus()) inactiveSamples++;
                InputMethodManager imm = (InputMethodManager) probe.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && imm.isActive(probe)) inputMethodActiveSeen = true;
                WindowInsets insets = Build.VERSION.SDK_INT >= 23 ? probe.getRootWindowInsets() : null;
                if (insets != null) {
                    rootInsetsSeen = true;
                    if (Build.VERSION.SDK_INT >= 30) {
                        if (insets.isVisible(WindowInsets.Type.ime())) imeVisibleSeen = true;
                        else imeHiddenSamples++;
                    }
                }
            });
        }
    }

    private void waitForWindow(Activity activity, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline && !hasWindowFocus(activity)) SystemClock.sleep(50L);
        if (!hasWindowFocus(activity)) throw new AssertionError("QA Activity window did not become visible/focused");
    }

    private boolean hasWindowFocus(Activity activity) {
        final boolean[] focused = new boolean[] {false};
        runOnMainSync(() -> focused[0] = activity.hasWindowFocus());
        return focused[0];
    }

    private EditText findEditText(Activity activity, String description) {
        return findEditText(activity.getWindow().getDecorView(), description);
    }

    private EditText findEditText(View view, String description) {
        if (view instanceof EditText && description.equals(view.getContentDescription())) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEditText(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String optStatus(String raw, String key) {
        try { return new JSONObject(raw).optString(key, ""); }
        catch (Exception ignored) { return ""; }
    }

    private static String safe(String text) { return text == null || text.isEmpty() ? "unknown" : text; }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
