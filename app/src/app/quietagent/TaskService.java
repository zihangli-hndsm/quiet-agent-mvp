package app.quietagent;
import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import app.quietagent.core.Engine;
import app.quietagent.core.Plan;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** All work runs here without starting Activities, IMEs, gestures or network. */
public final class TaskService extends Service {
 public static final String ACTION_RUN="app.quietagent.RUN",ACTION_CANCEL="app.quietagent.CANCEL";
 private static final AtomicBoolean ACTIVE=new AtomicBoolean(false);
 private final AtomicBoolean cancelled=new AtomicBoolean(false);
 private static final String CHANNEL="quiet-work";
 private static final int NOTIFICATION=41;
 private PowerManager.WakeLock wake;
 private Store store;
 private JSONObject status;
 private long lastPersist;
 private long lastNotification;
 private volatile boolean destroyedWhileRunning;
 public static boolean isRunning(){return ACTIVE.get();}
 public android.os.IBinder onBind(Intent intent){return null;}
 public void onCreate(){super.onCreate();store=new Store(this);NotificationChannel ch=new NotificationChannel(CHANNEL,"本地整理进度",NotificationManager.IMPORTANCE_LOW);ch.setSound(null,null);ch.enableVibration(false);getSystemService(NotificationManager.class).createNotificationChannel(ch);}
 private Notification notification(String text){
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  PendingIntent cancel=PendingIntent.getService(this,1,new Intent(this,TaskService.class).setAction(ACTION_CANCEL),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
  return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("静默整理 · 正在本地处理").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setDefaults(0).addAction(new Notification.Action.Builder(null,"取消",cancel).build()).build();
 }
 public int onStartCommand(Intent intent,int flags,int startId){
  if(intent==null){stopSelf();return START_NOT_STICKY;}
  if(ACTION_CANCEL.equals(intent.getAction())){cancelled.set(true);if(!ACTIVE.get())stopSelf();return START_NOT_STICKY;}
  if(!ACTION_RUN.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
  if(!ACTIVE.compareAndSet(false,true)){Log.i("QuietAgent","RUN_IGNORED already active");return START_NOT_STICKY;}
  cancelled.set(false);destroyedWhileRunning=false;String id="job-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8);
  startForeground(NOTIFICATION,notification("可继续使用其他应用；原文件保持不变"));
  wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"QuietAgent:work");wake.acquire(15*60*1000L);
  status=new JSONObject();put("id",id);put("state","RUNNING");put("phase","准备");put("message","原文件保持不变");persist();
  final String request=intent.getStringExtra("request"),source=intent.getStringExtra("source");
  final File dir=new File(store.jobsRoot(),id);
  new Thread(()->{
   android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
   Log.i("QuietAgent","START id="+id+" pid="+android.os.Process.myPid()+" uid="+android.os.Process.myUid());
   try{
    Plan plan=Plan.parse(request);Engine.Source access=Sources.create(this,source);
    Engine.Result result=Engine.run(access,plan,dir,(phase,done,total)->{
     put("phase",phase);put("done",done);put("total",total);
     if(SystemClock.elapsedRealtime()-lastPersist>350){persist();lastPersist=SystemClock.elapsedRealtime();}
     if(SystemClock.elapsedRealtime()-lastNotification>1500){getSystemService(NotificationManager.class).notify(NOTIFICATION,notification("本地处理 "+done+" / "+total+" · 原文件保持不变"));lastNotification=SystemClock.elapsedRealtime();}
    },()->cancelled.get()||Thread.currentThread().isInterrupted());
    if(cancelled.get())throw new java.io.InterruptedIOException("任务已取消，结果尚未发布");
    File pendingMarker=new File(dir,".complete.part");
    try(FileOutputStream marker=new FileOutputStream(pendingMarker)){marker.write(result.archiveSha256.getBytes(java.nio.charset.StandardCharsets.UTF_8));marker.getFD().sync();}
    if(cancelled.get())throw new java.io.InterruptedIOException("任务已取消，结果尚未发布");
    if(!pendingMarker.renameTo(new File(dir,".complete")))throw new java.io.IOException("无法发布完成标记");
    put("state","SUCCEEDED");put("phase","已核验");put("selected",result.selected);put("unique",result.unique);put("duplicates",result.duplicates);put("archiveSha256",result.archiveSha256);put("message","归档和清单已逐项回读校验。原文件保持不变。");
    Log.i("QuietAgent","SUCCESS id="+id+" selected="+result.selected+" unique="+result.unique);
   }catch(Exception error){
    put("state",destroyedWhileRunning?"INTERRUPTED":cancelled.get()?"CANCELLED":"FAILED");put("phase",cancelled.get()?"已停止":"未完成");put("message",destroyedWhileRunning?"任务被系统中断，原文件保持不变。":cancelled.get()?"已取消，原文件保持不变。":safe(error));
    cleanupUncommitted(dir);
    Log.e("QuietAgent","END_ERROR id="+id+" type="+error.getClass().getSimpleName());
   }finally{
    try{persist();}finally{new Handler(Looper.getMainLooper()).post(()->{ACTIVE.set(false);if(wake!=null&&wake.isHeld())wake.release();stopForeground(true);stopSelf();});}
   }
  },"quiet-file-worker").start();
  return START_NOT_STICKY;
 }
 private synchronized void put(String k,Object v){try{status.put(k,v);}catch(Exception ignored){}}
 private synchronized void persist(){put("updatedAt",System.currentTimeMillis());store.writeStatus(status);}
 private static String safe(Exception e){String s=e.getMessage();return s==null?"文件处理失败，请确认目录授权和可用空间。":s;}
 private void cleanupUncommitted(File dir){try{
  if(!dir.getCanonicalFile().getParentFile().equals(store.jobsRoot().getCanonicalFile())||new File(dir,".complete").exists())return;
  File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.isFile())f.delete();
 }catch(Exception ignored){}}
 public void onDestroy(){if(ACTIVE.get()){destroyedWhileRunning=true;cancelled.set(true);}super.onDestroy();}
}
