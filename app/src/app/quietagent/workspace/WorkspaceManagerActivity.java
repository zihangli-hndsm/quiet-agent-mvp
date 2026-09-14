package app.quietagent.workspace;

import android.app.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Dedicated, scalable list for identifying, browsing and removing workspace grants. */
public final class WorkspaceManagerActivity extends Activity {
  private static final int PAGE=Color.rgb(242,242,247), CARD=Color.WHITE;
  private static final int INK=Color.rgb(28,28,30), MUTED=Color.rgb(99,99,102);
  private static final int GREEN=Color.rgb(24,103,67), RED=Color.rgb(191,54,48);
  private WorkspaceStore store; private LinearLayout items; private TextView count;

  @Override public void onCreate(Bundle state){super.onCreate(state);requestWindowFeature(Window.FEATURE_NO_TITLE);getWindow().setStatusBarColor(PAGE);getWindow().setNavigationBarColor(PAGE);if(Build.VERSION.SDK_INT>=23)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);store=new WorkspaceStore(this);build();}

  private void build(){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(PAGE);LinearLayout page=column();page.setPadding(dp(20),dp(18),dp(20),dp(36));scroll.addView(page);
    TextView back=text("‹  工作区",16,GREEN);back.setGravity(Gravity.CENTER_VERTICAL);back.setPadding(0,0,0,dp(8));back.setOnClickListener(v->finish());page.addView(back);
    TextView title=text("工作区资料",32,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);page.addView(title);count=text("",15,MUTED);page.addView(count,margins(0,2,0,22));
    items=column();page.addView(items);page.addView(text("移出只会撤销 Quiet Agent 的工作区关联，不会删除手机中的原文件。",13,MUTED),margins(4,12,4,0));setContentView(scroll);refresh();}

  @Override protected void onResume(){super.onResume();if(items!=null)refresh();}

  private void refresh(){items.removeAllViews();try{store.compactDuplicateRoots();List<String> all=store.listRoots();List<String> granted=new ArrayList<>();for(String id:all)if(!WorkspaceStore.PRIVATE_ROOT.equals(id))granted.add(id);count.setText(granted.isEmpty()?"还没有加入目录或文件":"已加入 "+granted.size()+" 项，点击卡片可查看内容");
      addSmsCard();addResultsCard();for(String id:granted)addRoot(id,false);
    }catch(Exception e){items.addView(text("工作区授权记录不可读："+msg(e),14,RED));}}

  private void addResultsCard()throws Exception{WorkspaceFileAccess.Page page=store.list(WorkspaceStore.PRIVATE_ROOT,"",0,500,true);List<WorkspaceFileAccess.Resource> files=new ArrayList<>();for(WorkspaceFileAccess.Resource r:page.resources)if(!r.directory&&!relative(r.id).startsWith("imports/"))files.add(r);files.sort((a,b)->Long.compare(modified(b),modified(a)));LinearLayout card=card();TextView title=text("▣  成果",18,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);card.addView(title);String detail;if(files.isEmpty())detail="还没有生成成果";else{StringBuilder names=new StringBuilder(files.size()+" 个文件 · ");for(int i=0;i<Math.min(3,files.size());i++){if(i>0)names.append(" · ");names.append(stamp(files.get(i))).append(' ').append(files.get(i).name);}if(files.size()>3)names.append(" · 另有 ").append(files.size()-3).append(" 项");detail=names.toString();}card.addView(text(detail,13,MUTED),margins(0,4,0,10));Button manage=button("管理成果",GREEN);manage.setOnClickListener(v->startActivity(new android.content.Intent(this,WorkspaceResultsActivity.class)));card.addView(manage,new LinearLayout.LayoutParams(-1,dp(44)));items.addView(card,margins(0,0,0,10));}
  private void addSmsCard()throws Exception{List<WorkspaceFileAccess.Resource> sms=store.listSmsImports();LinearLayout card=card();TextView title=text("▤  短信副本",18,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);card.addView(title);card.addView(text(sms.isEmpty()?"工作区中没有短信副本":sms.size()+" 条短信副本 · 原短信不受影响",13,MUTED),margins(0,4,0,10));Button manage=button("管理短信",GREEN);manage.setOnClickListener(v->startActivity(new android.content.Intent(this,WorkspaceSmsActivity.class)));card.addView(manage,new LinearLayout.LayoutParams(-1,dp(44)));items.addView(card,margins(0,0,0,10));}
  private long modified(WorkspaceFileAccess.Resource r){try{return store.stat(r.id).modified;}catch(Exception ignored){return 0;}}
  private String stamp(WorkspaceFileAccess.Resource r){long value=modified(r);return value<=0?"时间未知":new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(new Date(value));}
  private String relative(String id){int p=id.indexOf(':');return p>=0?id.substring(p+1):id;}

  private void addRoot(String id,boolean ignored)throws Exception{LinearLayout card=card();String name=store.rootLabel(id);TextView title=text((store.rootIsFile(id)?"▤  ":"▥  ")+name,18,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);card.addView(title);card.addView(text("已授权的"+(store.rootIsFile(id)?"单个文件":"目录及其子目录"),13,MUTED),margins(0,4,0,10));
    LinearLayout actions=row();Button browse=button("查看内容",GREEN);browse.setOnClickListener(v->browse(id));actions.addView(browse,new LinearLayout.LayoutParams(0,dp(44),1));Button remove=button("移出工作区",RED);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(44),1);rp.setMargins(dp(8),0,0,0);actions.addView(remove,rp);remove.setOnClickListener(v->confirmRemove(id,name));card.addView(actions);items.addView(card,margins(0,0,0,10));}

  private void browse(String id){try{WorkspaceFileAccess.Page page=store.list(id,"",0,500,true);if(page.resources.isEmpty()){toast("这里还没有文件");return;}String[] names=new String[page.resources.size()];for(int i=0;i<names.length;i++){WorkspaceFileAccess.Resource r=page.resources.get(i);names[i]=(r.directory?"文件夹  ":"文件  ")+r.name;}new AlertDialog.Builder(this).setTitle(store.rootLabel(id)).setItems(names,(d,w)->preview(page.resources.get(w))).setNegativeButton("关闭",null).show();}catch(Exception e){error("无法浏览资料",e);}}
  private void preview(WorkspaceFileAccess.Resource r){if(r.directory)return;try{WorkspaceFileAccess.Stat stat=store.stat(r.id);String body;try{body=store.readText(r.id);if(body.length()>12000)body=body.substring(0,12000)+"\n\n…预览已截断";}catch(Exception unsupported){body="只读文件\n大小："+stat.size+" 字节\n\n可在首页交给助手读取或识别。";}new AlertDialog.Builder(this).setTitle(stat.name).setMessage(body).setPositiveButton("完成",null).show();}catch(Exception e){error("无法打开文件",e);}}
  private void confirmRemove(String id,String name){new AlertDialog.Builder(this).setTitle("移出“"+name.replace("（只读）","")+"”？").setMessage("Quiet Agent 将不再访问它，手机中的原文件不会删除。").setPositiveButton("移出",(d,w)->{try{store.removeRoot(id);refresh();toast("已移出工作区");}catch(Exception e){error("移出失败",e);}}).setNegativeButton("取消",null).show();}

  private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
  private LinearLayout card(){LinearLayout l=column();l.setPadding(dp(16),dp(15),dp(16),dp(15));l.setBackground(round(CARD,18));l.setElevation(dp(1));return l;}
  private TextView text(String s,float z,int c){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(c);t.setLineSpacing(0,1.12f);return t;}
  private Button button(String s,int color){Button b=new Button(this);b.setText(s);b.setTextSize(14);b.setTextColor(color);b.setAllCaps(false);b.setBackground(round(Color.rgb(246,246,248),12));return b;}
  private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
  private LinearLayout.LayoutParams margins(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
  private void error(String title,Exception e){new AlertDialog.Builder(this).setTitle(title).setMessage(msg(e)).setPositiveButton("好",null).show();}private String msg(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
