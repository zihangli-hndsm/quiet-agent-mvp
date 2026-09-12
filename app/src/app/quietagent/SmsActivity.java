package app.quietagent;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Telephony;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;

/** SMS scope, consent, exact-payload preview and read-only results. */
public final class SmsActivity extends Activity {
    private static final int BLUE=Color.rgb(0,122,255),INK=Color.rgb(28,28,30);
    private EditText days,sender,request;
    private TextView status;
    private Button readButton,exportButton,clearButton;
    private LinearLayout results;
    private CheckBox spamOnly;
    private JSONObject latestResult;
    private SmsJobs jobs;
    private String job="",rendered="";
    private int pendingDays;
    private String pendingSender;
    private boolean reading;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable poll=new Runnable(){public void run(){refresh();handler.postDelayed(this,900);}};

    public void onCreate(Bundle state){
        super.onCreate(state);jobs=new SmsJobs(this);
        getWindow().setStatusBarColor(Color.rgb(242,242,247));getWindow().setNavigationBarColor(Color.rgb(242,242,247));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);
        root.setPadding(dp(20),dp(20),dp(20),dp(30));root.setBackgroundColor(Color.rgb(242,242,247));scroll.addView(root);setContentView(scroll);
        root.addView(text("短信整理",30));root.addView(text("提取信息 · 筛选疑似垃圾短信",15));
        LinearLayout scope=card(root);scope.addView(text("1  选择读取范围",18));
        days=field(scope,"最近多少天（1—365）",true);days.setText("7");days.setContentDescription("sms-days");
        sender=field(scope,"发送方号码（可选，精确匹配）",false);sender.setContentDescription("sms-sender");
        scope.addView(text("仅查询收件箱内最新30条。系统授予读取权限后，仍需每次确认处理范围。",13));
        LinearLayout task=card(root);task.addView(text("2  描述提取要求",18));
        request=field(task,"例如：提取面试通知的公司、时间、地点",false);
        request.setText("提取面试通知的公司、面试时间和地点，并筛选疑似垃圾短信");request.setContentDescription("sms-request");
        readButton=button("授权读取并预览短信",()->confirmRead());readButton.setContentDescription("sms-read");task.addView(readButton);
        status=text("短信仅在你确认后读取；上传分析前还会展示实际发送内容。",14);task.addView(status);
        task.addView(button("取消当前分析",()->{
            if(SmsAnalysisService.isActive(job))startService(new Intent(this,SmsAnalysisService.class).setAction("CANCEL").putExtra("jobId",job));
        }));
        LinearLayout output=card(root);output.addView(text("3  待核对结果",18));
        exportButton=button("导出表格 CSV",()->confirmExport());exportButton.setEnabled(false);output.addView(exportButton);
        output.addView(button("查看本次审计",()->{
            try{new AlertDialog.Builder(this).setTitle("短信任务审计").setMessage(SmsJobs.read(new java.io.File(jobs.dir(job),"audit.json"))).setPositiveButton("关闭",null).show();}
            catch(Exception e){error("当前没有可查看的任务审计");}
        }));
        output.addView(button("打开原短信应用处理",()->openSms(-1)));
        output.addView(text("这里提供筛选建议；是否删除请在原短信应用中决定。不会自动删除或标记已读。",13));
        clearButton=button("清除此任务本地数据",()->{
            if(job.isEmpty()||reading||SmsAnalysisService.isActive(job))return;
            new AlertDialog.Builder(this).setTitle("清除本地数据？").setMessage("清除表格、短信快照和本任务审计。不能撤回已上传或已导出的副本。")
                .setNegativeButton("返回",null).setPositiveButton("清除",(d,w)->{
                    try{jobs.clear(job);job="";rendered="";latestResult=null;results.removeAllViews();exportButton.setEnabled(false);getPreferences(0).edit().remove("lastJob").apply();status.setText("本地数据已清除");}
                    catch(Exception e){error("清除失败，请重试");}
                }).show();
        });output.addView(clearButton);
        spamOnly=new CheckBox(this);spamOnly.setText("只看疑似垃圾短信");output.addView(spamOnly);
        spamOnly.setOnCheckedChangeListener((v,checked)->{if(latestResult!=null)try{render(latestResult);}catch(Exception e){error("无法刷新筛选结果");}});
        results=new LinearLayout(this);results.setOrientation(1);output.addView(results);
        String incoming=getIntent().getStringExtra("jobId");
        job=incoming!=null?incoming:getPreferences(0).getString("lastJob","");
        if(!job.isEmpty())try{jobs.recover(job);}catch(Exception e){job="";}
    }
    protected void onResume(){super.onResume();handler.post(poll);}
    protected void onPause(){handler.removeCallbacks(poll);super.onPause();}
    private void confirmRead(){
        if(reading||SmsAnalysisService.isActive(job))return;
        try{
            pendingDays=Integer.parseInt(days.getText().toString().trim());pendingSender=sender.getText().toString().trim();
            if(pendingDays<1||pendingDays>365||request.getText().toString().trim().isEmpty()||request.length()>600)throw new IllegalArgumentException();
        }catch(Exception e){error("请输入1至365天和不超过600字的任务描述");return;}
        new AlertDialog.Builder(this).setTitle("确认本地读取短信")
            .setMessage("读取最近"+pendingDays+"天、"+(pendingSender.isEmpty()?"所有发送方": "指定发送方")+"的最新30条收件短信。短信可能含个人、财务和验证码信息；当前仅在本机预览，不上传。")
            .setNegativeButton("取消",null).setPositiveButton("授权本地读取",(d,w)->{
                try{
                    job=jobs.create(pendingDays,pendingSender);getPreferences(0).edit().putString("lastJob",job).apply();
                    rendered="";latestResult=null;results.removeAllViews();exportButton.setEnabled(false);setBusy(true);
                    if(checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[]{Manifest.permission.READ_SMS},901);
                    else load();
                }catch(Exception e){setBusy(false);error("无法保存读取授权，未读取短信");}
            }).show();
    }
    public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==901){
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)load();
            else failLocal("未获得短信读取权限；没有读取短信。可在系统应用权限中允许后重试。");
        }
    }
    private void load(){
        final String id=job;final int range=pendingDays;final String from=pendingSender;
        new Thread(()->{
            try{
                JSONArray rows=SmsData.read(this,range,from);jobs.state(id,"PREVIEW","已读取，等待选择；尚未上传");
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed()){try{jobs.fail(id,"INTERRUPTED","预览已关闭，请重新授权");}catch(Exception ignored){}return;}
                    setBusy(false);showSelection(rows);
                });
            }catch(Exception e){runOnUiThread(()->failLocal("读取失败，可能是权限被撤销；没有上传短信"));}
        },"quiet-sms-read").start();
    }
    private void showSelection(JSONArray rows){
        if(rows.length()==0){try{jobs.state(job,"EMPTY","所选范围内没有短信，请调整时间或发送方");}catch(Exception ignored){}status.setText("所选范围内没有短信，请调整时间或发送方");return;}
        try{
            CharSequence[] labels=new CharSequence[rows.length()];boolean[] chosen=new boolean[rows.length()];
            for(int i=0;i<rows.length();i++){
                JSONObject r=rows.getJSONObject(i);boolean excluded=r.getBoolean("excluded");String body=r.getString("body");
                chosen[i]=!excluded;labels[i]=r.optString("sender")+" · "+android.text.format.DateFormat.format("MM-dd HH:mm",r.getLong("date"))+"\n"
                    +(excluded?"验证码或超长短信：不参与云端分析":body.substring(0,Math.min(200,body.length())));
            }
            new AlertDialog.Builder(this).setTitle("选择本次处理的短信（"+rows.length()+"条）").setCancelable(false)
                .setMultiChoiceItems(labels,chosen,(dialog,which,checked)->{
                    try{if(rows.getJSONObject(which).getBoolean("excluded")){chosen[which]=false;((AlertDialog)dialog).getListView().setItemChecked(which,false);}
                        else chosen[which]=checked;
                    }catch(Exception ignored){}
                }).setNegativeButton("取消",(d,w)->failLocal("已取消，没有上传短信"))
                .setPositiveButton("预览实际发送内容",(d,w)->showPayload(rows,chosen)).show();
        }catch(Exception e){error("无法展示短信预览");}
    }
    private void showPayload(JSONArray rows,boolean[] chosen){
        try{
            final String payload=SmsData.payload(request.getText().toString(),rows,chosen);final JSONArray selected=new JSONArray();
            for(int i=0;i<rows.length();i++)if(chosen[i]&&!rows.getJSONObject(i).optBoolean("excluded"))selected.put(rows.getJSONObject(i));
            ScrollView scroll=new ScrollView(this);TextView body=text("将发送到 DeepSeek 的实际文本如下。可能包含敏感内容，确认前请检查。发送方号码不作为独立字段上传，但正文内的信息会发送；验证码短信已排除。\n\n"+payload,14);scroll.addView(body);
            new AlertDialog.Builder(this).setTitle("确认云端分析范围").setView(scroll).setCancelable(false)
                .setNegativeButton("返回选择",(d,w)->showSelection(rows))
                .setPositiveButton("确认发送并整理",(d,w)->{
                    try{
                        jobs.authorize(job,payload,selected);
                        startForegroundService(new Intent(this,SmsAnalysisService.class).setAction("RUN").putExtra("jobId",job));
                        status.setText("正在后台分析；可以继续使用其他应用");readButton.setEnabled(false);
                    }catch(Exception e){failLocal("任务提交失败，需重新授权");}
                }).show();
        }catch(Exception e){error(e.getMessage());}
    }
    private void refresh(){
        if(job.isEmpty()||reading)return;
        try{
            JSONObject state=jobs.status(job);status.setText(state.optString("message"));
            boolean active=SmsAnalysisService.isActive(job);readButton.setEnabled(!active);clearButton.setEnabled(!active);
            days.setEnabled(!active);sender.setEnabled(!active);request.setEnabled(!active);
            if("SUCCEEDED".equals(state.optString("state"))&&!job.equals(rendered)){
                JSONObject data=new JSONObject(SmsJobs.read(jobs.result(job,"result.json")));render(data);rendered=job;exportButton.setEnabled(true);
            }
        }catch(Exception e){error("无法读取任务结果");}
    }
    private void render(JSONObject data)throws Exception{
        latestResult=data;
        results.removeAllViews();JSONArray rows=data.getJSONArray("rows"),cols=data.getJSONArray("columns"),sources=data.getJSONArray("sources");
        Map<String,JSONObject> source=new HashMap<>();for(int i=0;i<sources.length();i++)source.put(sources.getJSONObject(i).getString("id"),sources.getJSONObject(i));
        int suspected=0;for(int i=0;i<rows.length();i++)if("suspected".equals(rows.getJSONObject(i).getString("spam")))suspected++;
        results.addView(text("处理 "+rows.length()+" 条 · 疑似垃圾 "+suspected+" 条\n所有结果均待核对；缺失字段留空。",16));
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i),original=source.get(row.getString("id"));
            if(spamOnly.isChecked()&&!"suspected".equals(row.getString("spam")))continue;
            StringBuilder b=new StringBuilder(row.getString("id")+" · "+SmsData.label(row.getString("spam"))+"\n");
            JSONArray cells=row.getJSONArray("cells");for(int j=0;j<cols.length();j++)b.append(cols.getString(j)).append("：").append(cells.getString(j).isEmpty()?"未提取到":cells.getString(j)).append('\n');
            b.append("理由：").append(row.getString("reason")).append("\n证据：").append(row.getString("evidence"));
            LinearLayout item=card(results);item.addView(text(b.toString(),14));
            if(original!=null){item.addView(button("回看原短信",()->new AlertDialog.Builder(this).setTitle(original.optString("sender"))
                .setMessage(original.optString("body")).setPositiveButton("关闭",null).show()));
                long thread=original.getLong("thread");item.addView(button("到原短信应用查看",()->openSms(thread)));}
        }
    }
    private void confirmExport(){
        new AlertDialog.Builder(this).setTitle("确认导出短信表格").setMessage("表格可能包含个人和业务信息。确认后打开系统分享面板，由你选择去向。")
            .setNegativeButton("取消",null).setPositiveButton("确认导出",(d,w)->{
                try{
                    jobs.result(job,"result.csv");jobs.event(job,"exportConfirmedAt");
                    Uri uri=new Uri.Builder().scheme("content").authority(getPackageName()+".sms-results").appendPath(job).appendPath("result.csv").build();
                    Intent send=new Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.setClipData(ClipData.newRawUri("短信整理表格",uri));startActivity(Intent.createChooser(send,"导出表格"));jobs.event(job,"shareSheetOpenedAt");
                }catch(Exception e){error("无法导出表格");}
            }).show();
    }
    private void openSms(long thread){
        String pkg=Telephony.Sms.getDefaultSmsPackage(this);
        if(pkg==null){
            try{startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,Intent.CATEGORY_APP_MESSAGING));}
            catch(ActivityNotFoundException e){error("请从桌面打开原短信应用处理");}
            return;
        }
        if(thread>=0)try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("content://mms-sms/conversations/"+thread)).setPackage(pkg));return;}catch(ActivityNotFoundException ignored){}
        try{Intent launch=getPackageManager().getLaunchIntentForPackage(pkg);if(launch==null)throw new ActivityNotFoundException();startActivity(launch);}
        catch(ActivityNotFoundException e){error("请从桌面打开原短信应用查看和处理");}
    }
    private void failLocal(String message){try{if(!job.isEmpty())jobs.fail(job,"CANCELLED",message);}catch(Exception ignored){}setBusy(false);error(message);}
    private void setBusy(boolean value){reading=value;days.setEnabled(!value);sender.setEnabled(!value);request.setEnabled(!value);readButton.setEnabled(!value);}
    private void error(String message){status.setText(message==null?"操作失败，请重试":message);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(INK);t.setPadding(0,dp(8),0,dp(8));return t;}
    private EditText field(LinearLayout parent,String hint,boolean number){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(16);if(number)e.setInputType(InputType.TYPE_CLASS_NUMBER);parent.addView(e);return e;}
    private Button button(String title,Runnable action){Button b=new Button(this);b.setText(title);b.setTextColor(BLUE);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(dp(16),dp(10),dp(16),dp(12));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(18));c.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(14);parent.addView(c,p);return c;}
}
