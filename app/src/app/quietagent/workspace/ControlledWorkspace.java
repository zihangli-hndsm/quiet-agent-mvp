package app.quietagent.workspace;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A deliberately small, capability based execution surface for the Office workspace.
 * Model text is data: it can request a tool, but it cannot choose a path or add a tool.
 */
public final class ControlledWorkspace {
    public static volatile Audit GLOBAL_AUDIT;
    /** Future adapters may implement this, but no arbitrary App adapter is bundled in this MVP. */
    public interface ExecutionEnvironment { String name(); boolean isAvailable(); }
    public static final Set<String> TOOLS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList("list_inputs", "read_document", "write_table", "finish")));
    public static final int MAX_CALLS = 80;
    public static final long MAX_RUNTIME_MS = 10 * 60 * 1000L;

    public static final class Input {
        public final String id, name, text, sha256;
        public Input(String id, String name, String text) {
            if (id == null || !id.matches("f[0-9]+")) throw new IllegalArgumentException("文件编号无效");
            if (name == null || name.trim().isEmpty() || name.contains("..") || name.startsWith("/"))
                throw new IllegalArgumentException("文件名超出任务范围");
            this.id = id; this.name = name; this.text = text == null ? "" : text;
            this.sha256 = hash(this.text);
        }
    }

    public static final class Spec {
        public final String taskId, description, authorization;
        public final Set<String> inputIds;
        public final Set<String> tools;
        public final long expiresAt;
        private boolean consumed;
        private final Map<String,String> inputHashes;
        private Spec(String id, String description, Set<String> ids, Map<String,String> hashes, long expiresAt) {
            this.taskId=id; this.description=description; this.inputIds=Collections.unmodifiableSet(ids);
            this.inputHashes=Collections.unmodifiableMap(hashes); this.tools=TOOLS; this.expiresAt=expiresAt; this.authorization=UUID.randomUUID().toString();
        }
    }

    public static Spec authorize(String description, List<Input> inputs, long now) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 4) throw new IllegalArgumentException("请选择1至4份文件");
        Set<String> ids = new LinkedHashSet<String>();
        for (Input i : inputs) if (!ids.add(i.id)) throw new IllegalArgumentException("文件编号重复");
        Map<String,String> hashes=new LinkedHashMap<String,String>(); for(Input i:inputs) hashes.put(i.id,i.sha256);
        return new Spec("workspace-" + UUID.randomUUID(), description == null ? "" : description,
                ids, hashes, now + MAX_RUNTIME_MS);
    }

    public interface Cancellation { boolean cancelled(); }
    public interface Audit { void event(String event, String detail) throws IOException; }
    public static final class Row {
        public final String inputId, category, reason, evidence;
        public Row(String inputId, String category, String reason, String evidence) {
            this.inputId=inputId; this.category=category; this.reason=reason; this.evidence=evidence;
        }
    }
    public static final class Result {
        public final File csv, html, audit; public final int calls;
        Result(File csv, File html, File audit, int calls) { this.csv=csv; this.html=html; this.audit=audit; this.calls=calls; }
    }

    private final Spec spec; private final Map<String,Input> inputs; private final Set<String> read = new HashSet<String>();
    private final List<String> events = new ArrayList<String>(); private final long started = System.currentTimeMillis();
    private int calls; private boolean finished; private File output; private List<Row> writtenRows;
    public ControlledWorkspace(Spec spec, List<Input> inputs, Audit audit) {
        if (spec == null || inputs == null) throw new IllegalArgumentException("任务授权不能为空");
        synchronized (spec) { if (spec.consumed) throw new IllegalArgumentException("授权已使用，不能重放"); if (System.currentTimeMillis()>spec.expiresAt) throw new IllegalArgumentException("授权已过期"); spec.consumed=true; }
        this.spec=spec; this.inputs=new LinkedHashMap<String,Input>();
        for (Input i:inputs) { if (!spec.inputIds.contains(i.id) || !spec.inputHashes.get(i.id).equals(i.sha256)) throw new IllegalArgumentException("文件不在本次任务范围或内容已改变"); this.inputs.put(i.id,i); }
        if (this.inputs.size()!=spec.inputIds.size()) throw new IllegalArgumentException("任务文件缺失");
        this.audit=audit;
    }
    private final Audit audit;

    public List<String> list_inputs() throws IOException { guard("list_inputs"); List<String> out=new ArrayList<String>(); for(Input i:inputs.values()) out.add(i.id+"|sha256="+i.sha256); log("list_inputs",out.toString()); return out; }
    public String read_document(String id) throws IOException { guard("read_document"); Input i=inputs.get(id); if(i==null) throw new IOException("文件不在本次任务范围"); read.add(id); log("read_document",id+" sha256="+i.sha256); return i.text; }
    public void write_table(List<Row> rows, File destination) throws IOException {
        guard("write_table"); if(rows==null || rows.isEmpty()) throw new IOException("表格不能为空"); if(destination==null || !destination.getName().equals("results.csv")) throw new IOException("输出文件名不被允许");
        if(output!=null) throw new IOException("write_table 只能执行一次");
        for(Row r:rows){ if(r==null || !inputs.containsKey(r.inputId) || !read.contains(r.inputId)) throw new IOException("表格证据必须来自已读取文件"); if(r.evidence==null || r.evidence.trim().isEmpty() || !inputs.get(r.inputId).text.contains(r.evidence)) throw new IOException("证据不在原文中："+r.inputId); }
        File parent=destination.getParentFile(); if(parent==null || (!parent.exists()&&!parent.mkdirs())) throw new IOException("无法创建结果目录");
        File part=new File(parent,"results.csv.part");
        try(BufferedWriter w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(part),StandardCharsets.UTF_8))){ w.write("input_id,file_name,category,reason,evidence,source_sha256\n"); for(Row r:rows){ Input i=inputs.get(r.inputId); w.write(csv(r.inputId)+","+csv(i.name)+","+csv(r.category)+","+csv(r.reason)+","+csv(r.evidence)+","+i.sha256+"\n"); } }
        if(!part.renameTo(destination)) throw new IOException("无法原子发布结果表");
        output=destination; writtenRows=new ArrayList<Row>(rows); log("write_table",destination.getName()+" rows="+rows.size());
    }
    public Result finish(File html, File auditFile) throws IOException { guard("finish"); if(output==null) throw new IOException("必须先写入待核对表格"); if(html==null || auditFile==null) throw new IOException("结果路径不能为空");
        File hp=new File(html.getParentFile(),html.getName()+".part"), ap=new File(auditFile.getParentFile(),auditFile.getName()+".part");
        try(BufferedWriter w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(hp),StandardCharsets.UTF_8))){ w.write("<!doctype html><meta charset=\"utf-8\"><title>待核对资料</title><h1>待核对资料</h1><p>本任务包含 "+writtenRows.size()+" 条待核对记录。</p><table><tr><th>来源</th><th>类别</th><th>理由</th><th>证据</th></tr>"); for(Row r:writtenRows){Input i=inputs.get(r.inputId);w.write("<tr><td>"+html(i.name)+"</td><td>"+html(r.category)+"</td><td>"+html(r.reason)+"</td><td>"+html(r.evidence)+"</td></tr>");} w.write("</table><p>CSV：results.csv</p>"); }
        try(BufferedWriter w=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(ap),StandardCharsets.UTF_8))){ w.write("taskId="+spec.taskId+"\ninputIds="+spec.inputIds+"\n"+events.toString()+"\n"); }
        if(!hp.renameTo(html)||!ap.renameTo(auditFile)) throw new IOException("无法原子发布审计结果");
        finished=true; log("finish","csv/html/audit ready"); return new Result(output,html,auditFile,calls);
    }
    private void guard(String tool) throws IOException { if(!spec.tools.contains(tool)) throw new IOException("工具不在白名单："+tool); if(finished) throw new IOException("任务已结束"); if(++calls>MAX_CALLS) throw new IOException("超过工具调用次数限制"); if(System.currentTimeMillis()>spec.expiresAt || System.currentTimeMillis()-started>MAX_RUNTIME_MS) throw new IOException("任务已超时"); }
    /** Single dispatch point for model requests. Unknown tools, fields, IDs and paths are rejected. */
    public Object dispatch(String tool, Map<String,String> args) throws IOException {
        if (tool == null || args == null) return reject("空工具请求");
        if ("list_inputs".equals(tool) && args.isEmpty()) return list_inputs();
        if ("read_document".equals(tool) && args.size()==1 && args.containsKey("id")) return read_document(args.get("id"));
        if ("write_table".equals(tool)) return reject("write_table 必须通过结构化 Row API，拒绝模型直接指定路径");
        if ("finish".equals(tool) && args.isEmpty()) return reject("finish 需要由宿主绑定固定输出路径");
        return reject("工具或参数不被允许");
    }
    private Object reject(String message) throws IOException { try { log("REJECTED",message); } catch (IOException ignored) {} throw new IOException(message); }
    public void checkCancelled(Cancellation c) throws IOException { if(c!=null&&c.cancelled()) throw new IOException("任务已取消，没有发布结果"); }
    private void log(String e,String d) throws IOException { events.add(e+":"+d); Audit sink=audit!=null?audit:GLOBAL_AUDIT; if(sink!=null)sink.event(e,d); }
    private static String csv(String s){ if(s==null)return "\"\""; String v=s.replace("\r"," ").replace("\n"," "); if(v.startsWith("=")||v.startsWith("+")||v.startsWith("-")||v.startsWith("@"))v="'"+v; return "\""+v.replace("\"","\"\"")+"\""; }
    private static String html(String s){if(s==null)return "";return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String hash(String s){ try{ MessageDigest m=MessageDigest.getInstance("SHA-256"); byte[] b=m.digest(s.getBytes(StandardCharsets.UTF_8)); StringBuilder o=new StringBuilder(); for(byte x:b)o.append(String.format("%02x",x&255)); return o.toString(); }catch(Exception e){throw new AssertionError(e);} }
}
