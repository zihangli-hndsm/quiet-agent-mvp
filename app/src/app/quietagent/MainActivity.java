package app.quietagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import app.quietagent.core.Plan;
import app.quietagent.security.Authorization;
import app.quietagent.security.AtomicFileStateStore;
import app.quietagent.security.AuthorizationManager;
import app.quietagent.security.TaskSpec;
import app.quietagent.ui.VcFlowView;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/** Main screen for the offline, read-only file assistant. */
public final class MainActivity extends Activity {
    private static final int REQUEST_FOLDER = 4107;
    private static final int BG = Color.rgb(242, 242, 247);
    private static final int CARD = Color.WHITE;
    private static final int GREEN = Color.rgb(24, 103, 67);
    private static final int GREEN_DARK = Color.rgb(15, 75, 48);
    private static final int INK = Color.rgb(28, 39, 34);
    private static final int MUTED = Color.rgb(92, 108, 99);
    private static final int LINE = Color.rgb(220, 229, 222);

    private Store store;
    private EditText requestInput;
    private TextView sourceValue;
    private TextView planValue;
    private TextView statusValue;
    private TextView resultValue;
    private ProgressBar progress;
    private Button startButton;
    private Button cancelButton;
    private Button shareReportButton;
    private Button openArchiveButton;
    private LinearLayout resultActions;
    private Uri sourceUri;
    private boolean qaMode;
    private String lastPreviewedRequest;
    private String errorText;
    private VcFlowView vcFlow;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusPoller = new Runnable() {
        @Override public void run() {
            refreshStatus();
            statusHandler.postDelayed(this, 700L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        store = new Store(this);
        recoverInterruptedTask();
        qaMode = getIntent() != null && getIntent().getBooleanExtra("qa_test", false);
        if (qaMode) buildScreen();
        else buildVcScreen();
        if (!TaskService.isRunning()) EntryNotification.show(this, false, null);
    }

    @Override protected void onResume() {
        super.onResume();
        statusHandler.removeCallbacks(statusPoller);
        statusHandler.post(statusPoller);
    }

    @Override protected void onPause() {
        statusHandler.removeCallbacks(statusPoller);
        super.onPause();
    }

    @Override protected void onDestroy() {
        statusHandler.removeCallbacks(statusPoller);
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (qaMode) Log.i("QuietProbe", "FOCUS " + hasFocus);
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = column(this, 20, 20, 20, 32);
        scroll.addView(root);
        setContentView(scroll);

        if (qaMode) addQaPanel(root);

        TextView eyebrow = label("QUIET AGENT  ·  离线文件助手", 12, GREEN, true);
        root.addView(eyebrow, lp(-1, -2, 0, 0, 0, 10));
        TextView title = label("静默整理", 34, INK, true);
        root.addView(title, lp(-1, -2, 0, 0, 0, 4));
        TextView intro = label("把整理意图交给本地规则，先预览计划，再由你明确开始。", 15, MUTED, false);
        intro.setLineSpacing(2f, 1f);
        root.addView(intro, lp(-1, -2, 0, 0, 0, 18));

        LinearLayout sourceCard = card(this);
        root.addView(sourceCard, lp(-1, -2, 0, 0, 0, 12));
        sourceCard.addView(label("1  选择源目录", 17, INK, true), lp(-1, -2, 18, 16, 18, 4));
        sourceValue = label("尚未选择目录", 14, MUTED, false);
        sourceValue.setMaxLines(2);
        sourceValue.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        sourceCard.addView(sourceValue, lp(-1, -2, 18, 0, 18, 10));
        LinearLayout sourceButtons = row(this);
        Button choose = button("挑选目录", GREEN, Color.WHITE);
        choose.setContentDescription("select-folder");
        choose.setOnClickListener(v -> chooseFolder());
        sourceButtons.addView(choose, lp(0, 48, 18, 0, 6, 16, 1f));
        Button demo = outlineButton("载入体验示例（样例）");
        demo.setContentDescription("demo-source");
        demo.setOnClickListener(v -> loadDemoSource());
        sourceButtons.addView(demo, lp(0, 48, 6, 0, 18, 16, 1f));
        sourceCard.addView(sourceButtons);

        LinearLayout requestCard = card(this);
        root.addView(requestCard, lp(-1, -2, 0, 0, 0, 12));
        requestCard.addView(label("2  描述整理方式", 17, INK, true), lp(-1, -2, 18, 16, 18, 4));
        TextView hint = label("支持去重、按类型或月份分组、PDF / 图片 / 文档过滤、最近 N 天。", 13, MUTED, false);
        hint.setLineSpacing(1.5f, 1f);
        requestCard.addView(hint, lp(-1, -2, 18, 0, 18, 8));
        requestInput = new EditText(this);
        requestInput.setTextSize(16);
        requestInput.setTextColor(INK);
        requestInput.setHintTextColor(Color.rgb(155, 167, 159));
        requestInput.setHint("例如：把这个目录里的文件去重，按类型归档");
        requestInput.setGravity(Gravity.TOP | Gravity.START);
        requestInput.setMinHeight(dp(108));
        requestInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        requestInput.setBackground(roundDrawable(Color.rgb(249, 251, 249), LINE, 10));
        requestInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        requestInput.setContentDescription("task-request");
        String savedRequest = store.getRequest();
        if (savedRequest != null) requestInput.setText(savedRequest);
        requestInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                store.setRequest(s == null ? "" : s.toString());
                if (qaMode) Log.i("QuietProbe", "TEXT length=" + (s == null ? 0 : s.length()));
                String current = s == null ? "" : s.toString();
                if (lastPreviewedRequest != null && !lastPreviewedRequest.equals(current)) {
                    planValue.setText("内容已修改，开始前将重新解析");
                    planValue.setTextColor(MUTED);
                }
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        requestCard.addView(requestInput, lp(-1, -2, 18, 0, 18, 10));
        planValue = label("尚未生成计划", 14, MUTED, false);
        planValue.setLineSpacing(1.5f, 1f);
        requestCard.addView(planValue, lp(-1, -2, 18, 0, 18, 8));
        Button preview = outlineButton("预览计划");
        preview.setContentDescription("preview-plan");
        preview.setOnClickListener(v -> previewPlan());
        requestCard.addView(preview, lp(-1, 44, 18, 0, 18, 16));

        LinearLayout runCard = card(this);
        root.addView(runCard, lp(-1, -2, 0, 0, 0, 12));
        runCard.addView(label("3  执行与回看", 17, INK, true), lp(-1, -2, 18, 16, 18, 4));
        TextView boundary = label("本应用只在设备本地读取源目录并生成归档，遵循只读、不上传、不删除、不移动。解析来自离线规则，无法像聊天模型一样理解任意表达。", 13, MUTED, false);
        boundary.setLineSpacing(1.5f, 1f);
        runCard.addView(boundary, lp(-1, -2, 18, 0, 18, 10));
        startButton = button("开始整理", GREEN, Color.WHITE);
        startButton.setContentDescription("start-task");
        startButton.setOnClickListener(v -> startTask());
        runCard.addView(startButton, lp(-1, 48, 18, 0, 18, 8));
        cancelButton = outlineButton("取消当前任务");
        cancelButton.setContentDescription("cancel-task");
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> cancelTask());
        runCard.addView(cancelButton, lp(-1, 44, 18, 0, 18, 8));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        progress.setMax(100);
        runCard.addView(progress, lp(-1, 4, 18, 4, 18, 6));
        statusValue = label("状态：空闲", 14, MUTED, false);
        statusValue.setLineSpacing(1.5f, 1f);
        runCard.addView(statusValue, lp(-1, -2, 18, 0, 18, 12));
        resultValue = label("完成后，这里会显示本地归档与报告。", 14, MUTED, false);
        resultValue.setLineSpacing(1.5f, 1f);
        runCard.addView(resultValue, lp(-1, -2, 18, 0, 18, 8));
        resultActions = row(this);
        shareReportButton = outlineButton("查看报告");
        shareReportButton.setContentDescription("view-report");
        shareReportButton.setOnClickListener(v -> openReport());
        openArchiveButton = outlineButton("导出归档");
        openArchiveButton.setContentDescription("export-archive");
        openArchiveButton.setOnClickListener(v -> shareResult("archive.zip", "application/zip"));
        resultActions.addView(shareReportButton, lp(0, 44, 18, 0, 6, 16, 1f));
        resultActions.addView(openArchiveButton, lp(0, 44, 6, 0, 18, 16, 1f));
        resultActions.setVisibility(View.GONE);
        runCard.addView(resultActions);

        refreshSource();
    }

    private void buildVcScreen() {
        vcFlow = new VcFlowView(this, new VcFlowView.Callback() {
            @Override public boolean canStartTask() { return !TaskService.isRunning(); }
            @Override public String onAuthorized(TaskSpec spec, Authorization authorization, List<Uri> photos, Uri tree) {
                try {
                    List<String> sources = new ArrayList<String>();
                    if (spec.type() == TaskSpec.TaskType.OCR) {
                        for (Uri uri : photos) sources.add(uri.toString());
                    } else if (tree != null) {
                        sources.add(tree.toString());
                    }
                    String id = new ReceiptJobStore(MainActivity.this).createPending(spec, authorization.nonce(), sources);
                    Intent run = new Intent(MainActivity.this, TaskService.class)
                            .setAction(TaskService.ACTION_RUN).putExtra("jobId", id);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(run); else startService(run);
                    return id;
                } catch (Exception error) {
                    throw new IllegalStateException("无法保存并提交任务：" + safeMessage(error), error);
                }
            }
            @Override public void onReportRequested(String jobId) {
                startActivity(new Intent(MainActivity.this, ReportActivity.class).putExtra("jobId", jobId));
            }
            @Override public void onExportRequested(String jobId) {
                exportV2(jobId);
            }
            @Override public void onAuditRequested(String auditJson) {
                new AlertDialog.Builder(MainActivity.this).setTitle("执行审计凭证")
                        .setMessage(auditJson).setPositiveButton("关闭", null).show();
            }
            @Override public void onClearTaskDataRequested(String jobId) {
                clearV2Job(jobId);
            }
        });
        setContentView(vcFlow);
    }

    private AuthorizationManager v2Authorizer() {
        return new AuthorizationManager(new AtomicFileStateStore(
                new File(getFilesDir(), "security/authorization.state")));
    }

    private void recoverInterruptedTask() {
        if (TaskService.isRunning()) return;
        try {
            JSONObject last = new JSONObject(store.readStatus());
            String id = last.optString("id", "");
            if ("INTERRUPTED".equals(last.optString("state")) && id.startsWith("receipt-")) {
                new ReceiptJobStore(this).recoverInterrupted(id, v2Authorizer());
            }
        } catch (Exception ignored) { }
    }

    private void exportV2(String jobId) {
        try {
            ReceiptJobStore jobs = new ReceiptJobStore(this);
            AuthorizationManager manager = v2Authorizer();
            jobs.recordEventAndPublishCredential(jobId, AuthorizationManager.EXPORT_CONFIRMED, manager);
            Uri uri = ShareProvider.uriFor(this, jobId, "archive.zip");
            Intent share = new Intent(Intent.ACTION_SEND).setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri("archive.zip", uri));
            startActivity(Intent.createChooser(share, "选择导出去向"));
            jobs.recordEventAndPublishCredential(jobId, AuthorizationManager.SHARE_SHEET_OPENED, manager);
        } catch (Exception error) {
            new AlertDialog.Builder(this).setTitle("暂时无法导出")
                    .setMessage(safeMessage(error)).setPositiveButton("知道了", null).show();
        }
    }

    private void clearV2Job(String jobId) {
        try {
            ReceiptJobStore jobs = new ReceiptJobStore(this);
            jobs.recordEventAndPublishCredential(jobId, AuthorizationManager.CLEARED, v2Authorizer());
            jobs.deletePrivateJob(jobId);
            store.writeStatus(new JSONObject().put("state", "IDLE").put("message", "任务本地数据已清除"));
        } catch (Exception error) {
            new AlertDialog.Builder(this).setTitle("未能清除")
                    .setMessage(safeMessage(error)).setPositiveButton("知道了", null).show();
        }
    }

    private void addQaPanel(LinearLayout root) {
        LinearLayout qa = card(this);
        root.addView(qa, lp(-1, -2, 0, 0, 0, 12));
        qa.addView(label("本地诊断（qa_test）", 16, GREEN_DARK, true), lp(-1, -2, 18, 16, 18, 4));
        TextView note = label("仅记录文本长度与窗口焦点，不模拟物理按键。", 13, MUTED, false);
        qa.addView(note, lp(-1, -2, 18, 0, 18, 8));
        EditText probe = new EditText(this);
        probe.setContentDescription("qa-input");
        probe.setSingleLine(true);
        probe.setHint("诊断输入");
        probe.setTextSize(15);
        probe.setInputType(InputType.TYPE_CLASS_TEXT);
        probe.setBackground(roundDrawable(Color.rgb(249, 251, 249), LINE, 10));
        probe.setPadding(dp(12), 0, dp(12), 0);
        probe.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { Log.i("QuietProbe", "TEXT length=" + (s == null ? 0 : s.length())); }
            @Override public void afterTextChanged(Editable e) { }
        });
        qa.addView(probe, lp(-1, 48, 18, 0, 18, 8));
        ScrollView logScroll = new ScrollView(this);
        TextView localList = label(qaRows(), 13, MUTED, false);
        localList.setLineSpacing(5f, 1f);
        logScroll.addView(localList);
        qa.addView(logScroll, lp(-1, 190, 18, 0, 18, 16));
    }

    private String qaRows() {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= 18; i++) {
            b.append(String.format(Locale.ROOT, "本地观察记录 %02d   等待界面事件\n", i));
        }
        return b.toString();
    }

    private void chooseFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQUEST_FOLDER);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (vcFlow != null && vcFlow.handleActivityResult(requestCode, resultCode, data)) return;
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException ex) {
            showError("无法保存目录读取授权，请重新选择目录。");
            return;
        }
        sourceUri = uri;
        store.setSourceUri(uri.toString());
        clearError();
        clearResult();
        refreshSource();
    }

    private void loadDemoSource() {
        try {
            Uri uri = DemoSource.prepare(this);
            sourceUri = uri;
            store.setSourceUri(uri.toString());
            clearError();
            clearResult();
            refreshSource();
            statusValue.setText("状态：已载入体验示例（样例目录），尚未开始整理");
        } catch (Exception ex) {
            showError("体验示例准备失败：" + safeMessage(ex));
        }
    }

    private void refreshSource() {
        String saved = store.getSourceUri();
        sourceUri = saved == null || saved.trim().isEmpty() ? null : Uri.parse(saved);
        if (sourceValue == null) return;
        if (sourceUri == null) {
            sourceValue.setText("尚未选择目录\n可挑选自己的目录，或载入标明为样例的体验目录");
            sourceValue.setTextColor(MUTED);
        } else {
            boolean sample = isDemoUri(sourceUri);
            sourceValue.setText(sample ? "体验示例（样例目录）" : "已选择目录：" + sourceDisplayName(sourceUri));
            sourceValue.setTextColor(sample ? GREEN : INK);
        }
    }

    private boolean isDemoUri(Uri uri) {
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) return false;
        String path = uri.getPath();
        return path != null && (path.endsWith("/demo") || path.endsWith("/demo/"));
    }

    private String sourceDisplayName(Uri uri) {
        String name = "";
        try {
            String treeId = DocumentsContract.getTreeDocumentId(uri);
            if (treeId != null && treeId.indexOf(':') >= 0) treeId = treeId.substring(treeId.indexOf(':') + 1);
            if (treeId != null) name = Uri.decode(treeId);
        } catch (RuntimeException ignored) { }
        if (name.trim().isEmpty()) {
            String last = uri.getLastPathSegment();
            if (last != null) name = Uri.decode(last);
        }
        if (name.indexOf('/') >= 0) name = name.substring(name.lastIndexOf('/') + 1);
        return name.trim().isEmpty() ? "已选择目录" : name.trim();
    }

    private void previewPlan() {
        try {
            Plan p = Plan.parse(requestInput.getText() == null ? "" : requestInput.getText().toString());
            clearError();
            planValue.setText("计划预览：" + p.summary());
            planValue.setTextColor(GREEN);
            lastPreviewedRequest = requestInput.getText() == null ? "" : requestInput.getText().toString();
        } catch (IllegalArgumentException ex) {
            lastPreviewedRequest = null;
            planValue.setText("计划无法解析：" + safeMessage(ex));
            planValue.setTextColor(Color.rgb(163, 72, 50));
        }
    }

    private void startTask() {
        String request = requestInput.getText() == null ? "" : requestInput.getText().toString().trim();
        if (sourceUri == null) { showError("请先选择自己的目录，或载入体验示例（样例目录）。"); return; }
        try {
            Plan plan = Plan.parse(request);
            clearError();
            planValue.setText("计划预览：" + plan.summary());
            planValue.setTextColor(GREEN);
            lastPreviewedRequest = request;
            clearResult();
        } catch (IllegalArgumentException ex) {
            lastPreviewedRequest = null;
            planValue.setText("计划无法解析：" + safeMessage(ex));
            planValue.setTextColor(Color.rgb(163, 72, 50));
            return;
        }
        store.setRequest(request);
        Intent run = new Intent(this, TaskService.class)
                .setAction(TaskService.ACTION_RUN)
                .putExtra("request", request)
                .putExtra("source", sourceUri.toString());
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(run); else startService(run);
            startButton.setEnabled(false);
            cancelButton.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
            statusValue.setText("状态：正在准备…");
            resultActions.setVisibility(View.GONE);
        } catch (RuntimeException ex) {
            showError("无法开始本地任务：" + safeMessage(ex));
        }
    }

    private void cancelTask() {
        try {
            startService(new Intent(this, TaskService.class).setAction(TaskService.ACTION_CANCEL));
            statusValue.setText("状态：正在请求取消…");
        } catch (RuntimeException ex) {
            showError("无法取消当前任务：" + safeMessage(ex));
        }
    }

    private void refreshStatus() {
        if (store == null) return;
        if (vcFlow == null && statusValue == null) return;
        if (vcFlow == null && errorText != null) return;
        String raw;
        try { raw = store.readStatus(); } catch (RuntimeException ex) { return; }
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            JSONObject o = new JSONObject(raw);
            String state = o.optString("state", "IDLE");
            String phase = o.optString("phase", "");
            int done = o.optInt("done", 0);
            int total = o.optInt("total", 0);
            String message = o.optString("message", "");
            if (vcFlow != null) {
                Long totalCents = o.has("recognizedTotalCents") && !o.isNull("recognizedTotalCents")
                        ? Long.valueOf(o.optLong("recognizedTotalCents")) : null;
                vcFlow.updateTaskStatus(o.optString("id", ""), state, o.optInt("selected", 0),
                        o.optInt("unique", 0), o.optInt("duplicates", 0), totalCents, message);
                return;
            }
            StringBuilder line = new StringBuilder("状态：").append(stateLabel(state));
            String phaseLabel = phaseLabel(phase);
            if (!phaseLabel.isEmpty()) line.append(" · ").append(phaseLabel);
            if (total > 0) line.append(" · ").append(done).append('/').append(total);
            if (!message.isEmpty()) line.append("\n").append(message);
            if ("RUNNING".equals(state)) line.append("\n可以离开此页，完成后回来查看");
            statusValue.setText(line.toString());
            statusValue.setTextColor(MUTED);
            boolean active = "RUNNING".equals(state) || "INTERRUPTED".equals(state);
            startButton.setEnabled(!"RUNNING".equals(state));
            cancelButton.setVisibility("RUNNING".equals(state) ? View.VISIBLE : View.GONE);
            progress.setVisibility("RUNNING".equals(state) ? View.VISIBLE : View.GONE);
            if (total > 0) progress.setProgress(Math.max(0, Math.min(100, done * 100 / total)));
            if ("SUCCEEDED".equals(state)) {
                String id = o.optString("id", "");
                int selected = o.optInt("selected", 0);
                int unique = o.optInt("unique", 0);
                int duplicates = o.optInt("duplicates", 0);
                resultValue.setText("已生成本地归档与报告。\n已选 " + selected + " 项 · 写入 " + unique + " 项 · 去重跳过 " + duplicates + " 项");
                resultActions.setVisibility(id.isEmpty() ? View.GONE : View.VISIBLE);
            } else if ("FAILED".equals(state) || "CANCELLED".equals(state) || "INTERRUPTED".equals(state)) {
                resultActions.setVisibility(View.GONE);
            }
        } catch (Exception ignored) {
            statusValue.setText("状态：暂时无法读取");
        }
    }

    private String stateLabel(String state) {
        if ("RUNNING".equals(state)) return "整理中";
        if ("SUCCEEDED".equals(state)) return "已完成";
        if ("FAILED".equals(state)) return "失败";
        if ("CANCELLED".equals(state)) return "已取消";
        if ("INTERRUPTED".equals(state)) return "已中断";
        return "空闲";
    }

    private String phaseLabel(String phase) {
        if (phase == null || phase.trim().isEmpty()) return "";
        if ("已核验".equals(phase) || "已停止".equals(phase) || "未完成".equals(phase) || "准备".equals(phase)) return phase;
        if ("select".equals(phase)) return "筛选文件";
        if ("hash".equals(phase)) return "核对文件内容";
        if ("verify".equals(phase)) return "回读核验";
        if ("complete".equals(phase)) return "已完成";
        if ("SCANNING".equalsIgnoreCase(phase) || "SCAN".equalsIgnoreCase(phase)) return "扫描目录";
        if ("PLANNING".equalsIgnoreCase(phase) || "PLAN".equalsIgnoreCase(phase)) return "生成计划";
        if ("ARCHIVING".equalsIgnoreCase(phase) || "ARCHIVE".equalsIgnoreCase(phase)) return "生成归档";
        if ("REPORTING".equalsIgnoreCase(phase) || "REPORT".equalsIgnoreCase(phase)) return "生成报告";
        if ("DONE".equalsIgnoreCase(phase)) return "已完成";
        return "处理中";
    }

    private void shareResult(String fileName, String type) {
        String jobId = currentJobId();
        if (jobId.isEmpty()) { showError("当前还没有可分享的结果。"); return; }
        try {
            Uri uri = ShareProvider.uriFor(this, jobId, fileName);
            Intent share = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.setClipData(ClipData.newRawUri(fileName, uri));
            startActivity(Intent.createChooser(share, "分享"));
        } catch (RuntimeException ex) { showError("无法分享结果：" + safeMessage(ex)); }
    }

    private void openReport() {
        String jobId = currentJobId();
        if (jobId.isEmpty()) { showError("当前还没有可查看的报告。"); return; }
        try {
            startActivity(new Intent(this, ReportActivity.class).putExtra("jobId", jobId));
        } catch (RuntimeException ex) { showError("无法打开报告：" + safeMessage(ex)); }
    }

    private String currentJobId() {
        try { return new JSONObject(store.readStatus()).optString("id", ""); }
        catch (Exception ignored) { return ""; }
    }

    private void showError(String message) {
        errorText = message;
        if (statusValue != null) {
            statusValue.setText("提示：" + message);
            statusValue.setTextColor(Color.rgb(163, 72, 50));
        }
    }

    private void clearError() {
        errorText = null;
    }

    private void clearResult() {
        if (resultValue != null) resultValue.setText("完成后，这里会显示本地归档与报告。");
        if (resultActions != null) resultActions.setVisibility(View.GONE);
    }

    private static String safeMessage(Throwable t) {
        String s = t == null ? "未知错误" : t.getMessage();
        return s == null || s.trim().isEmpty() ? "未知错误" : s;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout column(Context c, int l, int t, int r, int b) {
        LinearLayout x = row(c);
        x.setOrientation(LinearLayout.VERTICAL);
        x.setPadding(dp(l), dp(t), dp(r), dp(b));
        return x;
    }

    private LinearLayout row(Context c) {
        LinearLayout x = new LinearLayout(c);
        x.setOrientation(LinearLayout.HORIZONTAL);
        x.setGravity(Gravity.CENTER_VERTICAL);
        return x;
    }

    private LinearLayout card(Context c) {
        LinearLayout x = new LinearLayout(c);
        x.setOrientation(LinearLayout.VERTICAL);
        x.setBackground(roundDrawable(CARD, LINE, 14));
        return x;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private Button button(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(roundDrawable(bg, bg, 10));
        return b;
    }

    private Button outlineButton(String text) {
        Button b = button(text, Color.TRANSPARENT, GREEN);
        b.setBackground(roundDrawable(Color.TRANSPARENT, GREEN, 10));
        return b;
    }

    private android.graphics.drawable.GradientDrawable roundDrawable(int fill, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radius));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b, float weight) {
        LinearLayout.LayoutParams p = lp(w, h, l, t, r, b);
        p.weight = weight;
        return p;
    }
}
