package app.quietagent;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.util.Log;

import app.quietagent.core.Engine;
import app.quietagent.core.Plan;
import app.quietagent.security.AtomicFileStateStore;
import app.quietagent.security.AuthorizationManager;
import app.quietagent.security.TaskSpec;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes a private, previously authorized job without opening UI or touching input focus. */
public final class TaskService extends Service {
    public static final String ACTION_RUN = "app.quietagent.RUN";
    public static final String ACTION_CANCEL = "app.quietagent.CANCEL";
    private static final String CHANNEL = "quiet-work";
    private static final int NOTIFICATION = 41;
    private static final AtomicBoolean ACTIVE = new AtomicBoolean(false);

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean destroyedWhileRunning;
    private volatile String activeJobId;
    private volatile ReceiptJobStore.Pending activePending;
    private PowerManager.WakeLock wake;
    private Store statusStore;
    private ReceiptJobStore jobs;
    private AuthorizationManager authorizer;
    private JSONObject status;
    private long lastPersist;
    private long lastNotification;

    public static boolean isRunning() { return ACTIVE.get(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        statusStore = new Store(this);
        jobs = new ReceiptJobStore(this);
        authorizer = new AuthorizationManager(new AtomicFileStateStore(
                new File(getFilesDir(), "security/authorization.state")));
        NotificationChannel channel = new NotificationChannel(CHANNEL, "本地整理进度", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, TaskService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("静默整理 · 正在本地处理")
                .setContentText(text).setContentIntent(open).setOngoing(true)
                .setOnlyAlertOnce(true).setDefaults(0)
                .addAction(new Notification.Action.Builder(null, "取消", stop).build()).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled.set(true);
            recordCancelRequest();
            if (!ACTIVE.get()) stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_RUN.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        final String jobId = intent.getStringExtra("jobId");
        if (jobId == null || !jobId.matches("receipt-[0-9]+-[a-f0-9]{8}")) {
            Log.w("QuietAgent", "RUN_REJECTED invalid job id"); stopSelf(); return START_NOT_STICKY;
        }
        if (!ACTIVE.compareAndSet(false, true)) {
            Log.i("QuietAgent", "RUN_IGNORED already active"); return START_NOT_STICKY;
        }
        cancelled.set(false);
        destroyedWhileRunning = false;
        activeJobId = jobId;
        try {
            activePending = jobs.readPendingForAuthorization(jobId);
        } catch (Exception error) {
            ACTIVE.set(false); activeJobId = null; stopSelf(); return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION, notification("可继续使用其他应用；不会占用屏幕或键盘"));
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuietAgent:work");
        wake.acquire(15 * 60 * 1000L);
        status = new JSONObject();
        put("id", jobId); put("taskType", activePending.spec.type().name()); put("state", "RUNNING");
        put("phase", "准备"); put("message", "任务在本机后台运行，可继续使用其他应用"); persist();
        new Thread(new Runnable() { @Override public void run() { execute(jobId); } }, "quiet-authorized-worker").start();
        return START_NOT_STICKY;
    }

    private void execute(String jobId) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        try {
            ReceiptJobStore.Pending pending = activePending;
            if (pending == null) throw new IOException("任务规格不可用");
            if (pending.spec.type() == TaskSpec.TaskType.OCR) runReceipt(jobId);
            else runArchive(jobId, pending);
            put("state", "SUCCEEDED"); put("phase", "已核验");
            put("message", "结果已回读校验；自动识别金额仍需用户核对");
            Log.i("QuietAgent", "SUCCESS id=" + jobId);
        } catch (Exception error) {
            String end = destroyedWhileRunning ? AuthorizationManager.INTERRUPTED
                    : cancelled.get() ? AuthorizationManager.CANCELLED : AuthorizationManager.FAILED;
            if (hasConsumedMarker(jobId)) recordTerminal(end);
            put("state", destroyedWhileRunning ? "INTERRUPTED" : cancelled.get() ? "CANCELLED" : "FAILED");
            put("phase", "未完成");
            put("message", destroyedWhileRunning ? "任务被系统中断，需重新授权后再执行"
                    : cancelled.get() ? "任务已取消，未发布完成包" : safe(error));
            try { jobs.clearRawUri(jobId); } catch (Exception ignored) { }
            try { jobs.cleanupUnpublished(jobId); } catch (Exception ignored) { }
            Log.e("QuietAgent", "END_ERROR id=" + jobId + " type=" + error.getClass().getSimpleName());
        } finally {
            try { persist(); } finally {
                new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
                    ACTIVE.set(false); activeJobId = null; activePending = null;
                    if (wake != null && wake.isHeld()) wake.release();
                    stopForeground(true); stopSelf();
                }});
            }
        }
    }

    private void runReceipt(String jobId) throws IOException {
        ReceiptTaskRunner runner = new ReceiptTaskRunner(getContentResolver(), jobs);
        ReceiptTaskRunner.Result result = runner.run(jobId, authorizer, ReceiptOcr.mlKitChinese(),
                new ReceiptTaskRunner.Listener() {
                    @Override public void update(String phase, int done, int total) { progress(phase, done, total); }
                }, new ReceiptTaskRunner.Cancellation() {
                    @Override public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
                });
        if (cancelled.get()) throw new java.io.InterruptedIOException("任务已取消");
        jobs.recordOutputAndPublishCredential(jobId, result.archiveSha256, authorizer);
        jobs.publishComplete(jobId, result.archiveSha256);
        put("selected", result.selected); put("unique", result.included); put("duplicates", result.duplicates);
        put("recognizedTotalCents", result.recognizedTotalCents == null ? JSONObject.NULL : result.recognizedTotalCents);
        put("archiveSha256", result.archiveSha256);
    }

    private void runArchive(String jobId, ReceiptJobStore.Pending initial) throws IOException {
        jobs.consume(jobId, authorizer);
        ReceiptJobStore.Pending pending = jobs.readForService(jobId);
        Engine.Source source = Sources.create(this, pending.sourceUris.get(0));
        Engine.Result result = Engine.run(source,
                Plan.parse("把这个目录里的文件去重，按类型归档"), jobs.jobDir(jobId),
                new Engine.Listener() {
                    @Override public void update(String phase, int done, int total) { progress(phase, done, total); }
                }, new Engine.Cancellation() {
                    @Override public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
                });
        jobs.clearRawUri(jobId);
        if (cancelled.get()) throw new java.io.InterruptedIOException("任务已取消");
        jobs.recordOutputAndPublishCredential(jobId, result.archiveSha256, authorizer);
        jobs.publishComplete(jobId, result.archiveSha256);
        put("selected", result.selected); put("unique", result.unique); put("duplicates", result.duplicates);
        put("archiveSha256", result.archiveSha256);
    }

    private boolean hasConsumedMarker(String jobId) {
        try { return new File(jobs.jobDir(jobId), ".consumed").isFile(); } catch (Exception ignored) { return false; }
    }

    private void recordCancelRequest() {
        ReceiptJobStore.Pending pending = activePending;
        String id = activeJobId;
        if (pending == null || id == null || !hasConsumedMarker(id)) return;
        try { authorizer.recordEvent(id, pending.spec, pending.nonce, AuthorizationManager.CANCEL_REQUESTED); }
        catch (Exception ignored) { }
    }

    private void recordTerminal(String event) {
        ReceiptJobStore.Pending pending = activePending;
        String id = activeJobId;
        if (pending == null || id == null) return;
        try {
            if (AuthorizationManager.CANCELLED.equals(event)) recordCancelRequest();
            authorizer.recordEvent(id, pending.spec, pending.nonce, event);
        } catch (Exception ignored) { }
    }

    private void progress(String phase, int done, int total) {
        put("phase", phase); put("done", done); put("total", total);
        long now = SystemClock.elapsedRealtime();
        if (now - lastPersist > 350) { persist(); lastPersist = now; }
        if (now - lastNotification > 1500) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION,
                    notification("本地处理 " + done + " / " + total + " · 屏幕和键盘仍归用户"));
            lastNotification = now;
        }
    }

    private synchronized void put(String key, Object value) { try { status.put(key, value); } catch (Exception ignored) { } }
    private synchronized void persist() { put("updatedAt", System.currentTimeMillis()); statusStore.writeStatus(status); }
    private static String safe(Exception error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.trim().isEmpty() ? "本地处理失败，未发布完成包" : value;
    }

    @Override public void onDestroy() {
        if (ACTIVE.get()) { destroyedWhileRunning = true; cancelled.set(true); }
        super.onDestroy();
    }
}
