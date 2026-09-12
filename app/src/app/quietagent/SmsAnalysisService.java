package app.quietagent;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicReference;

/** Foreground, silent analysis. No SMS write/delete/send operations. */
public final class SmsAnalysisService extends Service {
    private static final AtomicReference<String> ACTIVE=new AtomicReference<>();
    private volatile boolean cancelled;
    private String job;
    private SmsJobs jobs;
    public static boolean isActive(String id){return id!=null&&id.equals(ACTIVE.get());}
    public IBinder onBind(Intent i){return null;}
    public void onCreate(){super.onCreate();jobs=new SmsJobs(this);
        NotificationChannel ch=new NotificationChannel("sms-analysis","短信整理",NotificationManager.IMPORTANCE_LOW);ch.setSound(null,null);ch.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
    private Notification notice(String text,boolean ongoing){
        PendingIntent open=PendingIntent.getActivity(this,71,new Intent(this,SmsActivity.class).putExtra("jobId",job),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n=new Notification.Builder(this,"sms-analysis").setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Quiet Agent · 短信整理")
            .setContentText(text).setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true);
        if(ongoing)n.addAction(new Notification.Action.Builder(null,"取消",PendingIntent.getService(this,72,new Intent(this,SmsAnalysisService.class)
            .setAction("CANCEL").putExtra("jobId",job),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build());
        return n.build();
    }
    public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        String id=intent.getStringExtra("jobId");
        if("CANCEL".equals(intent.getAction())){
            synchronized(this){
                if(isActive(id)){try{if(!"SUCCEEDED".equals(jobs.status(id).optString("state"))){cancelled=true;jobs.event(id,"cancelRequestedAt");}}catch(Exception ignored){cancelled=true;}}
            }
            if(ACTIVE.get()==null)stopSelf();return START_NOT_STICKY;
        }
        if(!"RUN".equals(intent.getAction())||id==null||!id.matches("sms-[a-f0-9-]{36}")){stopSelf();return START_NOT_STICKY;}
        if(!ACTIVE.compareAndSet(null,id))return START_NOT_STICKY;
        job=id;cancelled=false;startForeground(71,notice("正在分析已授权的短信，可继续使用其他应用",true));
        new Thread(()->{
            boolean complete=false;
            try{
                String payload=jobs.consume(id);
                if(cancelled)throw new java.io.InterruptedIOException();
                JSONObject result=SmsData.validate(LlmIntentRouter.structured(this,payload,SmsData.INSTRUCTION),payload);
                synchronized(SmsAnalysisService.this){
                    if(cancelled)throw new java.io.InterruptedIOException();
                    jobs.finish(id,result);complete=true;
                }
            }catch(Exception e){try{jobs.fail(id,cancelled?"CANCELLED":"FAILED",cancelled?"已取消，没有发布结果":"处理失败，请重新授权重试");}catch(Exception ignored){}}
            finally{
                ACTIVE.compareAndSet(id,null);stopForeground(true);
                getSystemService(NotificationManager.class).notify(71,notice(complete?"整理完成，点击查看待核对结果":"任务未完成，点击查看",false));stopSelf();
            }
        },"quiet-sms-worker").start();return START_NOT_STICKY;
    }
    public void onDestroy(){cancelled=true;super.onDestroy();}
}
