package app.quietagent.test;
import android.app.Activity;
import android.os.*;
import android.content.*;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

/** A separate application/UID that owns the foreground keyboard during testing. */
public final class ProbeActivity extends Activity {
 private final Handler handler=new Handler(Looper.getMainLooper());
 private EditText input;
 private final Runnable sample=new Runnable(){public void run(){
  WindowInsets insets=input.getRootWindowInsets();
  sendBroadcast(new Intent("app.quietagent.TEST_PROBE").setPackage("app.quietagent")
   .putExtra("length",input.length()).putExtra("focus",input.hasFocus()&&input.hasWindowFocus())
   .putExtra("ime",insets!=null&&insets.isVisible(WindowInsets.Type.ime()))
   .putExtra("uid",android.os.Process.myUid()));
  handler.postDelayed(this,50);
 }};
 private final BroadcastReceiver finishReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){finish();}};
 public void onCreate(Bundle b){super.onCreate(b);
  if(Intent.ACTION_SEND.equals(getIntent().getAction())){
   Intent result=new Intent("app.quietagent.TEST_EXPORT").setPackage("app.quietagent");
   try {
    android.net.Uri uri=getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
    java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");
    long size=0;try(java.io.InputStream in=getContentResolver().openInputStream(uri)){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1){hash.update(buf,0,n);size+=n;}}
    StringBuilder hex=new StringBuilder();for(byte v:hash.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",v&255));
    boolean denied=false;try(java.io.OutputStream ignored=getContentResolver().openOutputStream(uri,"w")){}catch(Exception expected){denied=true;}
    result.putExtra("hash",hex.toString()).putExtra("bytes",size).putExtra("writeDenied",denied);
   }catch(Exception e){result.putExtra("error",e.toString());}
   result.putExtra("uid",android.os.Process.myUid());sendBroadcast(result);finish();return;
  }
  setShowWhenLocked(true);setTurnScreenOn(true);
  LinearLayout page=new LinearLayout(this);page.setOrientation(1);page.setPadding(36,80,36,36);page.setBackgroundColor(0xfff5f8ff);
  TextView title=new TextView(this);title.setText("独立前台应用 · 自动输入测试");title.setTextSize(25);page.addView(title);
  TextView note=new TextView(this);note.setText("本页属于另一个应用进程。整理服务在后台运行。此处输入为测试工具注入，不是真人操作。");note.setTextSize(17);page.addView(note);
  input=new EditText(this);input.setSingleLine(true);input.setHint("测试输入");page.addView(input);
  TextView list=new TextView(this);list.setText("\n本地列表\n\n周末清单\n\n阅读记录\n\n项目笔记\n\n全部为离线测试内容");list.setTextSize(20);page.addView(list);
  setContentView(page);registerReceiver(finishReceiver,new IntentFilter("app.quietagent.test.FINISH"));
  input.requestFocusFromTouch();handler.postDelayed(()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT),300);
  handler.post(sample);
 }
 public void onDestroy(){handler.removeCallbacksAndMessages(null);if(input!=null)unregisterReceiver(finishReceiver);setShowWhenLocked(false);super.onDestroy();}
}
