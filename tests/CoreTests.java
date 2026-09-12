import app.quietagent.core.Engine;
import app.quietagent.core.Plan;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Minimal no-dependency test entry point: java CoreTests. */
public final class CoreTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        testPlan();
        testArchiveAndManifest();
        testMutationCancellationAndLimits();
        testSemanticPaths();
        System.out.println("CoreTests OK (" + assertions + " assertions)");
    }

    private static void testPlan() {
        Plan p = Plan.parse("把这个目录里的文件去重，按类型归档");
        check(p.deduplicate && p.grouping == Plan.Grouping.TYPE, "default Chinese plan");
        check(Plan.parse("不去重，按月份整理 PDF 最近 7 天").deduplicate == false, "negation");
        check(!Plan.parse("不去重").deduplicate, "standalone negation");
        check(!Plan.parse("保留重复").deduplicate, "keep duplicates");
        check(Plan.parse("最近 7 天的图片").recentDays == 7, "recent range");
        check(Plan.parse("PDF和图片").filters.size() == 2, "union type filter");
        expectIllegal("删除重复文件");
        expectIllegal("不要图片");
        expectIllegal("不含 PDF");
        expectIllegal("排除 视频");
        expectIllegal("除了 PDF");
        expectIllegal("exclude images");
        expectIllegal("no videos");
        expectIllegal("except PDF");
        expectIllegal("按类型并按月份归档");
        expectIllegal("随便整理这些东西");
        expectIllegal("最近 3651 天的图片");
    }

    private static void testArchiveAndManifest() throws Exception {
        MemSource source = new MemSource("手机目录 <本地>");
        source.add("a/报告.txt", "hello");
        source.add("b/报告.txt", "hello");
        source.add("x/photo.jpg", "pixels");
        source.add("z/manual.pdf", "pdf");
        Plan p = Plan.parse("去重，按类型归档");
        File dest = Files.createTempDirectory("quiet-core-test").toFile();
        Engine.Result result = Engine.run(source, p, dest, null, null);
        check(result.scanned == 4 && result.selected == 4 && result.unique == 3 && result.duplicates == 1, "counts");
        check(result.archive.exists() && result.manifest.exists() && result.summary.exists(), "outputs");
        String manifest = readUtf8(result.manifest);
        check(manifest.contains("quiet-agent-manifest-v1") && manifest.contains("\"included\":false") && manifest.contains("\"duplicateOf\":"), "manifest schema and duplicate record");
        String summary = readUtf8(result.summary);
        check(summary.contains("&lt;本地&gt;") && summary.contains("viewport") && summary.contains("overflow-x:auto"), "mobile HTML escaping");
        Map<String, byte[]> zip = readZip(result.archive);
        check(zip.size() == 3 && zip.containsKey("document/报告.txt") && zip.containsKey("image/photo.jpg") && zip.containsKey("pdf/manual.pdf"), "safe grouped paths and ANY filter");
        check(result.archive.getName().equals("archive.zip") && result.manifest.getName().equals("manifest.json") && result.summary.getName().equals("summary.html"), "fixed output names");
        expectIo(new IoCall() { public void call() throws Exception { Engine.run(source, p, dest, null, null); } }, "non-empty destination rejected");
        Engine.Result union = Engine.run(source, Plan.parse("PDF和图片"), Files.createTempDirectory("quiet-core-union").toFile(), null, null);
        check(union.selected == 2 && union.unique == 2, "PDF and image union selection");
    }

    private static void testMutationCancellationAndLimits() throws Exception {
        final MemSource changing = new MemSource("changing");
        changing.add("x.txt", "before");
        changing.mutateOnSecondOpen = true;
        File dest = Files.createTempDirectory("quiet-core-mutation").toFile();
        expectIo(new IoCall() { public void call() throws Exception { Engine.run(changing, Plan.parse("去重"), dest, null, null); } }, "source mutation rejected");
        check(dest.listFiles().length == 0, "mutation cleanup");

        final MemSource duplicateChanging = new MemSource("duplicate changing");
        duplicateChanging.add("a.txt", "same");
        duplicateChanging.add("b.txt", "same");
        duplicateChanging.mutateOnOpen = 3; // initial hash, duplicate hash, duplicate recheck
        expectIo(new IoCall() { public void call() throws Exception { Engine.run(duplicateChanging, Plan.parse("去重"), Files.createTempDirectory("quiet-core-duplicate-change").toFile(), null, null); } }, "duplicate mutation rejected");

        final MemSource cancel = new MemSource("cancel"); cancel.add("x.txt", "data");
        expectIo(new IoCall() { public void call() throws Exception { Engine.run(cancel, Plan.parse("去重"), Files.createTempDirectory("quiet-core-cancel").toFile(), null, new Engine.Cancellation() { public boolean isCancelled() { return true; } }); } }, "cancellation");

        final MemSource tooMany = new MemSource("many");
        for (int i = 0; i < Engine.MAX_FILES + 1; i++) tooMany.add("f" + i + ".txt", "x");
        expectIo(new IoCall() { public void call() throws Exception { Engine.run(tooMany, Plan.parse("不去重，按类型归档"), Files.createTempDirectory("quiet-core-many").toFile(), null, null); } }, "file limit");
    }

    private static void testSemanticPaths() throws Exception {
        MemSource source = new MemSource("用途建议");
        source.add("技术岗位简历/resume.docx", "synthetic office snapshot");
        source.add("技术岗位简历/copy.docx", "synthetic office snapshot");
        File job = Files.createTempDirectory("quiet-job-metadata").toFile();
        Files.write(new File(job,"pending.properties").toPath(), "authorization".getBytes(StandardCharsets.UTF_8));
        Engine.Result result = Engine.run(source, Plan.semantic(), new File(job,"archive-output"), null, null);
        check(result.unique == 1 && result.duplicates == 1, "semantic duplicates");
        check(readZip(result.archive).containsKey("技术岗位简历/resume.docx"), "confirmed category preserved");
        check(readUtf8(result.manifest).contains("\"ruleBased\":false"), "model provenance");
        check(new File(job,"pending.properties").isFile(), "authorization metadata preserved");
        MemSource unsafe = new MemSource("unsafe"); unsafe.add("../escape.docx", "data");
        File output = Files.createTempDirectory("quiet-unsafe-category").toFile();
        try { Engine.run(unsafe, Plan.semantic(), output, null, null); throw new AssertionError("unsafe category accepted"); }
        catch (IllegalArgumentException expected) { check(output.listFiles().length == 0, "unsafe category produces no output"); }
    }

    private interface IoCall { void call() throws Exception; }
    private static void expectIo(IoCall c, String message) throws Exception {
        try { c.call(); throw new AssertionError(message + " (did not fail)"); }
        catch (IOException expected) { assertions++; }
    }
    private static void expectIllegal(String request) {
        try { Plan.parse(request); throw new AssertionError("did not reject: " + request); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }

    private static String readUtf8(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static Map<String, byte[]> readZip(File file) throws IOException {
        Map<String, byte[]> out = new HashMap<String, byte[]>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(file.toPath()))) {
            ZipEntry e; byte[] buf = new byte[8192];
            while ((e = in.getNextEntry()) != null) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                int n; while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
                out.put(e.getName(), b.toByteArray());
            }
        }
        return out;
    }

    private static final class MemSource implements Engine.Source {
        final String description; final List<Engine.Entry> entries = new ArrayList<Engine.Entry>(); final Map<String, byte[]> data = new HashMap<String, byte[]>();
        boolean mutateOnSecondOpen; int mutateOnOpen; int opens;
        MemSource(String d) { description = d; }
        void add(String path, String text) { byte[] b = text.getBytes(StandardCharsets.UTF_8); data.put(path, b); entries.add(new Engine.Entry(path, path, b.length, System.currentTimeMillis())); }
        public List<Engine.Entry> list() { return new ArrayList<Engine.Entry>(entries); }
        public InputStream open(Engine.Entry e) {
            opens++;
            byte[] b = data.get(e.id);
            if ((mutateOnSecondOpen && opens == 2) || (mutateOnOpen > 0 && opens == mutateOnOpen)) b = "changed".getBytes(StandardCharsets.UTF_8);
            return new ByteArrayInputStream(b == null ? new byte[0] : b);
        }
        public String description() { return description; }
    }
}
