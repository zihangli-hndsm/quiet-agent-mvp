package app.quietagent;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.List;

/** Minimal read-only provider. Only completed, allowlisted artifacts are shared. */
public final class ShareProvider extends ContentProvider {
 public static Uri uriFor(Context c,String id,String name){File f=new Store(c).getJobFile(id,name);if(!f.isFile())throw new IllegalArgumentException("结果文件不存在");return new Uri.Builder().scheme("content").authority(c.getPackageName()+".results").appendPath(id).appendPath(name).build();}
 public boolean onCreate(){return true;}
 private File resolve(Uri uri)throws FileNotFoundException{
  try{List<String> parts=uri.getPathSegments();if(parts.size()!=2)throw new IllegalArgumentException();File f=new Store(getContext()).getJobFile(parts.get(0),parts.get(1));
   if(!new File(f.getParentFile(),".complete").isFile()||!new File(f.getParentFile(),"manifest.json").isFile()||!new File(f.getParentFile(),"summary.html").isFile()||!f.isFile())throw new IllegalArgumentException();return f;
  }catch(Exception e){throw new FileNotFoundException("无可读取的完整结果");}
 }
 public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("只允许读取");return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
 public String getType(Uri uri){String name=uri.getLastPathSegment();return "archive.zip".equals(name)?"application/zip":"summary.html".equals(name)?"text/html":"application/json";}
 public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{File f=resolve(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor out=new MatrixCursor(cols);Object[] values=new Object[cols.length];for(int i=0;i<cols.length;i++)values[i]=OpenableColumns.DISPLAY_NAME.equals(cols[i])?f.getName():OpenableColumns.SIZE.equals(cols[i])?f.length():null;out.addRow(values);return out;}catch(FileNotFoundException e){return null;}}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
 public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
 public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
