import app.quietagent.workspace.*;
import java.io.*; import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.util.*;

/** Host boundary checks using real files and LocalBackend; no Android or provider fakes. */
public final class WorkspaceStoreHostTests {
  static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
  public static void main(String[] args)throws Exception{
    Path d=Files.createTempDirectory("workspace-host-"); try {
      WorkspaceStore a=new WorkspaceStore(d.toFile());
      String f=a.create(WorkspaceStore.PRIVATE_ROOT,"note.txt","one"); ok(new String(a.readBytes(f),"UTF-8").equals("one"),"create/read");
      a.writeText(f,"two",a.stat(f).fingerprint); ok(a.readText(f).equals("two"),"overwrite"); ok(!a.listRecycle().isEmpty(),"overwrite backup");
      WorkspaceStore.RecycleItem meta=a.listRecycleItems().get(0);ok(meta.name.equals("note.txt")&&meta.size==3&&meta.createdAt>0&&meta.rootName.equals("成果"),"recycle metadata");
      String op=meta.id; a.restore(op); ok(a.find(WorkspaceStore.PRIVATE_ROOT,"note",true)!=null,"restore conflict");ok(!a.listRecycle().contains(op),"restored item removed from recycle");
      String t=a.create(WorkspaceStore.PRIVATE_ROOT,"trash.txt","trash");a.trash(t);ok(!Files.exists(d.resolve("trash.txt")),"trash");String tr=a.listRecycle().get(a.listRecycle().size()-1).replace(".json","");a.restore(tr);ok(a.find(WorkspaceStore.PRIVATE_ROOT,"trash",false)!=null,"trash restore");
      try{a.stat("unknown:x");throw new AssertionError("unknown root");}catch(IOException expected){}
      try{a.create(WorkspaceStore.PRIVATE_ROOT,"../escape.txt","x");throw new AssertionError("traversal");}catch(IOException expected){}
      Path ext=Files.createTempDirectory(d,"external-");String rid=a.addTree("external",ext.toString(),3,false);String duplicate=a.addTree("real-name",ext.toString(),1,false);ok(rid.equals(duplicate),"duplicate root merged");ok(a.rootLabel(rid).startsWith("real-name"),"merged root label updated");String e=a.create(rid,"a.txt","A");WorkspaceStore b=new WorkspaceStore(d.toFile());b.removeRoot(rid);try{b.readBytes(e);throw new AssertionError("stale root");}catch(IOException expected){}
      Path single=Files.write(d.resolve("single-ticket.png"),new byte[]{1,2,3});String singleRoot=a.addTree("single-ticket.png",single.toString(),1,true);WorkspaceStore reloaded=new WorkspaceStore(d.toFile());WorkspaceFileAccess.Page singlePage=reloaded.list(singleRoot,"",0,10,false);ok(singlePage.resources.size()==1&&singlePage.resources.get(0).id.equals(singleRoot+":"),"single-file root survives reload");
      String x=a.create(WorkspaceStore.PRIVATE_ROOT,"conflict.txt","A");String fp=a.stat(x).fingerprint;Files.write(d.resolve("conflict.txt"),"B".getBytes(StandardCharsets.UTF_8));try{a.writeText(x,"C",fp);throw new AssertionError("external edit accepted");}catch(IOException expected){}ok(new String(a.readBytes(x),"UTF-8").equals("B"),"external preserved");
      String c=a.create(WorkspaceStore.PRIVATE_ROOT,"src.txt","copy");a.mkdir(WorkspaceStore.PRIVATE_ROOT+":","copies");a.copy(c,WorkspaceStore.PRIVATE_ROOT+":copies");ok(a.find(WorkspaceStore.PRIVATE_ROOT,"src",true)!=null,"copy");
      String sms=a.importText("sms-123-0.txt","发送方：样例\n时间：现在\n正文：测试");ok(a.listSmsImports().size()==1&&a.listSmsImports().get(0).id.equals(sms),"sms imports listed");a.trash(sms);ok(a.listSmsImports().isEmpty(),"sms import removable");
      String b1=a.create(WorkspaceStore.PRIVATE_ROOT,"batch-1.txt","1"),b2=a.create(WorkspaceStore.PRIVATE_ROOT,"batch-2.txt","2");a.trash(b1);a.trash(b2);List<WorkspaceStore.RecycleItem> batch=a.listRecycleItems();ok(batch.size()>=2,"batch recycle list");for(WorkspaceStore.RecycleItem item:new ArrayList<>(batch))a.clearRecycle(item.id);ok(a.listRecycleItems().isEmpty(),"batch clear primitives");
      try{a.readBytes(WorkspaceStore.PRIVATE_ROOT+":../bad");throw new AssertionError("bad id");}catch(IOException expected){}
      try{a.createBytes(WorkspaceStore.PRIVATE_ROOT,"bad.docx",new byte[]{1});throw new AssertionError("non-zip binary accepted");}catch(IOException expected){}
      ok(Files.exists(d.getParent().resolve("control/operation-journal.jsonl"))||Files.exists(d.resolve("../control/operation-journal.jsonl")),"journal");
      System.out.println("WorkspaceStoreHostTests OK");
    } finally { delete(d); }
  }
  static void delete(Path p)throws IOException{if(!Files.exists(p))return;try(java.util.stream.Stream<Path>s=Files.walk(p)){s.sorted(Comparator.reverseOrder()).forEach(x->{try{Files.deleteIfExists(x);}catch(IOException e){throw new RuntimeException(e);}});}}
}
