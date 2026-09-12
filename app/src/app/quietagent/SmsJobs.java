package app.quietagent;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** One-shot SMS analysis consent and app-private results; never mutates the SMS provider. */
public final class SmsJobs {
    private final File root;
    public SmsJobs(Context context){root=new File(context.getFilesDir(),"sms-jobs");}
    public File dir(String id)throws IOException{
        if(id==null||!id.matches("sms-[a-f0-9-]{36}"))throw new IOException("无效短信任务编号");
        return new File(root,id);
    }
    public String create(int days,String sender)throws Exception{
        String id="sms-"+UUID.randomUUID();File d=dir(id);if(!d.mkdirs())throw new IOException("无法保存短信授权");
        write(new File(d,"audit.json"),new JSONObject().put("noticeVersion","sms-readonly-v1").put("taskId",id)
            .put("scopeHash",SemanticFiles.hash(days+"\n"+sender)).put("readConfirmedAt",System.currentTimeMillis()).toString());
        state(id,"READING","正在本地读取短信");return id;
    }
    public synchronized void authorize(String id,String payload,JSONArray sources)throws Exception{
        File d=dir(id);
        if(new File(d,"consent.json").exists()||new File(d,".consumed").exists())throw new IOException("授权不能重复使用，请重新选择范围");
        write(new File(d,"payload.json"),payload);write(new File(d,"sources.json"),sources.toString());
        JSONObject consent=new JSONObject().put("payloadHash",SemanticFiles.hash(payload))
            .put("sourcesHash",SemanticFiles.hash(sources.toString())).put("authorizedAt",System.currentTimeMillis());
        write(new File(d,"consent.json"),consent.toString());
        event(id,"uploadConfirmedAt");state(id,"READY","已确认云端分析范围");
    }
    public synchronized String consume(String id)throws Exception{
        File d=dir(id);JSONObject consent=new JSONObject(read(new File(d,"consent.json")));
        if(System.currentTimeMillis()-consent.getLong("authorizedAt")>10*60*1000L)throw new IOException("授权已过期，请重新选择范围");
        String payload=read(new File(d,"payload.json"));
        if(!SemanticFiles.hash(payload).equals(consent.getString("payloadHash"))
            ||!SemanticFiles.hash(new File(d,"sources.json")).equals(consent.getString("sourcesHash")))throw new IOException("已确认范围发生变化");
        if(!new File(d,".consumed").createNewFile())throw new IOException("授权已使用，拒绝重复启动");
        JSONObject audit=new JSONObject(read(new File(d,"audit.json")));
        audit.put("payloadHash",consent.getString("payloadHash")).put("sourcesHash",consent.getString("sourcesHash"));
        write(new File(d,"audit.json"),audit.toString());
        state(id,"RUNNING","模型正在提取表格与筛选疑似垃圾短信");return payload;
    }
    public void finish(String id,JSONObject result)throws Exception{
        if(!"RUNNING".equals(status(id).optString("state")))throw new IOException("任务不在执行状态");
        File d=dir(id);
        result.put("sources",new JSONArray(read(new File(d,"sources.json"))));
        String csv=SmsData.csv(result);
        write(new File(d,"result.json"),result.toString());write(new File(d,"result.csv"),csv);
        JSONObject audit=new JSONObject(read(new File(d,"audit.json")));
        audit.put("csvHash",SemanticFiles.hash(new File(d,"result.csv"))).put("resultHash",SemanticFiles.hash(new File(d,"result.json")))
            .put("completedAt",System.currentTimeMillis());
        write(new File(d,"audit.json"),audit.toString());
        cleanInputs(d);write(new File(d,".complete"),audit.getString("csvHash"));state(id,"SUCCEEDED","完成，提取结果和垃圾短信建议均请核对");
    }
    public void fail(String id,String status,String message)throws Exception{
        File d=dir(id);if(new File(d,".complete").exists())return;
        cleanInputs(d);for(String name:new String[]{"result.json","result.csv","result.json.part","result.csv.part"})new File(d,name).delete();
        event(id,status.toLowerCase(java.util.Locale.ROOT)+"At");state(id,status,message);
    }
    public synchronized void event(String id,String key)throws Exception{
        File f=new File(dir(id),"audit.json");JSONObject audit=new JSONObject(read(f));audit.put(key,System.currentTimeMillis());write(f,audit.toString());
    }
    public void state(String id,String state,String message)throws Exception{write(new File(dir(id),"state.json"),new JSONObject().put("state",state).put("message",message).toString());}
    public JSONObject status(String id)throws Exception{return new JSONObject(read(new File(dir(id),"state.json")));}
    public File result(String id,String name)throws Exception{
        if(!"result.csv".equals(name)&&!"result.json".equals(name)&&!"audit.json".equals(name))throw new IOException("无效结果文件");
        File d=dir(id);if(!new File(d,".complete").isFile())throw new IOException("任务尚未完成");
        File f=new File(d,name);JSONObject audit=new JSONObject(read(new File(d,"audit.json")));
        if(!"audit.json".equals(name)&&!SemanticFiles.hash(f).equals(audit.getString("result.csv".equals(name)?"csvHash":"resultHash")))throw new IOException("结果校验失败");
        return f;
    }
    public void clear(String id)throws Exception{SemanticFiles.discard(dir(id));}
    public void recover(String id)throws Exception{
        String state=status(id).optString("state");
        if(!SmsAnalysisService.isActive(id)&&("READING".equals(state)||"PREVIEW".equals(state)||"READY".equals(state)||"RUNNING".equals(state)))
            fail(id,"INTERRUPTED","任务已中断，请重新读取并授权");
    }
    private static void cleanInputs(File dir){for(String name:new String[]{"payload.json","sources.json","payload.json.part","sources.json.part"})new File(dir,name).delete();}
    public static String read(File f)throws IOException{
        if(f.length()>512*1024)throw new IOException("文件超过读取限制");return new String(java.nio.file.Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);
    }
    private static void write(File file,String text)throws IOException{
        File part=new File(file.getPath()+".part");
        try(FileOutputStream out=new FileOutputStream(part)){out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!part.renameTo(file))throw new IOException("无法持久化短信任务");
    }
}
