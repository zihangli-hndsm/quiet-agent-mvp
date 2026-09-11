package app.quietagent;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.widget.TextView;
import java.io.*;
import java.nio.charset.StandardCharsets;
public final class ReportActivity extends Activity {
 public void onCreate(Bundle state){super.onCreate(state);
  try{
   File f=new Store(this).getJobFile(getIntent().getStringExtra("jobId"),"summary.html");
   if(!new File(f.getParentFile(),".complete").isFile())throw new IOException("结果尚未发布");
   if(f.length()>4*1024*1024)throw new IOException("报告过大，请导出后查看");
   ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(f)){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)b.write(buf,0,n);}
   WebView web=new WebView(this);web.getSettings().setJavaScriptEnabled(false);web.getSettings().setBlockNetworkLoads(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
   web.setNetworkAvailable(false);
   web.setWebViewClient(new WebViewClient(){public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}public boolean shouldOverrideUrlLoading(WebView v,String url){return true;}});
   setContentView(web);web.loadDataWithBaseURL(null,new String(b.toByteArray(),StandardCharsets.UTF_8),"text/html","UTF-8",null);
  }catch(Exception e){TextView t=new TextView(this);t.setText("报告暂不可用，请返回任务页面重试。");t.setTextSize(18);setContentView(t);}
 }
}
