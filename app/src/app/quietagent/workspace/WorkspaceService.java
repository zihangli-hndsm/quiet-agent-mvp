package app.quietagent.workspace;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import app.quietagent.LlmIntentRouter;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground host for one durable, explicitly authorized workspace job. */
public final class WorkspaceService extends Service {
    public static final String RUN="app.quietagent.workspace.RUN", CANCEL="app.quietagent.workspace.CANCEL", EXTRA_JOB_ID="jobId";
    private static final String CHANNEL="quiet-workspace";
    private static final int NOTIFICATION=77;
    private static final Object LOCK=new Object();
    private static String activeId;
    private static AtomicBoolean stop;
    private volatile long deadline;

    public static WorkspaceJobs.Prepared prepare(Context c,List<Uri> u,String r)throws Exception{return WorkspaceJobs.prepare(c,u,r);}
    public static void start(Context c,String id){Intent i=new Intent(c,WorkspaceService.class).setAction(RUN).putExtra(EXTRA_JOB_ID,id);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
    public static void cancel(Context c,String id){c.startService(new Intent(c,WorkspaceService.class).setAction(CANCEL).putExtra(EXTRA_JOB_ID,id));}
    public static JSONObject status(Context c,String id)throws IOException{return WorkspaceJobs.load(c,id);}
    public static String readResults(Context c,String id,String f)throws IOException{return WorkspaceJobs.readResults(c,id,f);}
    public static File exportAudit(Context c,String id)throws IOException{return new File(WorkspaceJobs.dir(c,id),"audit.jsonl");}
    public static void clear(Context c,String id)throws IOException{JSONObject s=status(c,id);if("RUNNING".equals(s.optString("state")))throw new IOException("运行中的任务不能清除");WorkspaceJobs.delete(WorkspaceJobs.dir(c,id));}

    @Override public void onCreate(){super.onCreate();NotificationChannel ch=new NotificationChannel(CHANNEL,"受控工作区进度",NotificationManager.IMPORTANCE_LOW);ch.setSound(null,null);ch.enableVibration(false);getSystemService(NotificationManager.class).createNotificationChannel(ch);try{WorkspaceJobs.recoverInterrupted(this,null);}catch(Exception ignored){}}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent in,int flags,int sid){String id=in==null?null:in.getStringExtra(EXTRA_JOB_ID);if(in!=null&&CANCEL.equals(in.getAction())){requestCancel(id);return START_NOT_STICKY;}if(in==null||!RUN.equals(in.getAction())||id==null){stopSelf();return START_NOT_STICKY;}synchronized(LOCK){if(activeId!=null){recordEvent(id,"rejected","已有任务运行");return START_NOT_STICKY;}activeId=id;stop=new AtomicBoolean(false);deadline=System.currentTimeMillis()+ControlledWorkspace.MAX_RUNTIME_MS;}startForeground(NOTIFICATION,notification("后台处理，可继续使用其他应用"));new Thread(()->run(id),"workspace-service").start();return START_NOT_STICKY;}
    private Notification notification(String text){return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("受控工作区").setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setDefaults(0).build();}
    private void requestCancel(String id){boolean active; synchronized(LOCK){active=activeId!=null&&activeId.equals(id);if(active&&stop!=null)stop.set(true);}if(active)record(id,"cancel_requested","用户请求取消");else recordEvent(id,"cancel_rejected","任务未运行");}
    private boolean cancelled(){synchronized(LOCK){return stop!=null&&stop.get();}}
    private void checkCancel()throws IOException{if(cancelled())throw new IOException("任务已取消，没有发布结果");if(deadline>0&&System.currentTimeMillis()>deadline)throw new IOException("任务已超时，没有发布结果");}
    private void checkDeadline(long ignored)throws IOException{if(deadline>0&&System.currentTimeMillis()>deadline)throw new IOException("任务已超时，没有发布结果");}
    private void run(String id){File d=null;boolean started=false;try{d=WorkspaceJobs.dir(this,id);JSONObject s=WorkspaceJobs.consumeAuthorization(this,id);started=true;long deadline=s.getLong("authorizedAt")+600000L;WorkspaceJobs.phase(this,id,"读取已授权输入");JSONArray fs=s.getJSONArray("files"),pf=new JSONObject(s.getString("payload")).getJSONArray("files");List<ControlledWorkspace.Input> inputs=new ArrayList<>();for(int i=0;i<fs.length();i++){checkCancel();checkDeadline(deadline);JSONObject f=fs.getJSONObject(i),p=pf.getJSONObject(i);File raw=new File(d,"f"+i),textFile=new File(d,"text"+i);if(!WorkspaceJobs.hash(raw).equals(f.getString("sha256"))||!WorkspaceJobs.hash(textFile).equals(f.getString("textSha256")))throw new IOException("输入快照校验失败");String text=WorkspaceJobs.read(textFile);if(!text.equals(p.optString("excerpt"))||!WorkspaceJobs.hash(text).equals(f.getString("textSha256")))throw new IOException("预览内容校验失败");inputs.add(new ControlledWorkspace.Input(f.getString("id"),f.getString("name"),text));}
      final File auditDir=d;ControlledWorkspace.Audit audit=(e,x)->record(auditDir,e,x);ControlledWorkspace.Spec spec=ControlledWorkspace.authorize(s.optString("request"),inputs,System.currentTimeMillis());ControlledWorkspace w=new ControlledWorkspace(spec,inputs,audit);StringBuilder transcript=new StringBuilder(s.getString("payload"));List<ControlledWorkspace.Row> rows=null;boolean done=false;
      for(int n=0;n<ControlledWorkspace.MAX_CALLS&&!done;n++){checkCancel();WorkspaceJobs.phase(this,id,"模型工具调用 "+(n+1));JSONObject q=LlmIntentRouter.workspaceTurn(this,transcript.toString());checkCancel();transcript.append("\nmodel_request=").append(q.toString());String tool=q.optString("tool");if("list_inputs".equals(tool)){transcript.append("\nlist_inputs=").append(w.dispatch(tool,Collections.<String,String>emptyMap()));continue;}if("read_document".equals(tool)){JSONObject a=q.optJSONObject("args");if(a==null||a.length()!=1||!a.has("id"))throw new IOException("read_document 参数无效");Map<String,String>args=new HashMap<>();args.put("id",a.getString("id"));transcript.append("\nread_document="+a.getString("id")+":").append(w.dispatch(tool,args));continue;}if("write_table".equals(tool)){JSONArray a=q.optJSONArray("rows");if(a==null)throw new IOException("write_table rows 缺失");rows=parseRows(a);w.write_table(rows,new File(d,"results.csv"));transcript.append("\nwrite_table=validated");continue;}if("finish".equals(tool)){if(rows==null)throw new IOException("finish 前未写表");transcript.append("\nfinish=accepted");done=true;continue;}throw new IOException("模型请求了不允许的工具："+tool);}
      if(rows==null||!done)throw new IOException("模型未完成受控工具循环");synchronized(LOCK){checkCancel();w.finish(new File(d,"summary.html"),new File(d,"audit.log"));checkCancel();record(d,"finish","results.csv/html ready");WorkspaceJobs.commitOutputs(this,id);}
    }catch(Exception e){try{if(d!=null){if(started){WorkspaceJobs.fail(this,id,cancelled()?"CANCELLED":"FAILED",e.getMessage());record(d,"failure",e.getMessage());}else recordEvent(id,"failure_rejected",e.getMessage());}}catch(Exception ignored){}}finally{synchronized(LOCK){if(id.equals(activeId)){activeId=null;stop=null;deadline=0;}}postCompletion(id);stopSelf();}}
    private List<ControlledWorkspace.Row> parseRows(JSONArray a)throws Exception{if(a.length()<1||a.length()>4)throw new IOException("rows 数量无效");List<ControlledWorkspace.Row> out=new ArrayList<>();Set<String> ids=new HashSet<>();for(int i=0;i<a.length();i++){JSONObject r=a.getJSONObject(i);if(r.length()!=4||!ids.add(r.getString("id")))throw new IOException("rows schema 无效");String cat=r.getString("category"),reason=r.getString("reason"),ev=r.getString("evidence");if(cat.length()>16||reason.length()>80||ev.length()>200||ev.trim().isEmpty())throw new IOException("rows 字段超限");out.add(new ControlledWorkspace.Row(r.getString("id"),cat,reason,ev));}return out;}
    private void record(String id,String e,String x){try{record(WorkspaceJobs.dir(this,id),e,x);}catch(Exception ignored){}}
    private void recordEvent(String id,String e,String x){try{recordFile(WorkspaceJobs.dir(this,id),"events.jsonl",e,x);}catch(Exception ignored){}}
    private static synchronized void record(File d,String e,String x)throws IOException{try{JSONObject o=new JSONObject().put("time",System.currentTimeMillis()).put("event",e).put("detail",WorkspaceJobs.clean(x,400));try(FileOutputStream out=new FileOutputStream(new File(d,"audit.jsonl"),true)){out.write((o.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}catch(JSONException z){throw new IOException(z);}}
    private static synchronized void recordFile(File d,String file,String e,String x)throws IOException{try{JSONObject o=new JSONObject().put("time",System.currentTimeMillis()).put("event",e).put("detail",WorkspaceJobs.clean(x,400));try(FileOutputStream out=new FileOutputStream(new File(d,file),true)){out.write((o.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}catch(JSONException z){throw new IOException(z);}}
    private void postCompletion(String id){try{if(!"COMPLETED".equals(WorkspaceJobs.load(this,id).optString("state"))){stopForeground(true);return;}Intent open=new Intent(this,WorkspaceActivity.class).putExtra(EXTRA_JOB_ID,id);PendingIntent pi=PendingIntent.getActivity(this,78,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);Notification n=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_save).setContentTitle("受控工作区已完成").setContentText("结果可回到工作区核对").setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(true).setDefaults(0).build();getSystemService(NotificationManager.class).notify(78,n);}catch(Exception ignored){stopForeground(true);}}
}
