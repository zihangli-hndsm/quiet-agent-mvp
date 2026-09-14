package app.quietagent.test;

import android.app.Instrumentation;
import android.content.*;
import android.net.Uri;
import app.quietagent.*;
import org.json.*;
import java.io.*;

/** Synthetic SMS only: does not insert, delete, send or upload device SMS. */
public final class SmsChecks {
    private interface Call{void run()throws Exception;}
    private static void rejects(Call action)throws Exception{try{action.run();throw new AssertionError("operation accepted");}catch(IOException|IllegalArgumentException expected){}}
    public static void run(Instrumentation runner,JSONObject report)throws Exception{
        if(!"含验证码".equals(SmsData.exclusionReason("您的验证码是123456")))throw new AssertionError("验证码排除原因");
        StringBuilder oversized=new StringBuilder();for(int i=0;i<=SmsData.MAX_BODY_CHARS;i++)oversized.append('a');
        if(!"短信过长（超过2000字）".equals(SmsData.exclusionReason(oversized.toString())))throw new AssertionError("超长排除原因");
        if(!SmsData.exclusionReason("验证码"+oversized).contains("；"))throw new AssertionError("组合排除原因");
        Context c=runner.getTargetContext();String originalDefault=android.provider.Telephony.Sms.getDefaultSmsPackage(c);
        report.put("defaultSmsPackage",originalDefault==null?JSONObject.NULL:originalDefault);
        boolean launchable=originalDefault!=null&&c.getPackageManager().getLaunchIntentForPackage(originalDefault)!=null;
        if(!launchable)launchable=Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,Intent.CATEGORY_APP_MESSAGING).resolveActivity(c.getPackageManager())!=null;
        if(!launchable)throw new AssertionError("default SMS app unavailable");
        boolean denied=c.checkSelfPermission(android.Manifest.permission.READ_SMS)!=android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(denied){
            try{SmsData.read(c,1,"QUIET_NONEXISTENT_TEST_SENDER");throw new AssertionError("ungranted SMS read allowed");}
            catch(SecurityException expected){report.put("ungrantedReadRejected",true);}
        }
        runner.getUiAutomation().adoptShellPermissionIdentity(android.Manifest.permission.READ_SMS);
        try{
            if(SmsData.read(c,1,"QUIET_NONEXISTENT_"+java.util.UUID.randomUUID()).length()!=0)throw new AssertionError("sender scope");
            report.put("realProviderEmptyScope",true);
        }finally{runner.getUiAutomation().dropShellPermissionIdentity();}
        JSONArray messages=new JSONArray();
        String[] bodies={"【虚构星河公司】面试通知：请于9月20日14:00到虚构园区A座参加面试。",
            "【虚构推广】限时优惠！贷款秒批，立即领取促销礼包，回复TD退订。",
            "【虚构物流】包裹已到虚构驿站，请及时领取。",
            "【虚构登录】验证码123456，五分钟内有效。"};
        for(int i=0;i<bodies.length;i++)messages.put(new JSONObject().put("id","s"+i).put("body",bodies[i]).put("sender","虚构发送方")
            .put("date",System.currentTimeMillis()).put("thread",-1).put("excluded",SmsData.isCode(bodies[i])));
        String payload=SmsData.payload("提取面试通知的公司、时间和地点，并筛选垃圾营销短信",messages,new boolean[]{true,true,true,true});
        if(payload.contains("123456")||payload.contains("虚构发送方"))throw new AssertionError("excluded data uploaded");
        rejects(()->SmsData.payload("提取",messages,new boolean[]{false,false,false,false}));
        JSONObject invalid=new JSONObject("{\"columns\":[\"公司\"],\"rows\":[{\"id\":\"s99\",\"cells\":[\"编造\"],\"spam\":\"normal\",\"reason\":\"\",\"evidence\":\"\"}]}");
        rejects(()->SmsData.validate(invalid,payload));
        if(!SmsData.cell("  =HYPERLINK(\"bad\")").startsWith("\"'"))throw new AssertionError("CSV formula");
        SmsJobs jobs=new SmsJobs(c);String unauth=jobs.create(7,"synthetic");
        rejects(()->jobs.consume(unauth));jobs.clear(unauth);
        JSONArray selected=new JSONArray();for(int i=0;i<3;i++)selected.put(messages.getJSONObject(i));
        String tampered=jobs.create(7,"synthetic");jobs.authorize(tampered,payload,selected);
        try(FileOutputStream out=new FileOutputStream(new File(jobs.dir(tampered),"payload.json"))){out.write("{}".getBytes("UTF-8"));}
        rejects(()->jobs.consume(tampered));jobs.clear(tampered);
        String cancelled=jobs.create(7,"synthetic");jobs.authorize(cancelled,payload,selected);jobs.consume(cancelled);
        jobs.fail(cancelled,"CANCELLED","synthetic cancellation");
        rejects(()->jobs.result(cancelled,"result.csv"));jobs.clear(cancelled);
        String id=jobs.create(7,"synthetic");jobs.authorize(id,payload,selected);
        c.startForegroundService(new Intent(c,SmsAnalysisService.class).setAction("RUN").putExtra("jobId",id));
        long until=android.os.SystemClock.elapsedRealtime()+85000;JSONObject state=null;
        while(android.os.SystemClock.elapsedRealtime()<until){state=jobs.status(id);String s=state.getString("state");
            if("SUCCEEDED".equals(s)||"FAILED".equals(s)||"CANCELLED".equals(s))break;Thread.sleep(200);}
        if(state==null||!"SUCCEEDED".equals(state.optString("state")))throw new AssertionError("SMS service failed: "+state);
        JSONObject result=new JSONObject(SmsJobs.read(jobs.result(id,"result.json")));JSONArray rows=result.getJSONArray("rows");
        boolean extracted=false,promo=false;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if("s0".equals(row.getString("id")))extracted=row.getJSONArray("cells").toString().contains("虚构星河公司")&&row.getJSONArray("cells").toString().contains("9月20日14:00")&&row.getJSONArray("cells").toString().contains("虚构园区A座");
            if("s1".equals(row.getString("id")))promo="suspected".equals(row.getString("spam"));
        }
        if(!extracted||!promo)throw new AssertionError("SMS extraction or spam semantics");
        rejects(()->jobs.consume(id));
        Uri uri=Uri.parse("content://"+c.getPackageName()+".sms-results/"+id+"/result.csv");
        try(android.os.ParcelFileDescriptor fd=c.getContentResolver().openFileDescriptor(uri,"r")){if(fd==null)throw new AssertionError("read share");}
        rejects(()->c.getContentResolver().openFileDescriptor(uri,"rw"));
        if(new File(jobs.dir(id),"payload.json").exists()||new File(jobs.dir(id),"sources.json").exists())throw new AssertionError("raw input cleanup");
        if(!java.util.Objects.equals(originalDefault,android.provider.Telephony.Sms.getDefaultSmsPackage(c)))throw new AssertionError("SMS default changed");
        android.app.Activity activity=runner.startActivitySync(new Intent(c,SmsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("jobId",id));
        try{
            runner.runOnMainSync(()->{
                android.view.View button=findText(activity.getWindow().getDecorView(),"打开原短信应用处理");
                if(button==null)throw new AssertionError("SMS handoff button missing");button.performClick();
            });
            long deadline=android.os.SystemClock.elapsedRealtime()+8000;boolean opened=false;
            while(android.os.SystemClock.elapsedRealtime()<deadline){
                android.view.accessibility.AccessibilityNodeInfo window=runner.getUiAutomation().getRootInActiveWindow();
                if(window!=null){opened=originalDefault.contentEquals(window.getPackageName()==null?"":window.getPackageName());window.recycle();}
                if(opened)break;Thread.sleep(200);
            }
            // AOD may own the visible accessibility window even after the SMS activity was launched.
            if(!opened)try(android.os.ParcelFileDescriptor fd=runner.getUiAutomation().executeShellCommand("dumpsys window");
                InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){
                java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(input,"UTF-8"));String line;
                while((line=reader.readLine())!=null)if(line.contains("mFocusedApp=")&&line.contains(originalDefault+"/"))opened=true;
            }
            if(!opened)throw new AssertionError("original SMS activity did not launch");
            report.put("originalSmsActivityLaunched",true);
        }finally{runner.runOnMainSync(activity::finish);}
        report.put("extractedInterview",extracted).put("suspectedSpam",promo).put("codeExcluded",true).put("validatedRows",rows.length())
            .put("consentAndTamperChecks",true).put("readOnlyExport",true).put("defaultSmsUnchanged",true).put("jobId",id);
    }
    private static android.view.View findText(android.view.View view,String text){
        if(view instanceof android.widget.Button && text.contentEquals(((android.widget.Button)view).getText()))return view;
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){
            android.view.View found=findText(((android.view.ViewGroup)view).getChildAt(i),text);if(found!=null)return found;
        }return null;
    }
}
