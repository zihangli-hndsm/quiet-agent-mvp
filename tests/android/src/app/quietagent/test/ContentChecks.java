package app.quietagent.test;

import android.content.Context;
import android.content.Intent;
import app.quietagent.*;
import app.quietagent.core.Engine;
import app.quietagent.security.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import android.app.Activity;
import android.app.Instrumentation;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import app.quietagent.ui.VcFlowView;

/** Synthetic documents only. Exercises the production snapshot/classify/authorize/service path. */
public final class ContentChecks {
    public static void run(Instrumentation runner, JSONObject result) throws Exception {
        Context c=runner.getTargetContext();
        checkUi(runner, result);
        File root=new File(c.getCacheDir(),"content-check-"+UUID.randomUUID()); root.mkdirs();
        try {
            String[] names={"resume.docx","skills.xlsx","portfolio.pptx","broken.docx"};
            String[] members={"word/document.xml","xl/sharedStrings.xml","ppt/slides/slide1.xml"};
            String[] texts={"虚构样例 软件开发简历 Java Android 后端开发 example@example.com 13800138000",
                "虚构样例 产品经理 需求分析 用户研究 数据分析", "虚构样例 平面设计师 作品集 品牌设计"};
            List<Engine.Entry> entries=new ArrayList<>();
            for(int i=0;i<3;i++) {
                File f=new File(root,names[i]);
                try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(f))) {
                    out.putNextEntry(new ZipEntry(members[i]));
                    out.write(("<root><t>"+texts[i]+"</t></root>").getBytes(StandardCharsets.UTF_8)); out.closeEntry();
                }
                if(!OfficeText.extract(f,names[i]).contains("虚构样例")) throw new AssertionError("Office extraction");
                entries.add(new Engine.Entry(names[i],names[i],f.length(),0));
            }
            File broken=new File(root,names[3]);
            try(OutputStream out=new FileOutputStream(broken)){out.write(new byte[]{1,2,3});}
            entries.add(new Engine.Entry(names[3],names[3],3,0));
            Engine.Source source=new Engine.Source(){
                public String description(){return "虚构验收样例";}
                public List<Engine.Entry> list(){return entries;}
                public InputStream open(Engine.Entry e)throws IOException{return new FileInputStream(new File(root,e.id));}
            };
            String scope="content://com.android.externalstorage.documents/tree/primary%3AQuietSynthetic";
            SemanticFiles prepared=SemanticFiles.prepare(c,source,scope,"按用途分类我这些简历（全部虚构样例）");
            try {
                if(prepared.payload.contains("example@example.com") || prepared.payload.contains("13800138000") || prepared.payload.contains("resume.docx"))
                    throw new AssertionError("payload privacy");
                result.put("officeFormats",3).put("redaction",true);
                if(LlmIntentRouter.understand(c,"已选择本地文件目录。按用途分类我这些简历").route!=LlmIntentRouter.Route.ORIGINAL)
                    throw new AssertionError("resume route");
                prepared.classify(c);
                JSONArray rows=prepared.plan.getJSONArray("files");
                String[] meanings={".*(技术|软件|开发|工程).*", ".*(产品|需求).*", ".*(设计|创意|视觉).*"};
                JSONArray categories=new JSONArray();
                for(int i=0;i<3;i++) {
                    String category=rows.getJSONObject(i).getString("category"); categories.put(category);
                    if(!category.matches(meanings[i]))throw new AssertionError("synthetic category mismatch: "+category);
                }
                result.put("syntheticCategories",categories);
                if(!"待核对".equals(rows.getJSONObject(3).getString("category"))) throw new AssertionError("corrupt fallback");
                String summary=prepared.seal();
                TaskSpec spec=TaskSpec.archive(scope,summary);
                AuthorizationManager auth=new AuthorizationManager(new AtomicFileStateStore(new File(c.getFilesDir(),"security/authorization.state")));
                Authorization grant=auth.authorize(spec);
                ReceiptJobStore jobs=new ReceiptJobStore(c);
                String job=jobs.createPending(spec,grant.nonce(),Collections.singletonList(scope));
                c.startForegroundService(new Intent(c,TaskService.class).setAction(TaskService.ACTION_RUN).putExtra("jobId",job));
                long until=android.os.SystemClock.elapsedRealtime()+60000;
                JSONObject status=new JSONObject();
                while(android.os.SystemClock.elapsedRealtime()<until){
                    status=new JSONObject(new Store(c).readStatus());
                    if(job.equals(status.optString("id")) && !"RUNNING".equals(status.optString("state")))break;
                    Thread.sleep(200);
                }
                if(!"SUCCEEDED".equals(status.optString("state")))throw new AssertionError("service "+status.optString("message"));
                File archive=jobs.resultFile(job,"archive.zip"); int count=0;
                try(ZipInputStream in=new ZipInputStream(new FileInputStream(archive))){
                    ZipEntry e; while((e=in.getNextEntry())!=null){if(!e.getName().contains("/"))throw new AssertionError("category path");count++;}
                }
                if(count!=4 || new File(jobs.jobDir(job),".semantic").exists())throw new AssertionError("outputs or cleanup");
                if(!SemanticFiles.hash(archive).equals(status.getString("archiveSha256")))throw new AssertionError("archive hash");
                result.put("route",true).put("cloudClassified",3).put("reviewRequired",1).put("archiveEntries",count)
                    .put("service",status.getString("state")).put("snapshotCleaned",true).put("jobId",job);
            } finally { SemanticFiles.discard(prepared.dir); }
        } finally { SemanticFiles.discard(root); }
    }

    private static void checkUi(Instrumentation runner, JSONObject result) throws Exception {
        Context c=runner.getTargetContext(); android.net.Uri selected=null;
        for(android.content.UriPermission p:c.getContentResolver().getPersistedUriPermissions())
            if(p.isReadPermission() && android.provider.DocumentsContract.isTreeUri(p.getUri())) { selected=p.getUri(); break; }
        if(selected==null) { result.put("uiScopeCheck","no persisted tree permission"); return; }
        final android.net.Uri tree=selected;
        Activity activity=runner.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        VcFlowView[] flow=new VcFlowView[1]; Throwable[] failure=new Throwable[1];
        try {
            runner.runOnMainSync(() -> {
                try {
                    flow[0]=new VcFlowView(activity,null); activity.setContentView(flow[0]);
                    find(flow[0],"mode-original").performClick();
                    if(find(flow[0],"authorize-and-start").isEnabled()) throw new AssertionError("scope required");
                    flow[0].handleActivityResult(VcFlowView.REQUEST_ORIGINAL_TREE,Activity.RESULT_OK,
                        new Intent().setData(tree).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    if(!find(flow[0],"authorize-and-start").isEnabled())throw new AssertionError("manual scope readiness");
                    ((EditText)find(flow[0],"llm-task-intent")).setText("按用途分类我这些简历");
                    find(flow[0],"llm-understand-task").performClick();
                } catch(Throwable e){failure[0]=e;}
            });
            if(failure[0]!=null) throw new AssertionError("UI setup",failure[0]);
            long until=android.os.SystemClock.elapsedRealtime()+35000; boolean[] ready={false};
            while(android.os.SystemClock.elapsedRealtime()<until) {
                runner.runOnMainSync(() -> ready[0]=find(flow[0],"authorize-and-start").isEnabled());
                if(ready[0])break; Thread.sleep(200);
            }
            if(!ready[0])throw new AssertionError("understand lost selected scope or failed route");
            runner.runOnMainSync(() -> {
                find(flow[0],"mode-ticket").performClick();
                ready[0]=find(flow[0],"authorize-and-start").isEnabled();
            });
            if(ready[0])throw new AssertionError("mode switch retained authorization");
            result.put("uiScopeBeforeUnderstanding",true).put("uiModeInvalidation",true);
        } finally { runner.runOnMainSync(activity::finish); }
    }
    private static View find(View view,String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            View found=find(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;
        }
        return null;
    }
}
