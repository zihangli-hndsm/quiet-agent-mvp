import app.quietagent.workspace.ControlledWorkspace;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

/** Small boundary checks for the controlled Office workspace executor. */
public final class WorkspaceTests {
  public static void main(String[] args) throws Exception {
    ControlledWorkspace.Input a=new ControlledWorkspace.Input("f0","alice.docx","Alice Java Android\n用途：移动端开发");
    ControlledWorkspace.Input b=new ControlledWorkspace.Input("f1","bo.xlsx","Bo Kotlin 数据分析");
    ControlledWorkspace.Spec s=ControlledWorkspace.authorize("比较四份简历并按岗位分类",Arrays.asList(a,b),System.currentTimeMillis());
    ControlledWorkspace w=new ControlledWorkspace(s,Arrays.asList(a,b),null);
    if(w.list_inputs().size()!=2) throw new AssertionError("list");
    w.read_document("f0");
    try { w.write_table(Collections.singletonList(new ControlledWorkspace.Row("f1","分析","注入","Bo")),new File(Files.createTempDirectory("workspace").toFile(),"results.csv")); throw new AssertionError("unread evidence accepted"); } catch(java.io.IOException expected) {}
    try { w.read_document("../secret"); throw new AssertionError("out of scope accepted"); } catch(java.io.IOException expected) {}
    w.read_document("f1"); File d=Files.createTempDirectory("workspace-ok").toFile();
    w.write_table(Arrays.asList(new ControlledWorkspace.Row("f0","Android","技术岗位","Android"),new ControlledWorkspace.Row("f1","数据","数据分析","数据分析")),new File(d,"results.csv"));
    w.finish(new File(d,"summary.html"),new File(d,"audit.log"));
    if(!new File(d,"results.csv").isFile()) throw new AssertionError("missing csv");
    String csv=new String(Files.readAllBytes(new File(d,"results.csv").toPath()),"UTF-8");
    if(csv.contains("=HYPERLINK")) throw new AssertionError("formula injection");
    try { new ControlledWorkspace(s,Arrays.asList(new ControlledWorkspace.Input("f0","alice.docx","changed"),b),null); throw new AssertionError("changed content accepted"); } catch(IllegalArgumentException expected) {}
    try { new ControlledWorkspace(s,Arrays.asList(a,b),null); throw new AssertionError("authorization replay accepted"); } catch(IllegalArgumentException expected) {}
    ControlledWorkspace.Spec cancelSpec=ControlledWorkspace.authorize("cancel",Arrays.asList(a,b),System.currentTimeMillis()); ControlledWorkspace cancelled=new ControlledWorkspace(cancelSpec,Arrays.asList(a,b),null); try { cancelled.checkCancelled(()->true); throw new AssertionError("cancel ignored"); } catch(java.io.IOException expected) {}
    try { cancelled.finish(new File(d,"cancel.html"),new File(d,"cancel.audit")); throw new AssertionError("cancelled finish accepted"); } catch(java.io.IOException expected) {}
    ControlledWorkspace.Spec dispatchSpec=ControlledWorkspace.authorize("dispatch",Arrays.asList(a,b),System.currentTimeMillis()); ControlledWorkspace dw=new ControlledWorkspace(dispatchSpec,Arrays.asList(a,b),null);
    try { dw.dispatch("delete",Collections.<String,String>emptyMap()); throw new AssertionError("unknown tool accepted"); } catch(java.io.IOException expected) {}
    try { dw.dispatch("read_document",Collections.singletonMap("id","../secret")); throw new AssertionError("path traversal accepted"); } catch(java.io.IOException expected) {}
    System.out.println("WorkspaceTests OK");
  }
}
