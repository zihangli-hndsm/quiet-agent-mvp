package app.quietagent;

import android.content.Context;
import app.quietagent.core.Engine;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Private snapshots: exactly the content previewed is later classified and archived. */
public final class SemanticFiles {
    private static final Set<String> ACTIVE = Collections.synchronizedSet(new HashSet<>());
    public static final String PREFIX = "用途分类-v1:";
    public final File dir;
    public final JSONObject plan;
    public final String payload;
    private SemanticFiles(File dir, JSONObject plan, String payload) { this.dir=dir; this.plan=plan; this.payload=payload; }

    public static SemanticFiles prepare(Context c, String uri, String request) throws Exception {
        return prepare(c, Sources.create(c, uri), uri, request);
    }

    public static SemanticFiles prepare(Context c, Engine.Source source, String uri, String request) throws Exception {
        File dir = new File(c.getCacheDir(), "semantic-" + UUID.randomUUID());
        if (!dir.mkdirs()) throw new IOException("无法创建私有快照");
        ACTIVE.add(dir.getName());
        try {
            record(c, dir.getName(), "LOCAL_READ_AUTHORIZED", hash(uri));
            List<Engine.Entry> entries = source.list();
            if (entries.isEmpty() || entries.size()>30) throw new IOException("内容分类请选择含1至30个文件的小目录");
            JSONArray rows = new JSONArray(), sent = new JSONArray(); long total=0;
            for (int i=0; i<entries.size(); i++) {
                Engine.Entry e=entries.get(i); String id="f"+i; File snap=new File(dir,id);
                long size=0;
                try (InputStream in=source.open(e); OutputStream out=new FileOutputStream(snap)) {
                    byte[] buf=new byte[32768]; int n;
                    while ((n=in.read(buf))!=-1) {
                        size+=n; total+=n;
                        if(size>20L*1024*1024 || total>200L*1024*1024) throw new IOException("超过单文件20 MiB或总计200 MiB限制");
                        out.write(buf,0,n);
                    }
                }
                String text="", state="";
                try { text=OfficeText.redact(OfficeText.extract(snap,e.path)); }
                catch (IOException error) { state="损坏或无法解析"; }
                if(text.trim().isEmpty() && state.isEmpty()) state="无可读文本或格式尚不支持";
                if(text.length()>1200) text=text.substring(0,1200);
                rows.put(new JSONObject().put("id",id).put("name",e.path).put("sha256",hash(snap))
                    .put("state",state).put("category","待核对").put("reason",state));
                if(!text.trim().isEmpty()) sent.put(new JSONObject().put("id",id).put("excerpt",text));
            }
            String payload=new JSONObject().put("request",request.substring(0,Math.min(600,request.length()))).put("files",sent).toString(2);
            JSONObject plan=new JSONObject().put("scope",uri).put("files",rows).put("payloadHash",hash(payload))
                .put("localConsentAt",System.currentTimeMillis()).put("noticeVersion","semantic-preview-v1");
            return new SemanticFiles(dir,plan,payload);
        } catch(Exception e) { discard(dir); throw e; }
    }

    public void classify(Context context) throws Exception {
        record(context, dir.getName(), "SUMMARY_UPLOAD_AUTHORIZED", hash(payload));
        plan.put("uploadConfirmedAt",System.currentTimeMillis());
        JSONArray input=new JSONObject(payload).getJSONArray("files");
        JSONObject response=input.length()==0 ? new JSONObject().put("files",new JSONArray()) : LlmIntentRouter.classify(context,payload);
        JSONArray predictions=response.getJSONArray("files"); Map<String,JSONObject> byId=new HashMap<>();
        Set<String> expected=new HashSet<>();
        for(int i=0;i<input.length();i++) expected.add(input.getJSONObject(i).getString("id"));
        for(int i=0;i<predictions.length();i++) {
            JSONObject p=predictions.getJSONObject(i); String id=p.getString("id"), category=p.getString("category");
            if(!expected.contains(id)||byId.put(id,p)!=null || !category.matches("[\\p{IsHan}A-Za-z0-9]{1,16}")
                ||p.getString("reason").length()>80) throw new IOException("模型分类结果不合法，未生成资料包");
        }
        if(!byId.keySet().equals(expected)) throw new IOException("模型漏掉文件，未生成资料包");
        JSONArray rows=plan.getJSONArray("files");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i), p=byId.get(row.getString("id"));
            if(p!=null) { row.put("category",p.getString("category")); row.put("reason",p.getString("reason")); }
        }
        plan.put("responseHash",hash(response.toString()));
    }

    public String preview() throws JSONException {
        StringBuilder b=new StringBuilder(); JSONArray rows=plan.getJSONArray("files");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);
            b.append(row.getString("name")).append(" → ").append(row.getString("category"))
                .append('\n').append(row.getString("reason")).append("\n\n");
        }
        return b.toString();
    }

    public String seal() throws Exception {
        plan.put("classificationConfirmedAt",System.currentTimeMillis());
        String json=plan.toString();
        try(OutputStream out=new FileOutputStream(new File(dir,"plan.json"))) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
        return PREFIX+dir.getName()+":"+hash(json);
    }

    public static void adopt(Context context, String summary, File job) throws IOException {
        if(!summary.startsWith(PREFIX)) return;
        String[] parts=summary.split(":");
        if(parts.length!=3 || !parts[1].matches("semantic-[a-f0-9-]{36}") || !parts[2].matches("[a-f0-9]{64}")) throw new IOException("分类凭证无效");
        File staged=new File(context.getCacheDir(),parts[1]);
        if(!hash(new File(staged,"plan.json")).equals(parts[2])) throw new IOException("分类计划已改变");
        if(!staged.renameTo(new File(job,".semantic"))) throw new IOException("无法保存分类快照");
    }

    public static Engine.Source source(File job, String summary, String scope) throws IOException {
        try {
            File dir=new File(job,".semantic"), file=new File(dir,"plan.json");
            if(!hash(file).equals(summary.substring(summary.lastIndexOf(':')+1))) throw new IOException("分类计划校验失败");
            JSONObject plan=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            if(!scope.equals(plan.getString("scope"))) throw new IOException("分类范围已改变");
            List<Engine.Entry> entries=new ArrayList<>(); Map<String,String> hashes=new HashMap<>();
            JSONArray rows=plan.getJSONArray("files");
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i); String id=row.getString("id"), name=row.getString("name");
                if(!id.matches("f[0-9]+")) throw new IOException("分类编号无效");
                name=name.substring(name.lastIndexOf('/')+1);
                entries.add(new Engine.Entry(id,row.getString("category")+"/"+name,new File(dir,id).length(),0));
                hashes.put(id,row.getString("sha256"));
            }
            return new Engine.Source() {
                public String description(){ return "用户确认的用途分类（模型建议，需核对）"; }
                public List<Engine.Entry> list(){ return entries; }
                public InputStream open(Engine.Entry e) throws IOException {
                    if(!hashes.containsKey(e.id)) throw new IOException("文件不在已确认计划中");
                    File f=new File(dir,e.id);
                    if(!hash(f).equals(hashes.get(e.id))) throw new IOException("文件快照校验失败");
                    return new FileInputStream(f);
                }
            };
        } catch(JSONException e) { throw new IOException("分类计划无效",e); }
    }

    public static void saveDetails(File job) throws IOException {
        try {
            JSONObject plan=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(job,".semantic/plan.json").toPath()),StandardCharsets.UTF_8));
            JSONObject audit=new JSONObject();
            for(String key:new String[]{"localConsentAt","uploadConfirmedAt","classificationConfirmedAt","payloadHash","responseHash","noticeVersion"}) audit.put(key,plan.get(key));
            JSONArray hashes=new JSONArray(), rows=plan.getJSONArray("files");
            StringBuilder html=new StringBuilder("<h2>用途分类建议（待核对）</h2><ul>");
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                hashes.put(new JSONObject().put("id",row.getString("id")).put("sha256",row.getString("sha256")));
                html.append("<li>").append(escape(row.getString("name"))).append(" → ")
                    .append(escape(row.getString("category"))).append("：").append(escape(row.getString("reason"))).append("</li>");
            }
            html.append("</ul>"); audit.put("inputs",hashes);
            java.nio.file.Files.write(new File(job,"audit-snapshot").toPath(),audit.toString().getBytes(StandardCharsets.UTF_8));
            File report=new File(job,"summary.html");
            String old=new String(java.nio.file.Files.readAllBytes(report.toPath()),StandardCharsets.UTF_8);
            java.nio.file.Files.write(report.toPath(),old.replace("</body>",html+"</body>").getBytes(StandardCharsets.UTF_8));
        } catch(JSONException e) { throw new IOException("无法保存分类审计",e); }
    }
    private static String escape(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }

    public static String hash(String value) throws IOException { return digest(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))); }
    public static String hash(File file) throws IOException { return digest(new FileInputStream(file)); }
    private static String digest(InputStream input) throws IOException {
        try(InputStream in=input) {
            MessageDigest md=MessageDigest.getInstance("SHA-256"); byte[] buf=new byte[32768]; int n;
            while((n=in.read(buf))!=-1) md.update(buf,0,n);
            StringBuilder b=new StringBuilder(); for(byte v:md.digest()) b.append(String.format(Locale.ROOT,"%02x",v&255)); return b.toString();
        } catch(java.security.NoSuchAlgorithmException e){ throw new AssertionError(e); }
    }
    public static void discard(File dir) {
        ACTIVE.remove(dir.getName());
        File[] files=dir.listFiles(); if(files!=null) for(File f:files) { if(f.isDirectory()) discard(f); else f.delete(); } dir.delete();
    }
    public static void recoverStale(Context context) {
        File[] dirs=context.getCacheDir().listFiles();
        if(dirs!=null) for(File dir:dirs)
            if(dir.getName().matches("semantic-[a-f0-9-]{36}") && !ACTIVE.contains(dir.getName())) discard(dir);
    }
    private static synchronized void record(Context context, String id, String event, String hash) throws Exception {
        JSONObject row=new JSONObject().put("id",id).put("event",event).put("sha256",hash)
            .put("noticeVersion","semantic-preview-v1").put("time",System.currentTimeMillis());
        try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"content-analysis-audit.jsonl"),true)) {
            out.write((row.toString()+"\n").getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
    }
}
