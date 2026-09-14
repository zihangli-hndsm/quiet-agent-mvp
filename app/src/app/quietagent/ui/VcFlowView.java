package app.quietagent.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.quietagent.security.AtomicFileStateStore;
import app.quietagent.LlmIntentRouter;
import app.quietagent.SemanticFiles;
import app.quietagent.security.Authorization;
import app.quietagent.security.AuthorizationException;
import app.quietagent.security.AuthorizationManager;
import app.quietagent.security.TaskSpec;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Native v0.2 presentation flow. Host code owns execution and file export. */
public final class VcFlowView extends ScrollView {
    public static final int REQUEST_TICKET_PHOTOS = 5201;
    public static final int REQUEST_ORIGINAL_TREE = 5202;

    public interface Callback {
        boolean canStartTask();
        /** Persist the authorized private job, start it with jobId only, and return that id. */
        String onAuthorized(TaskSpec spec, Authorization authorization, List<Uri> photos, Uri sourceTree);
        void onReportRequested(String jobId);
        void onExportRequested(String jobId);
        void onAuditRequested(String auditJson);
        void onClearTaskDataRequested(String jobId);
    }

    private static final int BG = Color.rgb(242, 242, 247);
    private static final int BLUE = Color.rgb(0, 122, 255);
    private static final int INK = Color.rgb(28, 28, 30);
    private static final int MUTED = Color.rgb(99, 99, 102);
    private static final int LINE = Color.TRANSPARENT;
    private final Activity activity;
    private final Callback callback;
    private final AuthorizationManager authorizer;
    private final LinearLayout root;
    private final TextView modeLabel;
    private final TextView selectionLabel;
    private final TextView permissionLabel;
    private final TextView planLabel;
    private final TextView resultLabel;
    private final EditText intentInput;
    private final Button understandButton;
    private final Button chooseButton;
    private final Button authorizeButton;
    private final Button reportButton;
    private final Button exportButton;
    private final Button auditButton;
    private final Button clearButton;
    private final List<Uri> photoUris = new ArrayList<Uri>();
    private Mode mode = Mode.TICKET;
    private Uri sourceTree;
    private Authorization authorization;
    private TaskSpec authorizedSpec;
    private String jobId = "";
    private String llmPlan = "";
    private String llmRisk = "";
    private boolean llmReady;
    /** True while the user selected the work mode directly instead of routing it through the LLM. */
    private boolean manualMode = true;
    /** Manual mode is ready only after a scope has been selected and its local notice is shown. */
    private boolean manualReady;
    private boolean riskReady;
    private boolean semanticBusy;
    private boolean contentClassification;
    private String semanticSummary;
    private boolean applyingRoute;
    /** Monotonically increases whenever the user changes the model input or route. */
    private long llmRevision;

    private enum Mode { TICKET, ORIGINAL }

    public VcFlowView(Context context, Callback callback) {
        super(context);
        if (!(context instanceof Activity)) throw new IllegalArgumentException("VcFlowView needs an Activity context");
        this.activity = (Activity) context;
        this.callback = callback == null ? new EmptyCallback() : callback;
        this.authorizer = new AuthorizationManager(new AtomicFileStateStore(
                new File(context.getFilesDir(), "security/authorization.state")));
        setFillViewport(true);
        setBackgroundColor(BG);
        root = column(20, 20, 20, 32);
        addView(root);

        TextView eyebrow = text("QUIET AGENT  ·  本地任务授权", 12, BLUE, true);
        root.addView(eyebrow, lp(-1, -2, 0, 0, 0, 10));
        TextView title = text("Quiet Agent", 30, INK, true);
        root.addView(title, lp(-1, -2, 0, 0, 0, 5));
        TextView intro = text("从通知进入，选择工作方式或描述任务，再授权开始。", 15, MUTED, false);
        intro.setLineSpacing(2f, 1f);
        root.addView(intro, lp(-1, -2, 0, 0, 0, 18));

        LinearLayout intentCard = card();
        root.addView(intentCard, lp(-1, -2, 0, 0, 0, 12));
        intentCard.addView(text("任务", 17, INK, true), lp(-1, -2, 18, 16, 18, 5));
        TextView intentHint = text("可以直接选择工作方式和范围；如果需要，也可以让模型理解一句自然语言任务。模型只读取这句话，不会读取照片、文件名或文件内容。\n", 13, MUTED, false);
        intentHint.setLineSpacing(2f, 1f);
        intentCard.addView(intentHint, lp(-1, -2, 18, 0, 18, 8));
        intentInput = new EditText(context);
        intentInput.setTextSize(16);
        intentInput.setTextColor(INK);
        intentInput.setHintTextColor(Color.rgb(142, 142, 147));
        intentInput.setHint("例如：整理这些餐饮票据，做一个本地待核对汇总");
        intentInput.setSingleLine(false);
        intentInput.setMinHeight(dp(76));
        intentInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        intentInput.setBackground(roundDrawable(Color.rgb(242, 242, 247), LINE, 10));
        intentInput.setContentDescription("llm-task-intent");
        intentInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                invalidateLlmDecision();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        intentCard.addView(intentInput, lp(-1, -2, 18, 0, 18, 10));
        understandButton = button("理解任务", BLUE, Color.WHITE);
        understandButton.setContentDescription("llm-understand-task");
        understandButton.setOnClickListener(v -> understandIntent());
        intentCard.addView(understandButton, lp(-1, 48, 18, 0, 18, 16));

        LinearLayout modeCard = card();
        root.addView(modeCard, lp(-1, -2, 0, 0, 0, 12));
        modeCard.addView(text("工作方式", 17, INK, true), lp(-1, -2, 18, 16, 18, 5));
        modeLabel = text("默认：票据整理", 14, BLUE, true);
        modeCard.addView(modeLabel, lp(-1, -2, 18, 0, 18, 8));
        LinearLayout modeButtons = row();
        Button ticket = outlineButton("票据整理");
        ticket.setContentDescription("mode-ticket");
        ticket.setOnClickListener(v -> switchMode(Mode.TICKET));
        modeButtons.addView(ticket, lp(0, 46, 18, 0, 6, 16, 1f));
        Button original = outlineButton("原文件整理");
        original.setContentDescription("mode-original");
        original.setOnClickListener(v -> switchMode(Mode.ORIGINAL));
        modeButtons.addView(original, lp(0, 46, 6, 0, 18, 16, 1f));
        modeCard.addView(modeButtons);
        Button sms = outlineButton("短信整理 · 提取表格 / 筛选垃圾短信");
        sms.setContentDescription("mode-sms");
        sms.setOnClickListener(v -> activity.startActivity(new Intent(activity, app.quietagent.SmsActivity.class)));
        modeCard.addView(sms, lp(-1, 48, 18, 0, 18, 16));
        Button workspace = outlineButton("受控工作区 · 比较简历 / Office 文件");
        workspace.setContentDescription("mode-workspace");
        workspace.setOnClickListener(v -> activity.startActivity(new Intent(activity, app.quietagent.workspace.WorkspaceActivity.class)));
        modeCard.addView(workspace, lp(-1, 48, 18, 0, 18, 16));

        LinearLayout selectCard = card();
        root.addView(selectCard, lp(-1, -2, 0, 0, 0, 12));
        selectCard.addView(text("选择范围", 17, INK, true), lp(-1, -2, 18, 16, 18, 5));
        selectionLabel = text("请选择要整理的票据照片", 15, MUTED, false);
        selectionLabel.setLineSpacing(2f, 1f);
        selectCard.addView(selectionLabel, lp(-1, -2, 18, 0, 18, 8));
        chooseButton = button("选择照片", BLUE, Color.WHITE);
        chooseButton.setContentDescription("select-ticket-photos");
        chooseButton.setOnClickListener(v -> selectCurrentScope());
        selectCard.addView(chooseButton, lp(-1, 48, 18, 0, 18, 12));
        permissionLabel = text("风险提示：照片可能包含姓名、金额、地址等敏感信息。仅在本机处理，不上传原图。", 13, MUTED, false);
        permissionLabel.setLineSpacing(2f, 1f);
        selectCard.addView(permissionLabel, lp(-1, -2, 18, 0, 18, 16));

        LinearLayout planCard = card();
        root.addView(planCard, lp(-1, -2, 0, 0, 0, 12));
        planCard.addView(text("授权前检查", 17, INK, true), lp(-1, -2, 18, 16, 18, 5));
        planLabel = text("允许的操作会显示在这里。", 14, MUTED, false);
        planLabel.setLineSpacing(2f, 1f);
        planCard.addView(planLabel, lp(-1, -2, 18, 0, 18, 10));
        authorizeButton = button("授权并开始", BLUE, Color.WHITE);
        authorizeButton.setContentDescription("authorize-and-start");
        setAuthorizeEnabled(false);
        authorizeButton.setOnClickListener(v -> authorizeAndStart());
        planCard.addView(authorizeButton, lp(-1, 50, 18, 0, 18, 16));

        LinearLayout resultCard = card();
        root.addView(resultCard, lp(-1, -2, 0, 0, 0, 12));
        resultCard.addView(text("结果与审计", 17, INK, true), lp(-1, -2, 18, 16, 18, 5));
        resultLabel = text("完成后可在这里回看报告。票据任务会先进入“待核对”。", 14, MUTED, false);
        resultLabel.setLineSpacing(2f, 1f);
        resultCard.addView(resultLabel, lp(-1, -2, 18, 0, 18, 10));
        LinearLayout resultButtons = row();
        reportButton = outlineButton("查看报告");
        reportButton.setContentDescription("vc-view-report");
        reportButton.setEnabled(false);
        reportButton.setOnClickListener(v -> callback.onReportRequested(jobId));
        resultButtons.addView(reportButton, lp(0, 44, 18, 0, 6, 8, 1f));
        exportButton = outlineButton("导出归档");
        exportButton.setContentDescription("vc-export-archive");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> confirmExport());
        resultButtons.addView(exportButton, lp(0, 44, 6, 0, 18, 8, 1f));
        resultCard.addView(resultButtons);
        auditButton = outlineButton("查看审计记录");
        auditButton.setContentDescription("vc-audit");
        auditButton.setOnClickListener(v -> showAudit());
        resultCard.addView(auditButton, lp(-1, 44, 18, 0, 18, 8));
        clearButton = outlineButton("清除任务数据");
        clearButton.setContentDescription("vc-clear-task-data");
        clearButton.setOnClickListener(v -> confirmClear());
        resultCard.addView(clearButton, lp(-1, 44, 18, 0, 18, 16));
        // Establish the selected scope before asking the model to understand the request.
        root.removeView(intentCard);
        root.addView(intentCard, root.indexOfChild(selectCard) + 1);
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_TICKET_PHOTOS && requestCode != REQUEST_ORIGINAL_TREE) return false;
        if (resultCode != Activity.RESULT_OK || data == null) return true;
        if (requestCode == REQUEST_TICKET_PHOTOS) acceptPhotos(data);
        else acceptTree(data);
        return true;
    }

    /** Reflects persisted worker state without opening a window or changing focus. */
    public void updateTaskStatus(String statusJobId, String state, int selected, int unique,
                                 int duplicates, Long totalCents, String message) {
        if (statusJobId == null || !statusJobId.equals(jobId)) return;
        boolean complete = "SUCCEEDED".equals(state);
        reportButton.setEnabled(complete);
        exportButton.setEnabled(complete);
        if ("RUNNING".equals(state)) {
            resultLabel.setText("正在本机后台处理。可以切到其他应用，屏幕、焦点和键盘不会被占用。\n" + safe(message));
        } else if (complete) {
            if (mode == Mode.ORIGINAL) {
                resultLabel.setText("已选 " + selected + " 个文件 · 归档 " + unique + " 个 · 重复 " + duplicates + " 个\n" + safe(message));
                return;
            }
            String amount = totalCents == null ? "待核对" : formatCents(totalCents.longValue()) + " 元";
            resultLabel.setText("已选 " + selected + " 张 · 归档 " + unique + " 张 · 重复 " + duplicates
                    + " 张\n自动识别合计：" + amount + "（待核对）");
        } else if ("FAILED".equals(state) || "CANCELLED".equals(state) || "INTERRUPTED".equals(state)) {
            resultLabel.setText(safe(message) + "\n本次没有发布完成包；重新执行需要再次授权。");
        }
    }

    private void switchMode(Mode next) {
        if (semanticBusy) return;
        if (mode == next && applyingRoute) return;
        if (!applyingRoute) {
            manualMode = true;
            invalidateLlmDecision();
        } else {
            manualMode = false;
        }
        mode = next;
        authorization = null;
        authorizedSpec = null;
        manualReady = false;
        riskReady = false;
        photoUris.clear();
        sourceTree = null;
        jobId = "";
        reportButton.setEnabled(false);
        exportButton.setEnabled(false);
        if (mode == Mode.TICKET) {
            modeLabel.setText("默认：票据整理");
            selectionLabel.setText("请选择要整理的票据照片");
            chooseButton.setText("选择票据照片");
            chooseButton.setContentDescription("select-ticket-photos");
            permissionLabel.setText(defaultPermissionNotice());
            authorizeButton.setText("授权并开始");
        } else {
            modeLabel.setText("原文件整理：只读归档");
            selectionLabel.setText("请选择要读取的目录");
            chooseButton.setText("挑选原文件目录");
            chooseButton.setContentDescription("select-original-folder");
            permissionLabel.setText(defaultPermissionNotice());
            authorizeButton.setText("授权并开始");
        }
        planLabel.setText("选择已改变，之前的授权已失效。\n允许的操作会显示在这里。");
        setAuthorizeEnabled(false);
        resultLabel.setText("完成后可在这里回看报告。票据任务会先进入“待核对”。");
    }

    private void selectCurrentScope() {
        if (semanticBusy) return;
        if (mode == Mode.TICKET) {
            new AlertDialog.Builder(activity).setTitle("照片可能包含敏感信息")
                    .setMessage("票据照片可能包含姓名、金额、地址或账号。继续后只在本机处理，选择结果会用于本次授权。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("继续选择", (d, w) -> openPhotoPicker()).show();
        } else {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            activity.startActivityForResult(intent, REQUEST_ORIGINAL_TREE);
        }
    }

    private void openPhotoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.startActivityForResult(intent, REQUEST_TICKET_PHOTOS);
    }

    private void acceptPhotos(Intent data) {
        llmRevision++;
        understandButton.setEnabled(true);
        understandButton.setText("理解任务");
        if (!manualMode) { llmReady = false; riskReady = false; }
        semanticSummary = null;
        photoUris.clear();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount() && photoUris.size() < 30; i++) addPhoto(clip.getItemAt(i).getUri(), data);
        } else if (data.getData() != null) addPhoto(data.getData(), data);
        authorization = null;
        authorizedSpec = null;
        if (photoUris.isEmpty()) {
            selectionLabel.setText("没有选择照片");
            planLabel.setText("请选择至少 1 张票据照片。");
            setAuthorizeEnabled(false);
            return;
        }
        selectionLabel.setText("已选择 " + photoUris.size() + " 张票据照片（最多30张）");
        if (manualMode) {
            manualReady = true;
            riskReady = true;
        }
        planLabel.setText(preAuthorizationText());
        setAuthorizeEnabled(isReadyForAuthorization());
        resultLabel.setText("待核对：授权后会生成本地报告，完成后请逐项检查。");
    }

    private void addPhoto(Uri uri, Intent data) {
        if (uri == null || photoUris.size() >= 30) return;
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags == 0) throw new SecurityException("missing read grant");
            activity.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ex) {
            showMessage("照片读取授权没有保存，请重新选择。");
            return;
        }
        photoUris.add(uri);
    }

    private void acceptTree(Intent data) {
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            activity.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ex) {
            showMessage("目录授权没有保存，请重新选择。");
            return;
        }
        sourceTree = uri;
        llmRevision++;
        understandButton.setEnabled(true);
        understandButton.setText("理解任务");
        if (!manualMode) { llmReady = false; riskReady = false; }
        semanticSummary = null;
        authorization = null;
        authorizedSpec = null;
        if (manualMode) {
            manualReady = true;
            riskReady = true;
        }
        selectionLabel.setText("已选择目录：" + displayName(uri));
        planLabel.setText(preAuthorizationText());
        setAuthorizeEnabled(isReadyForAuthorization());
        resultLabel.setText("准备完成：授权后将在本机生成归档。");
    }

    private void authorizeAndStart() {
        if (semanticBusy) return;
        if (!isReadyForAuthorization()) {
            showMessage(manualMode ? "请先选择范围并阅读本地风险提示。" : "请先让助手理解任务，并阅读模型生成的风险提示。");
            return;
        }
        String scope = scope();
        if (scope.isEmpty()) { showMessage("请先选择范围。"); return; }
        if (!callback.canStartTask()) { showMessage("已有任务正在处理，请等待完成或先取消。"); return; }
        if (!manualMode && contentClassification && mode == Mode.ORIGINAL && semanticSummary == null) {
            startContentAnalysis();
            return;
        }
        try {
            TaskSpec spec = mode == Mode.TICKET
                    ? TaskSpec.ocr(scope, "票据照片 · 本地摘要 · 待核对")
                    : TaskSpec.archive(scope, semanticSummary == null ? "原文件 · 只读整理归档" : semanticSummary);
            Authorization auth = authorizer.authorize(spec);
            authorization = auth;
            authorizedSpec = spec;
            List<Uri> selected = Collections.unmodifiableList(new ArrayList<Uri>(photoUris));
            jobId = callback.onAuthorized(spec, auth, selected, sourceTree);
            if (jobId == null || jobId.trim().isEmpty()) throw new IllegalStateException("任务没有成功提交");
            resultLabel.setText(mode == Mode.TICKET
                    ? "待核对：任务已授权并提交。完成后查看报告，逐项核对票据内容。"
                    : "任务已授权并提交。完成后查看本地归档和报告。");
            reportButton.setEnabled(false);
            exportButton.setEnabled(false);
            setAuthorizeEnabled(false);
            semanticSummary = null;
            riskReady = false;
        } catch (AuthorizationException ex) {
            showMessage("授权未保存，任务没有开始：" + ex.getMessage());
        } catch (RuntimeException ex) {
            showMessage("授权未开始：" + safe(ex.getMessage()));
        }
    }

    private void confirmExport() {
        new AlertDialog.Builder(activity).setTitle("确认导出归档")
                .setMessage("归档可能包含你选择的文件或票据摘要。确认后将打开系统分享面板，由你选择去向。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认导出", (d, w) -> callback.onExportRequested(jobId)).show();
    }

    private void showAudit() {
        try {
            callback.onAuditRequested(authorizer.exportAuditCredential());
        } catch (AuthorizationException ex) {
            showMessage("审计记录暂不可读：" + ex.getMessage());
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(activity).setTitle("准备清除任务数据？")
                .setMessage("这会清除本机任务记录、授权状态和审计凭证。源目录与原文件不会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("下一步", (d, w) -> new AlertDialog.Builder(activity)
                        .setTitle("再次确认清除")
                        .setMessage("确认清除全部任务数据？此操作完成后无法从本应用恢复记录。")
                        .setNegativeButton("返回", null)
                        .setPositiveButton("确认清除", (d2, w2) -> {
                            callback.onClearTaskDataRequested(jobId);
                            showMessage("已提交清除请求；源目录和原文件保持不变。");
                        }).show()).show();
    }

    private String scope() {
        if (mode == Mode.ORIGINAL) return sourceTree == null ? "" : sourceTree.toString();
        StringBuilder out = new StringBuilder();
        for (Uri uri : photoUris) { if (out.length() > 0) out.append('\n'); out.append(uri); }
        return out.toString();
    }

    private String displayName(Uri uri) {
        String value = uri == null ? "" : Uri.decode(uri.getLastPathSegment());
        if (value == null || value.trim().isEmpty()) return "已授权目录";
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) value = value.substring(colon + 1);
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private void showMessage(String message) {
        planLabel.setText(message);
        planLabel.setTextColor(Color.rgb(163, 72, 50));
    }

    private void understandIntent() {
        if (semanticBusy) return;
        if (scope().isEmpty()) {
            showMessage("请先选择本次处理目录或照片。Android 不允许助手直接读取手机根目录；系统授权后才能寻找文件。");
            return;
        }
        final String request = intentInput.getText() == null ? "" : intentInput.getText().toString().trim();
        if (request.length() < 2) { showMessage("请先用一句话描述任务。"); return; }
        manualMode = false;
        manualReady = false;
        riskReady = false;
        llmReady = false;
        setAuthorizeEnabled(false);
        final long requestRevision = ++llmRevision;
        final Mode selectedMode = mode;
        understandButton.setEnabled(false);
        understandButton.setText("正在理解任务…");
        planLabel.setText("正在请求模型计划。只发送这句任务描述；照片、文件名、文件内容和审计记录不会发送。");
        new Thread(new Runnable() { @Override public void run() {
            try {
                final LlmIntentRouter.Decision decision = LlmIntentRouter.understand(activity.getApplicationContext(),
                    (selectedMode == Mode.ORIGINAL ? "已选择本地文件目录。" : "已选择票据照片。") + request);
                activity.runOnUiThread(new Runnable() { @Override public void run() {
                    if (requestRevision != llmRevision) return;
                    applyDecision(decision);
                } });
            } catch (final Exception error) {
                activity.runOnUiThread(new Runnable() { @Override public void run() {
                    if (requestRevision != llmRevision) return;
                    llmReady = false;
                    understandButton.setEnabled(true);
                    understandButton.setText("重试");
                    showMessage("无法获得模型计划，任务没有开始：" + safe(error.getMessage()));
                } });
            }
        }}, "quiet-llm-intent").start();
    }

    private void applyDecision(LlmIntentRouter.Decision decision) {
        understandButton.setEnabled(true);
        understandButton.setText("重新理解");
        if (decision == null || decision.route == LlmIntentRouter.Route.UNSUPPORTED) {
            llmReady = false;
            showMessage("模型没有把这句话路由到现有的“票据整理”或“原文件整理”任务。请换一种描述。");
            return;
        }
        applyingRoute = true;
        try { switchMode(decision.route == LlmIntentRouter.Route.TICKET ? Mode.TICKET : Mode.ORIGINAL); }
        finally { applyingRoute = false; }
        manualMode = false;
        manualReady = false;
        llmPlan = safe(decision.plan);
        contentClassification = decision.contentClassification;
        llmRisk = safe(decision.risk);
        llmReady = true;
        riskReady = true;
        permissionLabel.setText(defaultPermissionNotice());
        planLabel.setText(preAuthorizationText());
        setAuthorizeEnabled(isReadyForAuthorization());
    }

    private void invalidateLlmDecision() {
        boolean keepManualRisk = manualMode && manualReady && riskReady;
        llmRevision++;
        llmReady = false;
        llmPlan = "";
        llmRisk = "";
        riskReady = keepManualRisk;
        understandButton.setEnabled(true);
        understandButton.setText("理解任务");
        setAuthorizeEnabled(false);
    }

    private String defaultPermissionNotice() {
        if (manualMode) return mode == Mode.TICKET
                ? "本地风险提示：照片可能包含姓名、金额、地址等敏感信息。仅在本机处理，不上传原图。"
                : "本地风险提示：你选择的目录可能包含个人资料、合同和财务信息。只读取本次授权目录，执行只读扫描、去重和生成本地归档；不会删除、移动、上传或发送原文件。完成后如需导出，还会再次请求确认。";
        if (!llmReady) return mode == Mode.TICKET
                ? "先让模型理解任务。照片可能包含姓名、金额、地址等敏感信息。"
                : "先让模型理解任务。随后只读取你明确授权的目录。";
        String local = mode == Mode.TICKET
                ? "照片仅在本机处理，不上传原图。"
                : "目录仅做只读整理，不删除、移动或上传原文件。";
        return "模型风险提示：" + llmRisk + "\n" + local;
    }

    private String preAuthorizationText() {
        String allowed = mode == Mode.TICKET
                ? "允许：本地提取摘要、生成待核对报告。\n禁止：上传、发送或自动分享原图。"
                : "允许：只读扫描、去重、生成本地归档和报告。\n禁止：删除、移动、上传或发送原文件。";
        if (!manualMode && contentClassification && mode == Mode.ORIGINAL) allowed += "\n内容分类：先本地读取 Office/文本，再单独确认上传摘要和分类计划。";
        String notice = manualMode ? defaultPermissionNotice() : "模型风险提示：" + safe(llmRisk);
        String plan = manualMode ? "手动选择：" + (mode == Mode.TICKET ? "票据整理" : "原文件整理") : "模型理解：" + safe(llmPlan);
        return plan + "\n" + allowed + "\n" + notice + "\n系统文件授权不等于本次任务授权；选择变化会使旧授权失效。";
    }

    private boolean isReadyForAuthorization() {
        boolean hasScope = !scope().isEmpty();
        if (!hasScope || !riskReady) return false;
        return llmReady || (manualMode && manualReady);
    }

    private void startContentAnalysis() {
        final String selectedScope = scope();
        final String request = intentInput.getText().toString().trim();
        new AlertDialog.Builder(activity).setTitle("授权本地读取内容")
            .setMessage("将在已选目录内读取并快照文件，支持 DOCX、XLSX、PPTX、TXT、Markdown、CSV。最多30个文件。先展示实际上传摘要；当前不发送文件内容。无法读取的文件标为待核对。")
            .setNegativeButton("取消", null)
            .setPositiveButton("授权本地分析", (dialog, which) -> {
                semanticBusy = true;
                intentInput.setEnabled(false);
                setAuthorizeEnabled(false);
                planLabel.setText("正在本地读取文档并遮罩明显敏感字段…");
                new Thread(() -> {
                    try {
                        final SemanticFiles prepared = SemanticFiles.prepare(activity.getApplicationContext(), selectedScope, request);
                        activity.runOnUiThread(() -> showUploadPreview(prepared));
                    } catch (Exception e) { activity.runOnUiThread(() -> endSemantic("本地分析失败：" + e.getMessage())); }
                }, "quiet-content-preview").start();
            }).show();
    }

    private void showUploadPreview(SemanticFiles prepared) {
        if (activity.isFinishing() || activity.isDestroyed()) { SemanticFiles.discard(prepared.dir); return; }
        new AlertDialog.Builder(activity).setTitle("确认发送以下摘要")
            .setMessage("以下为实际发送给 DeepSeek 的文本。遮罩无法保证移除所有敏感信息，请检查；原文件和文件名不会发送。\n\n" + prepared.payload)
            .setCancelable(false)
            .setNegativeButton("取消并清除", (d,w) -> { SemanticFiles.discard(prepared.dir); endSemantic("已取消，未上传摘要。"); })
            .setPositiveButton("确认上传并分类", (d,w) -> {
                planLabel.setText("正在请求模型分类，完成后还需确认归档计划…");
                new Thread(() -> {
                    try {
                        prepared.classify(activity.getApplicationContext());
                        final String preview = prepared.preview();
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) { SemanticFiles.discard(prepared.dir); return; }
                            new AlertDialog.Builder(activity).setTitle("确认用途分类计划")
                                .setMessage(preview + "仅生成资料包，不修改原文件。模型建议请核对。")
                                .setCancelable(false)
                                .setNegativeButton("取消并清除", (a,b) -> { SemanticFiles.discard(prepared.dir); endSemantic("已取消归档；已发送的摘要无法撤回。"); })
                                .setPositiveButton("授权并生成资料包", (a,b) -> {
                                    try {
                                        semanticSummary = prepared.seal();
                                        semanticBusy = false;
                                        intentInput.setEnabled(true);
                                        authorizeAndStart();
                                        SemanticFiles.discard(prepared.dir);
                                        semanticSummary = null;
                                    } catch (Exception e) { SemanticFiles.discard(prepared.dir); endSemantic("分类计划保存失败，任务未开始。"); }
                                }).show();
                        });
                    } catch (Exception e) {
                        SemanticFiles.discard(prepared.dir);
                        activity.runOnUiThread(() -> endSemantic("分类失败，未生成资料包：" + e.getMessage()));
                    }
                }, "quiet-content-classification").start();
            }).show();
    }

    private void endSemantic(String message) {
        semanticBusy = false;
        semanticSummary = null;
        intentInput.setEnabled(true);
        setAuthorizeEnabled(isReadyForAuthorization());
        showMessage(message);
    }

    private static String safe(String value) { return value == null || value.trim().isEmpty() ? "未知错误" : value; }
    private void setAuthorizeEnabled(boolean enabled) {
        authorizeButton.setEnabled(enabled);
        authorizeButton.setAlpha(enabled ? 1f : 0.42f);
    }
    private static String formatCents(long cents) {
        long absolute = Math.abs(cents);
        return (cents < 0 ? "-" : "") + (absolute / 100) + "."
                + (absolute % 100 < 10 ? "0" : "") + (absolute % 100);
    }

    private LinearLayout column(int l, int t, int r, int b) {
        LinearLayout x = row(); x.setOrientation(LinearLayout.VERTICAL); x.setPadding(dp(l), dp(t), dp(r), dp(b)); return x;
    }
    private LinearLayout row() { LinearLayout x = new LinearLayout(activity); x.setOrientation(LinearLayout.HORIZONTAL); x.setGravity(Gravity.CENTER_VERTICAL); return x; }
    private LinearLayout card() { LinearLayout x = new LinearLayout(activity); x.setOrientation(LinearLayout.VERTICAL); x.setBackground(roundDrawable(Color.WHITE, Color.WHITE, 20)); return x; }
    private TextView text(String value, float size, int color, boolean bold) { TextView t = new TextView(activity); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL)); return t; }
    private Button button(String value, int fill, int foreground) { Button b = new Button(activity); b.setText(value); b.setTextSize(15); b.setTextColor(foreground); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setStateListAnimator(null); b.setElevation(0); b.setBackground(roundDrawable(fill, fill, 12)); return b; }
    private Button outlineButton(String value) { return button(value, Color.rgb(239, 245, 255), BLUE); }
    private android.graphics.drawable.GradientDrawable roundDrawable(int fill, int stroke, int radius) { android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); d.setStroke(dp(1), stroke); return d; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b, float weight) { LinearLayout.LayoutParams p = lp(w, h, l, t, r, b); p.weight = weight; return p; }

    private static final class EmptyCallback implements Callback {
        public boolean canStartTask() { return true; }
        public String onAuthorized(TaskSpec spec, Authorization authorization, List<Uri> photos, Uri sourceTree) { return "preview-job"; }
        public void onReportRequested(String jobId) { }
        public void onExportRequested(String jobId) { }
        public void onAuditRequested(String auditJson) { }
        public void onClearTaskDataRequested(String jobId) { }
    }
}
