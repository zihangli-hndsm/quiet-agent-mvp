package app.quietagent.workspace;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.DateFormat;
import java.util.*;

/** Calm, single-screen UI for persistent workspace grants, tasks and results. */
public final class WorkspaceActivity extends Activity {
  private static final int DIR=7101, FILES=7102, SMS=7103, EXPORT=7104;
  private static final int PAGE=Color.rgb(242,242,247), CARD=Color.WHITE;
  private static final int INK=Color.rgb(28,28,30), MUTED=Color.rgb(99,99,102);
  private static final int GREEN=Color.rgb(24,103,67), GREEN_SOFT=Color.rgb(232,244,237), RED=Color.rgb(191,54,48);
  private WorkspaceStore store;
  private LinearLayout rootList;
  private EditText chat;
  private TextView taskState, taskSummary;
  private LinearLayout taskLog;
  private Button cancelButton;
  private String currentJob, exportId;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private boolean polling;
  private final Runnable poll=new Runnable(){@Override public void run(){refreshTask();if(polling)handler.postDelayed(this,900L);}};

  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    getWindow().setStatusBarColor(PAGE);getWindow().setNavigationBarColor(PAGE);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    if(Build.VERSION.SDK_INT>=23)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    store=new WorkspaceStore(this);
    currentJob=getIntent().getStringExtra("jobId");
    if(currentJob==null)currentJob=getPreferences(0).getString("lastJob",null);
    else getPreferences(0).edit().putString("lastJob",currentJob).apply();
    buildScreen();
  }

  private void buildScreen(){
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(PAGE);
    LinearLayout page=column();page.setPadding(dp(20),dp(18),dp(20),dp(36));scroll.addView(page);
    TextView eyebrow=label("QUIET AGENT");eyebrow.setTextColor(GREEN);page.addView(eyebrow);
    TextView title=text("工作区",34,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);page.addView(title);
    page.addView(text("把资料放进来，然后用一句话交代任务。",16,MUTED),margins(0,2,0,20));

    LinearLayout notice=card();TextView nt=text("加入即授权",17,INK);nt.setTypeface(Typeface.DEFAULT,Typeface.BOLD);notice.addView(nt);
    notice.addView(text("助手可以按你的指令读取、整理和修改工作区文件，并按需将文档文本及图片识别文字发送给云端模型。删除与覆盖会先保留可恢复副本。",14,MUTED),margins(0,6,0,0));
    page.addView(notice,margins(0,14,0,24));

    page.addView(label("工作区资料"));rootList=column();page.addView(rootList,margins(0,8,0,10));
    LinearLayout addRow=row();Button addDir=secondaryButton("＋ 目录");addDir.setOnClickListener(v->chooseDirectory());
    Button addFiles=secondaryButton("＋ 文件 / 图片");addFiles.setOnClickListener(v->chooseFiles());
    addRow.addView(addDir,weight());addRow.addView(addFiles,weightLeft());page.addView(addRow);
    Button addSms=secondaryButton("＋ 导入短信副本");addSms.setOnClickListener(v->chooseSms());page.addView(addSms,margins(0,8,0,24));

    page.addView(label("告诉助手"));LinearLayout composer=card();chat=new EditText(this);
    chat.setHint("例如：读完这些资料，按用途分类并生成一份表格");chat.setHintTextColor(Color.rgb(142,142,147));chat.setTextColor(INK);chat.setTextSize(16);chat.setGravity(Gravity.TOP);
    chat.setMinLines(3);chat.setMaxLines(6);chat.setBackgroundColor(Color.TRANSPARENT);composer.addView(chat,new LinearLayout.LayoutParams(-1,-2));
    Button send=primaryButton("发送任务");send.setOnClickListener(v->startTask());composer.addView(send,margins(0,10,0,0));page.addView(composer,margins(0,8,0,24));

    page.addView(label("当前任务"));LinearLayout activity=card();taskState=text("等待任务",17,INK);taskState.setTypeface(Typeface.DEFAULT,Typeface.BOLD);activity.addView(taskState);
    taskSummary=text("完成后，摘要和成果会显示在这里。",14,MUTED);activity.addView(taskSummary,margins(0,6,0,10));
    taskLog=column();activity.addView(taskLog,margins(0,0,0,6));
    cancelButton=quietButton("停止任务",RED);cancelButton.setOnClickListener(v->cancelCurrent());cancelButton.setEnabled(false);activity.addView(cancelButton);
    page.addView(activity,margins(0,8,0,24));

    page.addView(label("管理"));LinearLayout management=card();
    management.addView(menu("浏览文件与成果","查看、预览或导出工作区内容",v->chooseRootToBrowse()));management.addView(divider());
    management.addView(menu("任务历史","查看过去任务的状态与摘要",v->showHistory()));management.addView(divider());
    management.addView(menu("回收区","恢复删除或覆盖前保留的副本",v->showRecycle()));
    page.addView(management,margins(0,8,0,24));
    page.addView(text("当前支持文档、表格、图片 OCR 与短信副本。可安装 App 的独立执行环境已预留接口，尚未开放。",12,MUTED));
    setContentView(scroll);refreshRoots();refreshTask();
  }

  private void chooseDirectory(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,DIR);}
  private void chooseFiles(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).putExtra(Intent.EXTRA_LOCAL_ONLY,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,FILES);}

  private void startTask(){
    try{currentJob=UnifiedAgentService.start(this,chat.getText().toString().trim());getPreferences(0).edit().putString("lastJob",currentJob).apply();chat.setText("");taskState.setText("正在开始…");taskSummary.setText("你可以切换到其他应用，任务会在后台继续。");taskLog.removeAllViews();polling=true;handler.removeCallbacks(poll);handler.post(poll);}catch(Exception e){error("无法开始任务",e);}
  }
  private void cancelCurrent(){if(currentJob==null)return;UnifiedAgentService.cancel(this,currentJob);taskSummary.setText("已请求停止；当前工具返回后不会继续执行。");}

  private void refreshRoots(){
    rootList.removeAllViews();
    try{store.compactDuplicateRoots();repairLegacyRootNames();List<String> ids=new ArrayList<>();for(String id:store.listRoots())if(!WorkspaceStore.PRIVATE_ROOT.equals(id))ids.add(id);int sms=store.listSmsImports().size();String title=ids.isEmpty()&&sms==0?"尚未加入资料":ids.size()+" 个授权项目 · "+sms+" 条短信";StringBuilder names=new StringBuilder();for(int i=0;i<Math.min(3,ids.size());i++){if(i>0)names.append(" · ");names.append(store.rootLabel(ids.get(i)));}if(ids.size()>3)names.append(" · 另有 ").append(ids.size()-3).append(" 项");if(sms>0){if(names.length()>0)names.append(" · ");names.append("短信副本 ").append(sms).append(" 条");}String detail=ids.isEmpty()&&sms==0?"添加目录、文件、图片或短信副本":names.toString();LinearLayout box=card();box.addView(menu(title,detail,v->startActivity(new Intent(this,WorkspaceManagerActivity.class))));rootList.addView(box);}
    catch(Exception e){rootList.addView(text("工作区授权记录不可读："+msg(e),14,RED));}
  }

  private void repairLegacyRootNames(){try{for(String id:store.listRoots())if(!WorkspaceStore.PRIVATE_ROOT.equals(id)&&"工作区目录".equals(store.rootName(id))){String name=directoryName(Uri.parse(store.rootUri(id)));if(!name.isEmpty())store.updateRootName(id,name);}}catch(Exception ignored){}}

  private void refreshTask(){
    if(currentJob==null){taskState.setText("等待任务");taskSummary.setText("完成后，摘要和成果会显示在这里。");taskLog.removeAllViews();cancelButton.setEnabled(false);return;}
    try{JSONObject job=UnifiedAgentService.status(this,currentJob);String state=job.optString("state","UNKNOWN");taskState.setText(humanState(state));String summary=job.optString("summary","");String phase=job.optString("phase","");String failure=job.optString("failure","");String body=summary.isEmpty()?phase:summary;if(!failure.isEmpty()&&!failure.equals(phase))body+="\n"+failure;taskSummary.setText(body.isEmpty()?"等待执行记录…":body);renderTaskEvents(job.optJSONArray("events"));boolean running="QUEUED".equals(state)||"RUNNING".equals(state);cancelButton.setEnabled(running);if(!running)polling=false;}
    catch(Exception e){taskState.setText("状态暂不可读");taskSummary.setText(msg(e));taskLog.removeAllViews();cancelButton.setEnabled(false);}
  }
  private void renderTaskEvents(JSONArray events){taskLog.removeAllViews();if(events==null)return;int start=Math.max(0,events.length()-6);for(int i=start;i<events.length();i++){JSONObject e=events.optJSONObject(i);if(e==null)continue;String status=e.optString("status"),symbol="running".equals(status)?"●":"success".equals(status)?"✓":"failed".equals(status)?"!":"·";int color="failed".equals(status)?RED:"success".equals(status)?GREEN:MUTED;String line=symbol+"  第 "+e.optInt("call")+" 次 · "+e.optString("label","处理资料");String detail=e.optString("detail","");if(!detail.isEmpty())line+="\n    "+detail;TextView item=text(line,13,color);item.setMaxLines(2);taskLog.addView(item,margins(0,3,0,3));}}

  private void chooseRootToBrowse(){try{List<String> ids=store.listRoots();new AlertDialog.Builder(this).setTitle("浏览工作区").setItems(rootLabels(ids),(d,w)->browseRoot(ids.get(w))).setNegativeButton("关闭",null).show();}catch(Exception e){error("无法读取工作区",e);}}
  private void browseRoot(String rootId){
    try{WorkspaceFileAccess.Page page=store.list(rootId,"",0,500,true);if(page.resources.isEmpty()){toast("这里还没有文件");return;}String[] names=new String[page.resources.size()];for(int i=0;i<names.length;i++){WorkspaceFileAccess.Resource r=page.resources.get(i);names[i]=(r.directory?"文件夹  ":"文件  ")+r.name;}new AlertDialog.Builder(this).setTitle(store.rootLabel(rootId)).setItems(names,(d,w)->preview(page.resources.get(w))).setNegativeButton("关闭",null).show();}
    catch(Exception e){error("无法浏览资料",e);}
  }
  private void preview(WorkspaceFileAccess.Resource resource){if(resource.directory)return;try{WorkspaceFileAccess.Stat stat=store.stat(resource.id);String body;try{body=store.readText(resource.id);if(body.length()>12000)body=body.substring(0,12000)+"\n\n…预览已截断";}catch(Exception unsupported){body="类型：只读文件\n大小："+stat.size+" 字节\n\n可交给助手读取或识别，也可以导出副本。";}new AlertDialog.Builder(this).setTitle(stat.name).setMessage(body).setPositiveButton("完成",null).setNeutralButton("导出副本",(d,w)->export(resource.id,stat.name)).show();}catch(Exception e){error("无法打开文件",e);}}
  private void export(String id,String name){exportId=id;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime(name)).putExtra(Intent.EXTRA_TITLE,name),EXPORT);}

  private void chooseRootToRemove(){
    try{List<String> ids=new ArrayList<>();for(String id:store.listRoots())if(!WorkspaceStore.PRIVATE_ROOT.equals(id))ids.add(id);if(ids.isEmpty()){toast("没有可以移出的资料");return;}new AlertDialog.Builder(this).setTitle("移出工作区").setMessage("只撤销工作区授权，不会删除原文件。").setItems(rootLabels(ids),(d,w)->{try{store.removeRoot(ids.get(w));refreshRoots();toast("已移出工作区");}catch(Exception e){error("移出失败",e);}}).setNegativeButton("取消",null).show();}
    catch(Exception e){error("无法读取授权",e);}
  }

  private void showHistory(){
    try{JSONArray a=UnifiedAgentService.history(this);if(a.length()==0){toast("还没有任务记录");return;}String[] labels=new String[a.length()];for(int i=0;i<labels.length;i++){JSONObject x=a.getJSONObject(i);labels[i]=humanState(x.optString("state"))+"  ·  "+shorten(x.optString("request"),34);}new AlertDialog.Builder(this).setTitle("任务历史").setItems(labels,(d,w)->{JSONObject x=a.optJSONObject(w);if(x==null)return;currentJob=x.optString("jobId",null);getPreferences(0).edit().putString("lastJob",currentJob).apply();refreshTask();}).setNegativeButton("关闭",null).show();}
    catch(Exception e){error("历史记录不可读",e);}
  }

  private void showRecycle(){
    try{final List<WorkspaceStore.RecycleItem> all=store.listRecycleItems();if(all.isEmpty()){toast("回收区为空");return;}String[] labels=new String[all.size()];final boolean[] selected=new boolean[all.size()];for(int i=0;i<labels.length;i++){WorkspaceStore.RecycleItem x=all.get(i);String source=x.rootName+(x.path.isEmpty()?"":" / "+x.path);labels[i]=x.name+"\n"+formatDate(x.createdAt)+" · "+formatBytes(x.size)+" · 来源 "+shorten(source,42);}AlertDialog dialog=new AlertDialog.Builder(this).setTitle("回收区 · "+all.size()+" 项").setMultiChoiceItems(labels,selected,(d,w,on)->selected[w]=on).setNeutralButton("全选",null).setNegativeButton("关闭",null).setPositiveButton("批量操作",null).create();dialog.setOnShowListener(v->{Button allButton=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);allButton.setOnClickListener(x->{boolean every=true;for(boolean on:selected)if(!on){every=false;break;}for(int i=0;i<selected.length;i++){selected[i]=!every;dialog.getListView().setItemChecked(i,!every);}allButton.setText(every?"全选":"取消全选");});dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->recycleBatch(dialog,all,selected));});dialog.show();}
    catch(Exception e){error("回收区不可读",e);}
  }
  private void recycleBatch(AlertDialog parent,List<WorkspaceStore.RecycleItem> all,boolean[] selected){final ArrayList<String> ids=new ArrayList<>();for(int i=0;i<selected.length;i++)if(selected[i])ids.add(all.get(i).id);if(ids.isEmpty()){toast("请先选择项目");return;}new AlertDialog.Builder(this).setTitle("处理所选 "+ids.size()+" 项").setMessage("恢复时不会覆盖后来产生的同名文件；永久清除后无法恢复。").setPositiveButton("批量恢复",(d,w)->{int ok=0,failed=0;for(String id:ids)try{store.restore(id);ok++;}catch(Exception e){failed++;}parent.dismiss();refreshRoots();toast(failed==0?"已恢复 "+ok+" 项":"已恢复 "+ok+" 项，失败 "+failed+" 项");}).setNeutralButton("永久清除",(d,w)->confirmClearMany(parent,ids)).setNegativeButton("取消",null).show();}
  private void confirmClearMany(AlertDialog parent,List<String> ids){new AlertDialog.Builder(this).setTitle("永久清除 "+ids.size()+" 项？").setMessage("清除后无法从 Quiet Agent 恢复这些副本。").setPositiveButton("永久清除",(d,w)->{int ok=0,failed=0;for(String id:ids)try{store.clearRecycle(id);ok++;}catch(Exception e){failed++;}parent.dismiss();toast(failed==0?"已永久清除 "+ok+" 项":"已清除 "+ok+" 项，失败 "+failed+" 项");}).setNegativeButton("取消",null).show();}

  private void chooseSms(){
    if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.READ_SMS},SMS);return;}
    try{final JSONArray messages=app.quietagent.SmsData.readRecent(this,app.quietagent.SmsData.MAX_IMPORT_MESSAGES);if(messages.length()==0){new AlertDialog.Builder(this).setTitle("没有可导入的短信").setMessage("系统短信收件箱没有返回记录。请确认默认短信应用中已有收到的短信，并允许 Quiet Agent 读取短信。").setPositiveButton("好",null).show();return;}String[] labels=new String[messages.length()];final boolean[] selected=new boolean[messages.length()];for(int i=0;i<labels.length;i++){JSONObject m=messages.getJSONObject(i);boolean excluded=m.optBoolean("excluded");String sender=m.optString("sender","").trim();if(sender.isEmpty())sender="未知发送方";sender=shorten(sender,12);String when=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(m.optLong("date")));String reason=m.optString("excludeReason","不可导入");labels[i]=(excluded?"不可选 · ":"")+sender+" · "+when+" · "+(excluded?reason:shorten(m.optString("body"),20));}AlertDialog dialog=new AlertDialog.Builder(this).setTitle("选择短信副本（最近 "+messages.length()+" 条）").setMultiChoiceItems(labels,selected,(d,w,checked)->{JSONObject m=messages.optJSONObject(w);if(m!=null&&m.optBoolean("excluded")){((AlertDialog)d).getListView().setItemChecked(w,false);selected[w]=false;}else selected[w]=checked;}).setNeutralButton("全选可导入",null).setNegativeButton("取消",null).setPositiveButton("导入",null).create();dialog.setOnShowListener(x->{ListView smsList=dialog.getListView();ViewGroup.OnHierarchyChangeListener limiter=new ViewGroup.OnHierarchyChangeListener(){private void limit(View child){if(child instanceof TextView){TextView row=(TextView)child;row.setMaxLines(2);row.setEllipsize(android.text.TextUtils.TruncateAt.END);}}@Override public void onChildViewAdded(View parent,View child){limit(child);}@Override public void onChildViewRemoved(View parent,View child){}};smsList.setOnHierarchyChangeListener(limiter);for(int i=0;i<smsList.getChildCount();i++)limiter.onChildViewAdded(smsList,smsList.getChildAt(i));Button all=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);all.setOnClickListener(v->{boolean every=true;for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&!m.optBoolean("excluded")&&!selected[i]){every=false;break;}}boolean target=!every;for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);boolean value=m!=null&&!m.optBoolean("excluded")&&target;selected[i]=value;dialog.getListView().setItemChecked(i,value);}all.setText(target?"取消全选":"全选可导入");});dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int count=0;for(int i=0;i<messages.length();i++)if(selected[i]){JSONObject m=messages.getJSONObject(i);if(m.optBoolean("excluded"))continue;String content="发送方："+m.optString("sender")+"\n时间："+DateFormat.getDateTimeInstance().format(new Date(m.optLong("date")))+"\n正文："+m.optString("body");store.importText("sms-"+m.optLong("date")+"-"+i+".txt",content);count++;}if(count==0){toast("请至少选择一条可导入短信");return;}dialog.dismiss();refreshRoots();toast("已导入 "+count+" 条短信副本");}catch(Exception e){error("短信导入失败",e);}});});dialog.show();}
    catch(Exception e){error("无法读取短信",e);}
  }

  @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==SMS&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)chooseSms();}
  @Override protected void onActivityResult(int code,int result,Intent data){
    super.onActivityResult(code,result,data);if(result!=RESULT_OK||data==null)return;
    try{if(code==EXPORT){if(exportId==null||data.getData()==null)throw new IOException("没有待导出文件");try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"w")){if(out==null)throw new IOException("无法打开导出位置");out.write(store.readBytes(exportId));}exportId=null;toast("已保存副本");return;}int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);if(code==DIR){Uri uri=data.getData();if(uri==null)throw new IOException("未选择目录");getContentResolver().takePersistableUriPermission(uri,flags);store.addTree(directoryName(uri),uri.toString(),flags,false);refreshRoots();toast("目录已加入工作区");}else if(code==FILES){List<Uri> uris=selectedUris(data);for(Uri uri:uris){getContentResolver().takePersistableUriPermission(uri,flags);store.addFile(displayName(uri),uri,flags);}refreshRoots();toast("已加入 "+uris.size()+" 个文件");}}
    catch(Exception e){error("操作失败",e);}
  }
  @Override protected void onResume(){super.onResume();currentJob=getPreferences(0).getString("lastJob",currentJob);polling=true;handler.removeCallbacks(poll);handler.post(poll);refreshRoots();}
  @Override protected void onPause(){super.onPause();polling=false;handler.removeCallbacks(poll);}

  private List<Uri> selectedUris(Intent data){List<Uri> out=new ArrayList<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)out.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)out.add(data.getData());return out;}
  private String displayName(Uri uri){try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}String s=uri.getLastPathSegment();return s==null?"所选文件":s;}
  private String directoryName(Uri uri){try{Uri doc=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri));String name=displayName(doc);if(name!=null&&!name.trim().isEmpty())return name.trim();}catch(Exception ignored){}String id;try{id=DocumentsContract.getTreeDocumentId(uri);}catch(Exception e){id=uri.getLastPathSegment();}if(id==null||id.isEmpty())return"所选目录";int p=Math.max(id.lastIndexOf(':'),id.lastIndexOf('/'));return p>=0&&p+1<id.length()?id.substring(p+1):id;}
  private String[] rootLabels(List<String> ids)throws IOException{String[] out=new String[ids.size()];for(int i=0;i<out.length;i++)out[i]=store.rootLabel(ids.get(i));return out;}
  private String humanState(String s){if("QUEUED".equals(s))return"排队中";if("RUNNING".equals(s))return"正在工作";if("COMPLETED".equals(s))return"已完成";if("PARTIAL".equals(s))return"部分完成";if("CANCELLED".equals(s))return"已停止";if("INTERRUPTED".equals(s))return"已中断";if("FAILED".equals(s))return"未完成";return s==null||s.isEmpty()?"等待任务":s;}
  private String mime(String n){n=n.toLowerCase(Locale.ROOT);if(n.endsWith(".csv"))return"text/csv";if(n.endsWith(".html"))return"text/html";if(n.endsWith(".txt")||n.endsWith(".md"))return"text/plain";if(n.endsWith(".zip"))return"application/zip";if(n.endsWith(".png"))return"image/png";if(n.endsWith(".jpg")||n.endsWith(".jpeg"))return"image/jpeg";return"application/octet-stream";}
  private String msg(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
  private String shorten(String s,int n){if(s==null)return"";s=s.replace('\n',' ').replace('\r',' ').trim();return s.length()<=n?s:s.substring(0,n)+"…";}
  private String formatDate(long value){return value<=0?"时间未知":new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(new Date(value));}
  private String formatBytes(long n){if(n<1024)return n+" B";if(n<1024*1024)return String.format(Locale.ROOT,"%.1f KB",n/1024.0);return String.format(Locale.ROOT,"%.1f MB",n/(1024.0*1024.0));}
  private void error(String title,Exception e){new AlertDialog.Builder(this).setTitle(title).setMessage(msg(e)).setPositiveButton("好",null).show();}
  private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
  private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
  private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
  private TextView text(String s,float z,int c){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(c);t.setLineSpacing(0,1.12f);return t;}
  private TextView label(String s){TextView t=text(s,12,MUTED);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setLetterSpacing(.08f);return t;}
  private LinearLayout card(){LinearLayout l=column();l.setPadding(dp(16),dp(15),dp(16),dp(15));l.setBackground(round(CARD,18));l.setElevation(dp(1));return l;}
  private Button primaryButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(16);b.setTextColor(Color.WHITE);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(round(GREEN,13));b.setMinHeight(dp(48));return b;}
  private Button secondaryButton(String s){Button b=new Button(this);b.setText(s);b.setTextSize(15);b.setTextColor(GREEN);b.setAllCaps(false);b.setBackground(round(GREEN_SOFT,13));b.setMinHeight(dp(46));return b;}
  private Button quietButton(String s,int c){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setTextColor(c);b.setAllCaps(false);b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);b.setBackgroundColor(Color.TRANSPARENT);return b;}
  private View menu(String a,String b,View.OnClickListener listener){LinearLayout item=row();item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(0,dp(9),0,dp(9));LinearLayout words=column();words.addView(text(a,16,INK));words.addView(text(b,13,MUTED),margins(0,2,0,0));item.addView(words,new LinearLayout.LayoutParams(0,-2,1));item.addView(text("›",28,Color.rgb(174,174,178)));item.setOnClickListener(listener);return item;}
  private View divider(){View v=new View(this);v.setBackgroundColor(Color.rgb(229,229,234));v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));return v;}
  private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
  private LinearLayout.LayoutParams margins(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
  private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1);}
  private LinearLayout.LayoutParams weightLeft(){LinearLayout.LayoutParams p=weight();p.setMargins(dp(8),0,0,0);return p;}
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
