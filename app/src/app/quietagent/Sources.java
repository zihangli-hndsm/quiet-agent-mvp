package app.quietagent;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import app.quietagent.core.Engine;
import java.io.*;
import java.util.*;

/** URI permissions stay enforced by the provider. file:// accepts only own demo. */
public final class Sources {
 public static Engine.Source create(Context c,String value)throws IOException{
  if(value==null||value.isEmpty())throw new IOException("请先选择源目录");
  Uri uri=Uri.parse(value);
  if("content".equals(uri.getScheme())&&DocumentsContract.isTreeUri(uri)){
   // A remote DocumentsProvider could make network requests in its own process
   // despite this APK having no INTERNET permission. MVP deliberately stays local.
   String authority=uri.getAuthority();
   if(!"com.android.externalstorage.documents".equals(authority))throw new IOException("首版仅支持本机目录，请从内部存储中选择文件夹");
   return new Tree(c,uri);
  }
  if("file".equals(uri.getScheme())){
   File allowed=DemoSource.directory(c).getCanonicalFile();
   if(!allowed.equals(new File(uri.getPath()).getCanonicalFile()))throw new IOException("仅可直接读取本应用的样例目录，请通过选择器授权其他目录");
   return new Local(allowed);
  }
  throw new IOException("不支持的源目录，请重新选择");
 }
 static final class Local implements Engine.Source {
  final File root;
  Local(File root){this.root=root;}
  public String description(){return "体验样例目录（非用户资料）";}
  public List<Engine.Entry> list()throws IOException{List<Engine.Entry> out=new ArrayList<>();walk(root,"",out,0);return out;}
  private void walk(File dir,String prefix,List<Engine.Entry> out,int depth)throws IOException{
   if(depth>32)throw new IOException("目录层级超过32层");
   File[] files=dir.listFiles();if(files==null)throw new IOException("无法读取目录");Arrays.sort(files,Comparator.comparing(File::getName));
   for(File f:files){
    File canonical=f.getCanonicalFile();if(!canonical.getPath().startsWith(root.getPath()+File.separator))throw new IOException("拒绝越界文件");
    if(!canonical.equals(f.getAbsoluteFile()))throw new IOException("不支持符号链接");
    if(f.isDirectory())walk(f,prefix+f.getName()+"/",out,depth+1);
    else{out.add(new Engine.Entry(f.getAbsolutePath(),prefix+f.getName(),f.length(),f.lastModified()));if(out.size()>5000)throw new IOException("目录超过5000个文件，请选择较小目录");}
   }
  }
  public InputStream open(Engine.Entry entry)throws IOException{
   File f=new File(entry.id).getCanonicalFile();if(!f.getPath().startsWith(root.getPath()+File.separator))throw new IOException("拒绝越界读取");return new FileInputStream(f);
  }
 }
 static final class Tree implements Engine.Source {
  final Context c;final Uri tree;
  Tree(Context c,Uri tree){this.c=c.getApplicationContext();this.tree=tree;}
  public String description(){return "用户授权目录："+DocumentsContract.getTreeDocumentId(tree);}
  public List<Engine.Entry> list()throws IOException{
   List<Engine.Entry> out=new ArrayList<>();walk(DocumentsContract.getTreeDocumentId(tree),"",out,new HashSet<>(),0);return out;
  }
  void walk(String id,String prefix,List<Engine.Entry> out,Set<String> visited,int depth)throws IOException{
   if(depth>32||visited.size()>5000)throw new IOException("目录过大或层级过深，请选择较小目录");
   if(!visited.add(id))throw new IOException("目录包含循环引用");
   String[] cols={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_FLAGS};
   try(Cursor rows=c.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,id),cols,null,null,null)){
    if(rows==null)throw new IOException("目录读取失败或授权失效");
    while(rows.moveToNext()){
     String child=rows.getString(0),name=rows.getString(1),mime=rows.getString(2);
     if(child==null||name==null||name.isEmpty())throw new IOException("文件提供方缺少名称或编号");
     if(name.contains("/")||name.contains("\\")||name.equals(".")||name.equals(".."))throw new IOException("不支持的文件名称");
     if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime))walk(child,prefix+name+"/",out,visited,depth+1);
     else{
      if((rows.getInt(5)&DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT)!=0)throw new IOException("目录含不能直接读取的虚拟文件，请改选本地文件目录");
      out.add(new Engine.Entry(DocumentsContract.buildDocumentUriUsingTree(tree,child).toString(),prefix+name,rows.isNull(3)?-1:rows.getLong(3),rows.isNull(4)?0:rows.getLong(4)));
      if(out.size()>5000)throw new IOException("目录超过5000个文件，请选择较小目录");
     }
    }
   }catch(SecurityException|IllegalArgumentException e){throw new IOException("目录授权已失效，请重新选择目录",e);}
  }
  public InputStream open(Engine.Entry e)throws IOException{
   try{InputStream in=c.getContentResolver().openInputStream(Uri.parse(e.id));if(in==null)throw new IOException("无法打开文件");return in;}
   catch(SecurityException ex){throw new IOException("文件读取授权已失效",ex);}
  }
 }
}
