package app.quietagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Quiet notification entry; never opens an activity without a user's tap. */
public final class EntryNotification {
    private static final String CHANNEL = "quiet-entry";
    private static final int ID = 42;
    private EntryNotification() {}
    public static void show(Context context, boolean completed, String jobId) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "助手入口", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        Intent entry = new Intent(context, app.quietagent.workspace.WorkspaceActivity.class).putExtra("jobId",jobId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(context, 42, entry, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("Quiet Agent")
                .setContentText(completed ? "整理完成 · 点击查看结果" : "有什么需要帮忙？点击描述任务")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "新任务", open).build());
        if (completed && jobId != null) {
            Intent report = new Intent(context, ReportActivity.class).putExtra("jobId", jobId);
            PendingIntent result = PendingIntent.getActivity(context, 43, report, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentIntent(result).addAction(new Notification.Action.Builder(null, "查看结果", result).build());
        }
        manager.notify(ID, builder.build());
    }
    public static void hide(Context context) { context.getSystemService(NotificationManager.class).cancel(ID); }
}
