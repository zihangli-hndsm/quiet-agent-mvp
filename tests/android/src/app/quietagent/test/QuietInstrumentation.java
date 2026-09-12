package app.quietagent.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import app.quietagent.ReceiptJobStore;
import app.quietagent.Store;
import app.quietagent.TaskService;
import app.quietagent.security.AtomicFileStateStore;
import app.quietagent.security.Authorization;
import app.quietagent.security.AuthorizationManager;
import app.quietagent.security.TaskSpec;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.Set;

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
    private static final long RECEIPT_LIMIT_MS = 180_000L;
    private static final String[] RECEIPT_FIXTURES = new String[] {
            "receipt-01.png", "receipt-02.png", "receipt-03.png", "receipt-04.png",
            "receipt-05.png", "receipt-06.png", "receipt-07.png", "receipt-08.png",
            "duplicate-01.png", "duplicate-02.png", "ambiguous-amount.png", "not-a-receipt.png"
    };
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
            if (!"content".equals(selected) && !"smoke".equals(selected) && !"cancel".equals(selected) && !"external".equals(selected) && !"export".equals(selected) && !"ui".equals(selected) && !"receipt".equals(selected) && !"permission".equals(selected) && !"interrupt_start".equals(selected) && !"recover".equals(selected) && !"inspect_status".equals(selected)) {
                throw new IllegalArgumentException("unknown scenario");
            }
            if ("content".equals(selected)) ContentChecks.run(this, result);
            else if ("smoke".equals(selected)) runSmoke(result);
            else if("external".equals(selected)) runExternal(result);
            else if("export".equals(selected)) runExport(result);
            else if("ui".equals(selected)) runUi(result);
            else if("receipt".equals(selected)) runReceipt(result);
            else if("permission".equals(selected)) runPermissionDenied(result);
            else if("interrupt_start".equals(selected)) runInterruptStart(result);
            else if("recover".equals(selected)) runRecover(result);
            else if("inspect_status".equals(selected)) runInspectStatus(result);
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

    /** Returns aggregate task status only; never emits OCR text or source identifiers. */
    private void runInspectStatus(JSONObject out) throws Exception {
        JSONObject status = new JSONObject(new Store(getTargetContext()).readStatus());
        for (String key : new String[]{"id", "state", "taskType", "selected", "unique", "duplicates", "recognizedTotalCents"}) {
            if (status.has(key)) out.put(key, status.get(key));
        }
        String id = status.optString("id", "");
        if (id.startsWith("receipt-") && "SUCCEEDED".equals(status.optString("state"))) {
            File manifestFile = new Store(getTargetContext()).getJobFile(id, "manifest.json");
            JSONObject manifest = new JSONObject(readUtf8(manifestFile));
            org.json.JSONArray sourceRows = manifest.optJSONArray("rows");
            org.json.JSONArray aggregateRows = new org.json.JSONArray();
            if (sourceRows != null) for (int i = 0; i < sourceRows.length(); i++) {
                JSONObject source = sourceRows.getJSONObject(i);
                JSONObject aggregate = new JSONObject();
                aggregate.put("amountCents", source.opt("amountCents"));
                aggregate.put("reviewReason", source.opt("reviewReason"));
                aggregateRows.put(aggregate);
            }
            out.put("rows", aggregateRows);
        }
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
        recordKeyguard(out);
        List<String> sources = new ArrayList<String>();
        List<Uri> uris = new ArrayList<Uri>();
        try {
            for (String name : RECEIPT_FIXTURES) {
                Uri uri = Uri.parse("content://app.quietagent.test.fixtures/" + name);
                fixturePermission(target, uri, true);
                uris.add(uri); sources.add(uri.toString());
            }
            String scope = android.text.TextUtils.join("\n", sources);
            TaskSpec spec = TaskSpec.ocr(scope, "票据照片 · 本地摘要 · 待核对");
            AuthorizationManager auth = new AuthorizationManager(new AtomicFileStateStore(new File(target.getFilesDir(), "security/authorization.state")));
            Authorization authorization = auth.authorize(spec);
            ReceiptJobStore jobs = new ReceiptJobStore(target);
            String id = jobs.createPending(spec, authorization.nonce(), sources);
            Store store = new Store(target);
            Intent run = new Intent(target, TaskService.class).setAction(TaskService.ACTION_RUN).putExtra("jobId", id);
            if (Build.VERSION.SDK_INT >= 26) target.startForegroundService(run); else target.startService(run);
            waitForRunning(store, "", LIMIT_MS);
            target.startService(new Intent(target, TaskService.class).setAction(TaskService.ACTION_CANCEL));
            JSONObject terminal = waitForTerminal(store, id, LIMIT_MS);
            if (!"CANCELLED".equals(terminal.optString("state"))) throw new AssertionError("expected CANCELLED");
            if (new File(jobs.jobDir(id), ".complete").exists()) throw new AssertionError("cancelled task published .complete");
            out.put("jobId", id).put("statusState", "CANCELLED").put("completeMarker", false);
        } finally {
            for (Uri uri : uris) try { fixturePermission(target, uri, false); } catch (Exception ignored) { }
        }
    }

    private void runPermissionDenied(JSONObject out) throws Exception {
        Context target = getTargetContext();
        Uri uri = Uri.parse("content://app.quietagent.test.fixtures/receipt-01.png");
        fixturePermission(target, uri, true);
        List<String> sources = java.util.Collections.singletonList(uri.toString());
        TaskSpec spec = TaskSpec.ocr(uri.toString(), "票据照片 · 本地摘要 · 待核对");
        AuthorizationManager auth = new AuthorizationManager(new AtomicFileStateStore(new File(target.getFilesDir(), "security/authorization.state")));
        Authorization authorization = auth.authorize(spec);
        ReceiptJobStore jobs = new ReceiptJobStore(target);
        String id = jobs.createPending(spec, authorization.nonce(), sources);
        fixturePermission(target, uri, false);
        Store store = new Store(target);
        Intent run = new Intent(target, TaskService.class).setAction(TaskService.ACTION_RUN).putExtra("jobId", id);
        if (Build.VERSION.SDK_INT >= 26) target.startForegroundService(run); else target.startService(run);
        JSONObject terminal = waitForTerminal(store, id, LIMIT_MS);
        if (!"FAILED".equals(terminal.optString("state"))) throw new AssertionError("revoked permission must fail");
        if (new File(jobs.jobDir(id), ".complete").exists()) throw new AssertionError("permission failure published .complete");
        out.put("jobId", id).put("statusState", "FAILED").put("completeMarker", false)
                .put("permissionRevoked", true);
    }

    private void runInterruptStart(JSONObject out) throws Exception {
        Context target = getTargetContext();
        List<String> sources = new ArrayList<String>();
        for (String name : RECEIPT_FIXTURES) {
            Uri uri = Uri.parse("content://app.quietagent.test.fixtures/" + name);
            fixturePermission(target, uri, true);
            sources.add(uri.toString());
        }
        String scope = android.text.TextUtils.join("\n", sources);
        TaskSpec spec = TaskSpec.ocr(scope, "票据照片 · 本地摘要 · 待核对");
        AuthorizationManager auth = new AuthorizationManager(new AtomicFileStateStore(new File(target.getFilesDir(), "security/authorization.state")));
        Authorization authorization = auth.authorize(spec);
        ReceiptJobStore jobs = new ReceiptJobStore(target);
        String id = jobs.createPending(spec, authorization.nonce(), sources);
        Store store = new Store(target);
        Intent run = new Intent(target, TaskService.class).setAction(TaskService.ACTION_RUN).putExtra("jobId", id);
        if (Build.VERSION.SDK_INT >= 26) target.startForegroundService(run); else target.startService(run);
        waitForRunning(store, "", LIMIT_MS);
        out.put("jobId", id).put("statusState", "RUNNING").put("readyForForceStop", true);
    }

    private void runRecover(JSONObject out) throws Exception {
        Context target = getTargetContext();
        Store store = new Store(target);
        JSONObject status = new JSONObject(store.readStatus());
        String id = status.optString("id", "");
        if (!"INTERRUPTED".equals(status.optString("state")) || !id.startsWith("receipt-")) {
            throw new AssertionError("interrupted status not recovered");
        }
        ReceiptJobStore jobs = new ReceiptJobStore(target);
        AuthorizationManager auth = new AuthorizationManager(new AtomicFileStateStore(new File(target.getFilesDir(), "security/authorization.state")));
        jobs.recoverInterrupted(id, auth);
        File dir = jobs.jobDir(id);
        if (new File(dir, ".receipt-temp").exists() || new File(dir, ".complete").exists()
                || new File(dir, "archive.zip").exists() || !new File(dir, "credential").isFile()) {
            throw new AssertionError("interrupted private data cleanup failed");
        }
        for (String name : RECEIPT_FIXTURES) {
            fixturePermission(target, Uri.parse("content://app.quietagent.test.fixtures/" + name), false);
        }
        out.put("jobId", id).put("statusState", "INTERRUPTED").put("tempCleaned", true)
                .put("completeMarker", false).put("credentialRetained", true);
    }

    /** Runs the real OCR task against 12 read-only images bundled in this test APK. */
    private void runReceipt(JSONObject out) throws Exception {
        Context target = getTargetContext();
        recordKeyguard(out);
        long started = SystemClock.elapsedRealtime();
        List<String> sources = new ArrayList<String>();
        List<Uri> uris = new ArrayList<Uri>();
        externalLength = 0; externalUid = 0; externalFocus = false; externalIme = false;
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                externalLength = i.getIntExtra("length", 0);
                externalUid = i.getIntExtra("uid", 0);
                externalFocus = i.getBooleanExtra("focus", false);
                externalIme = i.getBooleanExtra("ime", false);
            }
        };
        target.registerReceiver(receiver, new android.content.IntentFilter("app.quietagent.TEST_PROBE"));
        try {
            for (String name : RECEIPT_FIXTURES) {
                Uri uri = Uri.parse("content://app.quietagent.test.fixtures/" + name);
                // The test provider owns the bytes; the target app receives only
                // one read grant per selected image, matching the picker contract.
                fixturePermission(target, uri, true);
                sources.add(uri.toString());
                uris.add(uri);
            }
            if (arguments != null && "true".equals(arguments.getString("dumpOcr"))) {
                dumpReceiptOcr(target, sources, out);
            }
            StringBuilder scope = new StringBuilder();
            for (String source : sources) { if (scope.length() > 0) scope.append('\n'); scope.append(source); }
            TaskSpec spec = TaskSpec.ocr(scope.toString(), "票据照片 · 本地摘要 · 待核对");
            AuthorizationManager authorizer = new AuthorizationManager(new AtomicFileStateStore(
                    new File(target.getFilesDir(), "security/authorization.state")));
            Authorization authorization = authorizer.authorize(spec);
            ReceiptJobStore jobs = new ReceiptJobStore(target);
            String jobId = jobs.createPending(spec, authorization.nonce(), sources);
            out.put("jobId", jobId).put("fixtureCount", sources.size());
            Store store = new Store(target);
            Intent run = new Intent(target, TaskService.class).setAction(TaskService.ACTION_RUN).putExtra("jobId", jobId);
            if (Build.VERSION.SDK_INT >= 26) target.startForegroundService(run); else target.startService(run);
            target.startActivity(new Intent().setClassName("app.quietagent.test", "app.quietagent.test.ProbeActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long foregroundDeadline = SystemClock.elapsedRealtime() + 5000L;
            while (!(externalFocus && externalIme) && SystemClock.elapsedRealtime() < foregroundDeadline) SystemClock.sleep(25L);
            if (!(externalFocus && externalIme)) throw new AssertionError("independent foreground not ready");
            int focusSamples = 0, focusLost = 0, inputChanges = 0, previousLength = externalLength;
            long runDeadline = SystemClock.elapsedRealtime() + RECEIPT_LIMIT_MS;
            while ("RUNNING".equals(optStatus(store.readStatus(), "state")) && SystemClock.elapsedRealtime() < runDeadline) {
                focusSamples++;
                if (!externalFocus || !externalIme) focusLost++;
                if (externalLength > previousLength) inputChanges++;
                previousLength = externalLength;
                android.view.KeyEvent[] events = android.view.KeyCharacterMap.load(
                        android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents("x ".toCharArray());
                for (android.view.KeyEvent event : events) getUiAutomation().injectInputEvent(event, true);
                SystemClock.sleep(75L);
            }
            JSONObject terminal = waitForTerminal(store, jobId, RECEIPT_LIMIT_MS);
            if (!"SUCCEEDED".equals(terminal.optString("state"))) {
                throw new AssertionError("receipt ended in " + terminal.optString("state") + ": " + terminal.optString("message"));
            }
            verifyReceiptOutput(jobs, jobId, terminal, out);
            out.put("foregroundUid", externalUid).put("agentUid", android.os.Process.myUid())
                    .put("focusSamples", focusSamples).put("focusOrImeLost", focusLost)
                    .put("inputChangeSamples", inputChanges).put("probeLength", externalLength);
            if (inputChanges < 1 || focusLost != 0 || externalUid == android.os.Process.myUid()) {
                throw new AssertionError("independent foreground contract failed");
            }
            out.put("durationMs", SystemClock.elapsedRealtime() - started);
            out.put("statusState", terminal.optString("state"));
        } finally {
            target.sendBroadcast(new Intent("app.quietagent.test.FINISH").setPackage("app.quietagent.test"));
            try { target.unregisterReceiver(receiver); } catch (Exception ignored) { }
            for (Uri uri : uris) {
                try { fixturePermission(target, uri, false); }
                catch (Exception ignored) { }
            }
        }
    }

    private void dumpReceiptOcr(Context target, List<String> sources, JSONObject result) throws Exception {
        app.quietagent.ReceiptOcr ocr = app.quietagent.ReceiptOcr.mlKitChinese();
        StringBuilder text = new StringBuilder();
        try {
            for (int i = 0; i < Math.min(2, sources.size()); i++) {
                Uri uri = Uri.parse(sources.get(i));
                InputStream input = target.getContentResolver().openInputStream(uri);
                Bitmap bitmap;
                try { bitmap = BitmapFactory.decodeStream(input); } finally { if (input != null) input.close(); }
                app.quietagent.ReceiptOcr.Result value = ocr.recognize(bitmap, RECEIPT_FIXTURES[i], null);
                text.append(RECEIPT_FIXTURES[i]).append('=');
                for (app.quietagent.receipt.ReceiptParser.OcrLine line : value.lines) {
                    text.append('[').append(line.text).append(']');
                }
                text.append(';');
                if (bitmap != null) bitmap.recycle();
            }
        } finally { ocr.close(); }
        result.put("ocrLines", text.toString());
    }

    private void verifyReceiptOutput(ReceiptJobStore jobs, String jobId, JSONObject status, JSONObject out) throws Exception {
        File dir = jobs.jobDir(jobId);
        File marker = new File(dir, ".complete");
        File archive = jobs.resultFile(jobId, "archive.zip");
        File manifestFile = jobs.resultFile(jobId, "manifest.json");
        File csvFile = jobs.resultFile(jobId, "receipts.csv");
        File summaryFile = jobs.resultFile(jobId, "summary.html");
        File auditFile = jobs.resultFile(jobId, "audit-snapshot");
        if (!marker.isFile()) throw new AssertionError("receipt .complete marker missing");
        JSONObject manifest = new JSONObject(readUtf8(manifestFile));
        if (!"quiet-agent-receipt-manifest-v1".equals(manifest.optString("schema"))) throw new AssertionError("receipt manifest schema mismatch");
        if (manifest.optInt("recognizedTotalCents", -1) != 38080) throw new AssertionError("receipt total is not 38080 cents: " + manifest.optInt("recognizedTotalCents", -1) + " rows=" + manifest.optJSONArray("rows"));
        org.json.JSONArray rows = manifest.optJSONArray("rows");
        if (rows == null || rows.length() != 12) throw new AssertionError("receipt manifest must contain 12 rows");
        Map<String, String> ids = new LinkedHashMap<String, String>();
        int included = 0, duplicates = 0, review = 0, clearAmounts = 0;
        long clearTotal = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String name = row.optString("sourceName", "");
            String sourceId = row.optString("sourceId", "");
            if (name.isEmpty() || sourceId.length() != 64) throw new AssertionError("manifest row identity is invalid");
            ids.put(name, sourceId);
            if (row.optBoolean("included", false)) included++;
            if (!row.isNull("duplicateOf")) duplicates++;
            if (!row.isNull("reviewReason") && !row.optString("reviewReason", "").isEmpty()) review++;
            if (row.optBoolean("included", false) && !row.isNull("amountCents")) {
                clearAmounts++; clearTotal += row.optLong("amountCents", -1L);
            }
        }
        if (included != 10 || duplicates != 2) throw new AssertionError("expected 10 included and 2 duplicate rows");
        if (review != 2) throw new AssertionError("expected exactly 2 review rows");
        if (clearAmounts != 8 || clearTotal != 38080L) throw new AssertionError("expected 8 recognized rows totaling 38080 cents, got rows=" + clearAmounts + " total=" + clearTotal);
        if (!ids.containsKey("receipt-01.png") || !ids.containsKey("receipt-03.png")) throw new AssertionError("duplicate targets missing");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String name = row.optString("sourceName", "");
            if ("duplicate-01.png".equals(name) && !ids.get("receipt-01.png").equals(row.optString("duplicateOf", ""))) throw new AssertionError("duplicate-01 mapping mismatch");
            if ("duplicate-02.png".equals(name) && !ids.get("receipt-03.png").equals(row.optString("duplicateOf", ""))) throw new AssertionError("duplicate-02 mapping mismatch");
        }
        int csvLines = countLines(readUtf8(csvFile));
        if (csvLines != 13) throw new AssertionError("receipts.csv must contain header plus 12 rows");
        if (!readUtf8(summaryFile).contains("票据整理结果")) throw new AssertionError("receipt summary missing");
        JSONObject audit = new JSONObject(readUtf8(auditFile));
        if (!"quiet-receipt-audit-snapshot-v1".equals(audit.optString("schema")) || audit.optJSONArray("files") == null || audit.optJSONArray("files").length() != 12) throw new AssertionError("audit snapshot must cover 12 rows");

        Set<String> zipNames = new java.util.LinkedHashSet<String>();
        Set<String> imageHashes = new java.util.HashSet<String>();
        int imageCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive), 64 * 1024))) {
            ZipEntry entry; byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (!zipNames.add(entry.getName())) throw new AssertionError("duplicate ZIP entry: " + entry.getName());
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); int n;
                while ((n = zip.read(buffer)) != -1) digest.update(buffer, 0, n);
                if (entry.getName().startsWith("receipts/") && entry.getName().endsWith(".png")) { imageCount++; imageHashes.add(hex(digest.digest())); }
            }
        }
        if (imageCount != 10 || imageHashes.size() != 10) throw new AssertionError("ZIP must contain 10 unique image payloads");
        for (String required : new String[]{"manifest.json", "receipts.csv", "summary.html", "audit-snapshot"}) if (!zipNames.contains(required)) throw new AssertionError("ZIP missing " + required);
        for (String name : zipNames) if (name.toLowerCase(Locale.ROOT).contains("expected.json")) throw new AssertionError("oracle leaked into ZIP");
        out.put("selected", status.optInt("selected", -1));
        out.put("unique", status.optInt("unique", -1));
        out.put("duplicates", status.optInt("duplicates", -1));
        out.put("recognizedTotalCents", manifest.optInt("recognizedTotalCents", -1));
        out.put("completeMarker", true);
        out.put("zipImagePayloads", imageCount);
        out.put("reviewRows", review);
        Uri shared = app.quietagent.ShareProvider.uriFor(getTargetContext(), jobId, "archive.zip");
        getTargetContext().grantUriPermission(getContext().getPackageName(), shared, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        long sharedBytes = 0;
        boolean writeDenied = false;
        try {
            try (java.io.InputStream input = getContext().getContentResolver().openInputStream(shared)) {
                byte[] buffer = new byte[64 * 1024]; int n;
                while ((n = input.read(buffer)) != -1) sharedBytes += n;
            }
            try (java.io.OutputStream ignored = getContext().getContentResolver().openOutputStream(shared, "w")) {
                // A writable descriptor would violate the cross-app export contract.
            } catch (Exception expected) { writeDenied = true; }
        } finally {
            getTargetContext().revokeUriPermission(getContext().getPackageName(), shared,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        if (sharedBytes != archive.length() || !writeDenied) throw new AssertionError("cross-app export was not read-only");
        out.put("crossAppBytes", sharedBytes).put("crossAppWriteDenied", true);
    }

    private static String readUtf8(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()]; int offset = 0, n;
            while (offset < data.length && (n = input.read(data, offset, data.length - offset)) != -1) offset += n;
            return new String(data, 0, offset, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static int countLines(String value) {
        if (value == null || value.length() == 0) return 0;
        int lines = 0; for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '\n') lines++;
        return value.endsWith("\n") ? lines : lines + 1;
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

    private void fixturePermission(Context target, Uri uri, boolean grant) {
        String action = grant ? "app.quietagent.test.GRANT_FIXTURE" : "app.quietagent.test.REVOKE_FIXTURE";
        target.sendBroadcast(new Intent(action).setClassName("app.quietagent.test",
                "app.quietagent.test.GrantReceiver").putExtra("uri", uri.toString()));
        SystemClock.sleep(75L);
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
