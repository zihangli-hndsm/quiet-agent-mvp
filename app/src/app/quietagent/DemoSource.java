package app.quietagent;
import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Small, explicitly labelled example. Never imports private user data. */
public final class DemoSource {
 public static File directory(Context c){return new File(c.getExternalFilesDir(null),"demo");}
 public static Uri prepare(Context c)throws IOException{
  File root=directory(c);if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建样例目录");
  put(root,"出差/行程.txt","这是体验样例，不是真实行程。\n9月11日：北京 → 上海。\n");
  put(root,"备份/行程副本.txt","这是体验样例，不是真实行程。\n9月11日：北京 → 上海。\n");
  put(root,"项目甲/笔记.txt","项目甲样例笔记。保留原件，仅生成归档。\n");
  put(root,"项目乙/笔记.txt","项目乙样例笔记。相同名称、不同内容。\n");
  put(root,"清单.csv","项目,金额\n样例交通,120\n样例餐饮,40\n");
  put(root,"说明.md","# 静默整理体验目录\n此目录只含本应用创建的样例。请用“挑选目录”整理自己的文件。\n");
  return Uri.fromFile(root);
 }
 private static void put(File root,String path,String text)throws IOException{
  File f=new File(root,path);if(f.exists())return;if(!f.getParentFile().isDirectory()&&!f.getParentFile().mkdirs())throw new IOException("无法创建样例子目录");
  try(FileOutputStream out=new FileOutputStream(f)){out.write(text.getBytes(StandardCharsets.UTF_8));}
 }
}
