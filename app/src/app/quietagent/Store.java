package app.quietagent;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** App-owned state only. Atomic replacement prevents torn progress records. */
public final class Store {
 private final Context context;
 private final SharedPreferences prefs;
 private static final Object LOCK=new Object();
 public Store(Context context){this.context=context.getApplicationContext();prefs=this.context.getSharedPreferences("settings",0);}
 public String getSourceUri(){return prefs.getString("source","");}
 public void setSourceUri(String uri){prefs.edit().putString("source",uri).apply();}
 public String getRequest(){return prefs.getString("request","把这个目录里的文件去重，按类型归档");}
 public void setRequest(String request){prefs.edit().putString("request",request).apply();}
 public File jobsRoot(){File f=new File(context.getFilesDir(),"jobs");f.mkdirs();return f;}
 public File receiptJobsRoot(){File f=new File(context.getFilesDir(),"receipt-jobs");f.mkdirs();return f;}
 public File jobDir(String id){
  if(id==null)throw new IllegalArgumentException("无效任务编号");
  if(id.matches("job-[0-9]+-[a-f0-9]{8}"))return new File(jobsRoot(),id);
  if(id.matches("receipt-[0-9]+-[a-f0-9]{8}"))return new File(receiptJobsRoot(),id);
  throw new IllegalArgumentException("无效任务编号");
 }
 public File getJobFile(String id,String name){
  if(!name.equals("archive.zip")&&!name.equals("manifest.json")&&!name.equals("summary.html")&&!name.equals("receipts.csv")&&!name.equals("audit-snapshot")&&!name.equals("credential"))throw new IllegalArgumentException("无效结果文件");
  return new File(jobDir(id),name);
 }
 private AtomicFile statusFile(){return new AtomicFile(new File(context.getFilesDir(),"status.json"));}
 public String readStatus(){synchronized(LOCK){
  try{
   JSONObject status=new JSONObject(new String(statusFile().readFully(),StandardCharsets.UTF_8));
   if(status.optString("state").equals("RUNNING")&&!TaskService.isRunning()){
    status.put("state","INTERRUPTED").put("message","上次任务被系统中断。原文件未改动，请重新开始。").put("updatedAt",System.currentTimeMillis());
    writeStatus(status);
   }
   return status.toString();
  }catch(Exception e){
   if(!statusFile().getBaseFile().exists())return "{\"state\":\"IDLE\"}";
   return "{\"state\":\"INTERRUPTED\",\"message\":\"无法读取上次任务状态；不能确认完成。原文件未改动，可重新开始。\"}";
  }
 }}
 public void writeStatus(JSONObject value){synchronized(LOCK){
  AtomicFile file=statusFile();FileOutputStream out=null;
  try{out=file.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
  catch(IOException e){if(out!=null)file.failWrite(out);throw new IllegalStateException("无法保存任务状态",e);}
 }}
}
