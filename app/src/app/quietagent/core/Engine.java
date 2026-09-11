package app.quietagent.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Streaming, non-destructive local archive engine. It never writes to Source. */
public final class Engine {
    public static final int MAX_FILES = 1000;
    public static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
    public static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final int BUFFER = 32 * 1024;

    public static final class Entry {
        public final String id;
        public final String path;
        public final long size;
        public final long modifiedMillis;
        public Entry(String id, String path, long size, long modifiedMillis) {
            this.id = id;
            this.path = path;
            this.size = size;
            this.modifiedMillis = modifiedMillis;
        }
    }

    public interface Source {
        List<Entry> list() throws IOException;
        InputStream open(Entry e) throws IOException;
        String description();
    }

    public interface Listener { void update(String phase, int done, int total); }
    public interface Cancellation { boolean isCancelled(); }

    public static final class Result {
        public final File archive;
        public final File manifest;
        public final File summary;
        public final int scanned;
        public final int selected;
        public final int unique;
        public final int duplicates;
        public final long bytes;
        public final String archiveSha256;

        public Result(File archive, File manifest, File summary, int scanned, int selected,
                      int unique, int duplicates, long bytes, String archiveSha256) {
            this.archive = archive;
            this.manifest = manifest;
            this.summary = summary;
            this.scanned = scanned;
            this.selected = selected;
            this.unique = unique;
            this.duplicates = duplicates;
            this.bytes = bytes;
            this.archiveSha256 = archiveSha256;
        }
    }

    private static final class Item {
        final Entry entry;
        final String hash;
        final long actualSize;
        final String zipName;
        Item(Entry e, String h, long n, String z) { entry = e; hash = h; actualSize = n; zipName = z; }
    }

    private static final class ManifestRecord {
        final Entry entry;
        final String hash;
        final long actualSize;
        boolean included;
        String archivePath;
        String duplicateOf;
        ManifestRecord(Entry e, String h, long n) { entry = e; hash = h; actualSize = n; }
    }

    private static final class Counter {
        long value;
    }

    private Engine() { }

    public static Result run(Source source, Plan plan, File destination,
                             Listener listener, Cancellation cancel) throws IOException {
        if (source == null || plan == null || destination == null) throw new IllegalArgumentException("来源、计划和归档目录不能为空");
        if (!destination.exists() && !destination.mkdirs()) throw new IOException("无法创建归档目录：" + destination);
        if (!destination.isDirectory()) throw new IOException("归档目标不是目录：" + destination);
        File[] existing = destination.listFiles();
        if (existing == null) throw new IOException("无法读取归档目录");
        if (existing.length != 0) throw new IOException("归档目录必须是新建或空目录，不会覆盖已有文件");
        final List<File> owned = new ArrayList<File>();
        File archive = null, manifest = null, summary = null;
        try {
            checkCancelled(cancel);
            List<Entry> listed = source.list();
            if (listed == null) throw new IOException("来源没有返回文件列表");
            // A Source may walk a tree, so cap enumeration separately from the selected-file limit.
            if (listed.size() > 5000) throw new IOException("来源遍历超过5000项，已停止");
            int scanned = 0;
            List<Entry> candidates = new ArrayList<Entry>();
            long now = System.currentTimeMillis();
            for (Entry e : listed) {
                checkCancelled(cancel);
                scanned++;
                if (e == null || e.id == null || e.path == null || e.path.trim().isEmpty()) continue;
                if (!matches(plan, e, now)) continue;
                if (e.size > MAX_FILE_BYTES) throw new IOException("单个文件超过64 MiB：" + e.path);
                candidates.add(e);
                if (candidates.size() > MAX_FILES) throw new IOException("选中文件超过1000个限制");
                notify(listener, "select", candidates.size(), Math.max(1, listed.size()));
            }
            if (candidates.isEmpty()) throw new IOException("没有符合条件的文件");
            long declaredTotal = 0;
            for (Entry e : candidates) {
                if (e.size >= 0) {
                    declaredTotal = safeAdd(declaredTotal, e.size);
                    if (declaredTotal > MAX_TOTAL_BYTES) throw new IOException("选中文件总大小超过256 MiB限制");
                }
            }

            List<Item> items = new ArrayList<Item>();
            Map<String, ManifestRecord> firstByHash = new HashMap<String, ManifestRecord>();
            List<ManifestRecord> records = new ArrayList<ManifestRecord>();
            Set<String> usedNames = new HashSet<String>();
            long totalActual = 0;
            int done = 0;
            for (Entry e : candidates) {
                checkCancelled(cancel);
                HashResult h = hash(source, e, cancel);
                if (h.size > MAX_FILE_BYTES) throw new IOException("单个文件读取后超过64 MiB：" + e.path);
                totalActual = safeAdd(totalActual, h.size);
                if (totalActual > MAX_TOTAL_BYTES) throw new IOException("选中文件读取后总大小超过256 MiB限制");
                ManifestRecord prior = firstByHash.get(h.hex);
                boolean duplicate = prior != null;
                ManifestRecord record = new ManifestRecord(e, h.hex, h.size);
                if (duplicate) {
                    HashResult second = hash(source, e, cancel);
                    if (second.size != h.size || !second.hex.equals(h.hex)) {
                        throw new IOException("源文件在快照期间发生变化：" + e.path + "（快照不是全局事务）");
                    }
                    record.duplicateOf = prior.entry.id;
                }
                if (!duplicate || !plan.deduplicate) {
                    String name = uniqueName(zipPath(plan, e), usedNames);
                    Item item = new Item(e, h.hex, h.size, name);
                    items.add(item);
                    record.included = true;
                    record.archivePath = name;
                    if (!duplicate) firstByHash.put(h.hex, record);
                } else {
                    record.included = false;
                    record.archivePath = prior.archivePath;
                }
                records.add(record);
                done++;
                notify(listener, "hash", done, candidates.size());
            }
            if (items.isEmpty()) throw new IOException("没有可归档的唯一文件");
            long archiveBytes = 0;
            for (Item item : items) archiveBytes = safeAdd(archiveBytes, item.actualSize);

            File archivePart = new File(destination, "archive.zip.part");
            File manifestPart = new File(destination, "manifest.json.part");
            File summaryPart = new File(destination, "summary.html.part");
            archive = new File(destination, "archive.zip");
            manifest = new File(destination, "manifest.json");
            summary = new File(destination, "summary.html");
            owned.add(archivePart); owned.add(manifestPart); owned.add(summaryPart);
            owned.add(archive); owned.add(manifest); owned.add(summary);

            writeArchive(source, items, archivePart, cancel, listener);
            String zipHash = sha256File(archivePart, cancel);
            verifyArchive(archivePart, items, cancel);
            moveIntoPlace(archivePart, archive);

            checkCancelled(cancel);
            writeManifest(manifestPart, source.description(), plan, scanned, candidates.size(), records, zipHash);
            checkCancelled(cancel);
            writeSummary(summaryPart, source.description(), plan, scanned, candidates.size(), items,
                    candidates.size() - items.size(), archiveBytes, zipHash);
            checkCancelled(cancel);
            moveIntoPlace(manifestPart, manifest);
            moveIntoPlace(summaryPart, summary);
            checkCancelled(cancel);
            notify(listener, "complete", items.size(), items.size());
            checkCancelled(cancel);
            return new Result(archive, manifest, summary, scanned, candidates.size(), items.size(),
                    candidates.size() - items.size(), archiveBytes, zipHash);
        } catch (IOException ex) {
            cleanup(owned);
            throw ex;
        } catch (RuntimeException ex) {
            cleanup(owned);
            throw ex;
        }
    }

    private static boolean matches(Plan p, Entry e, long now) {
        String lower = e.path.toLowerCase(Locale.ROOT);
        if (!p.filters.contains(Plan.Filter.ANY)) {
            boolean typeMatch = false;
            if (p.filters.contains(Plan.Filter.PDF)) typeMatch |= lower.endsWith(".pdf");
            if (p.filters.contains(Plan.Filter.IMAGE)) typeMatch |= ext(lower, "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "tif", "tiff");
            if (p.filters.contains(Plan.Filter.DOCUMENT)) typeMatch |= ext(lower, "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "ppt", "pptx", "csv");
            if (p.filters.contains(Plan.Filter.AUDIO)) typeMatch |= ext(lower, "mp3", "wav", "m4a", "aac", "flac", "ogg", "wma");
            if (p.filters.contains(Plan.Filter.VIDEO)) typeMatch |= ext(lower, "mp4", "mkv", "mov", "avi", "webm", "m4v", "wmv");
            if (!typeMatch) return false;
        }
        if (p.recentDays != null) {
            long age = p.recentDays.longValue() * 86400000L;
            long threshold = now - age;
            if (e.modifiedMillis < threshold || e.modifiedMillis > now + 86400000L) return false;
        }
        return true;
    }

    private static boolean ext(String path, String... extensions) {
        for (String x : extensions) if (path.endsWith("." + x)) return true;
        return false;
    }

    private static final class HashResult { final String hex; final long size; HashResult(String h, long s) { hex = h; size = s; } }

    private static HashResult hash(Source source, Entry e, Cancellation cancel) throws IOException {
        MessageDigest digest = sha256();
        long n = 0;
        InputStream raw = source.open(e);
        if (raw == null) throw new IOException("来源无法打开文件：" + e.path);
        try (InputStream in = new BufferedInputStream(raw, BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int r;
            while ((r = in.read(buf)) != -1) {
                checkCancelled(cancel);
                n = safeAdd(n, r);
                if (n > MAX_FILE_BYTES) throw new IOException("单个文件读取后超过64 MiB：" + e.path);
                digest.update(buf, 0, r);
            }
        }
        return new HashResult(hex(digest.digest()), n);
    }

    private static void writeArchive(Source source, List<Item> items, File part,
                                     Cancellation cancel, Listener listener) throws IOException {
        try (OutputStream raw = new BufferedOutputStream(new FileOutputStream(part), BUFFER);
             ZipOutputStream zip = new ZipOutputStream(raw)) {
            byte[] buf = new byte[BUFFER];
            int done = 0;
            for (Item item : items) {
                checkCancelled(cancel);
                ZipEntry ze = new ZipEntry(item.zipName);
                ze.setTime(item.entry.modifiedMillis >= 0 ? item.entry.modifiedMillis : System.currentTimeMillis());
                zip.putNextEntry(ze);
                MessageDigest digest = sha256();
                long n = 0;
                InputStream rawIn = source.open(item.entry);
                if (rawIn == null) throw new IOException("来源无法再次打开文件：" + item.entry.path);
                try (InputStream in = new BufferedInputStream(rawIn, BUFFER)) {
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        checkCancelled(cancel);
                        n = safeAdd(n, r);
                        if (n > MAX_FILE_BYTES) throw new IOException("单个文件复制时超过64 MiB：" + item.entry.path);
                        digest.update(buf, 0, r);
                        zip.write(buf, 0, r);
                    }
                }
                zip.closeEntry();
                if (n != item.actualSize || !item.hash.equals(hex(digest.digest()))) {
                    throw new IOException("源文件在归档期间发生变化：" + item.entry.path + "（快照不是全局事务）");
                }
                done++;
                notify(listener, "archive", done, items.size());
            }
        }
    }

    private static void verifyArchive(File file, List<Item> items, Cancellation cancel) throws IOException {
        Map<String, String> expected = new HashMap<String, String>();
        for (Item i : items) expected.put(i.zipName, i.hash);
        Set<String> seen = new HashSet<String>();
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(file), BUFFER))) {
            byte[] buf = new byte[BUFFER];
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                checkCancelled(cancel);
                String h = hashZipEntry(in, buf, cancel);
                String wanted = expected.get(e.getName());
                if (wanted == null || !wanted.equals(h) || !seen.add(e.getName()))
                    throw new IOException("归档校验失败：" + e.getName());
            }
        }
        if (seen.size() != items.size()) throw new IOException("归档校验发现缺少文件");
    }

    private static String hashZipEntry(ZipInputStream in, byte[] buf, Cancellation cancel) throws IOException {
        MessageDigest d = sha256();
        int r;
        while ((r = in.read(buf)) != -1) { checkCancelled(cancel); d.update(buf, 0, r); }
        return hex(d.digest());
    }

    private static void writeManifest(File file, String sourceDescription, Plan plan, int scanned,
                                      int selected, List<ManifestRecord> records,
                                      String archiveHash) throws IOException {
        StringBuilder b = new StringBuilder(4096);
        b.append("{\n  \"schema\":\"quiet-agent-manifest-v1\",\n");
        b.append("  \"ruleBased\":true,\n  \"source\":").append(q(sourceDescription)).append(",\n");
        b.append("  \"snapshot\":\"已读取候选列表并逐项读取内容；不是全局事务，不锁定用户文件\",\n");
        b.append("  \"plan\":").append(q(plan.summary())).append(",\n");
        b.append("  \"scanned\":").append(scanned).append(",\n  \"selected\":").append(selected).append(",\n");
        int unique = 0;
        for (ManifestRecord r : records) if (r.included) unique++;
        b.append("  \"unique\":").append(unique).append(",\n  \"duplicates\":").append(selected - unique).append(",\n");
        b.append("  \"archiveSha256\":").append(q(archiveHash)).append(",\n  \"files\":[\n");
        for (int i = 0; i < records.size(); i++) {
            ManifestRecord x = records.get(i);
            if (i > 0) b.append(",\n");
            b.append("    {\"id\":").append(q(x.entry.id)).append(",\"sourcePath\":").append(q(x.entry.path));
            b.append(",\"included\":").append(x.included);
            b.append(",\"duplicateOf\":").append(x.duplicateOf == null ? "null" : q(x.duplicateOf));
            b.append(",\"archivePath\":").append(x.archivePath == null ? "null" : q(x.archivePath)).append(",\"size\":").append(x.actualSize);
            b.append(",\"modifiedMillis\":").append(x.entry.modifiedMillis).append(",\"sha256\":").append(q(x.hash)).append("}");
        }
        b.append("\n  ]\n}\n");
        writeUtf8(file, b.toString());
    }

    private static void writeSummary(File file, String sourceDescription, Plan plan, int scanned,
                                     int selected, List<Item> items, int duplicates, long bytes,
                                     String archiveHash) throws IOException {
        StringBuilder b = new StringBuilder(4096);
        b.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>本地文件归档结果</title><style>body{font-family:sans-serif;background:#fff;color:#164a3a;margin:1rem;line-height:1.5}h1{color:#0b5d3b} .table-wrap{overflow-x:auto}table{border-collapse:collapse;min-width:680px}th,td{border:1px solid #b7d5c7;padding:.35rem;text-align:left}th{background:#e7f3ed}.hash{overflow-wrap:anywhere;word-break:break-word}</style></head><body><h1>本地文件归档结果</h1>");
        b.append("<p><b>规则</b>: ").append(html(plan.summary())).append("</p>");
        b.append("<p><b>来源快照</b>: ").append(html(sourceDescription)).append("。读取的是一次候选列表和逐项内容快照；不是全局事务，也不锁定用户文件。</p>");
        b.append("<p><b>统计</b>: 扫描 ").append(scanned).append("，选择 ").append(selected).append("，写入归档 ").append(items.size()).append("，重复跳过 ").append(duplicates).append("，归档字节 ").append(bytes).append("。</p>");
        b.append("<p class=\"hash\"><b>archive.zip SHA-256</b>: ").append(html(archiveHash)).append("</p>");
        b.append("<p><b>限制</b>: 最多1000个选中文件；总计256 MiB；单文件64 MiB；源文件不可变；仅支持本地流；不上传、不发送、不删除、不移动。归档完成前会回读 ZIP 并核对每项 CRC/内容 SHA-256。</p>");
        b.append("<div class=\"table-wrap\"><table><tr><th>归档路径</th><th>原路径</th><th>大小</th><th>SHA-256</th></tr>");
        for (Item x : items) b.append("<tr><td>").append(html(x.zipName)).append("</td><td>").append(html(x.entry.path)).append("</td><td>").append(x.actualSize).append("</td><td class=\"hash\">").append(html(x.hash)).append("</td></tr>");
        b.append("</table></div></body></html>\n");
        writeUtf8(file, b.toString());
    }

    private static String zipPath(Plan plan, Entry e) {
        String name = baseName(e.path);
        if (plan.grouping == Plan.Grouping.TYPE) return typeOf(name) + "/" + name;
        if (plan.grouping == Plan.Grouping.MONTH) {
            ZonedDateTime z = Instant.ofEpochMilli(Math.max(0L, e.modifiedMillis)).atZone(ZoneId.systemDefault());
            return String.format(Locale.ROOT, "%04d-%02d/%s", z.getYear(), z.getMonthValue(), name);
        }
        return name;
    }

    private static String typeOf(String name) {
        int p = name.lastIndexOf('.');
        if (p < 1 || p == name.length() - 1) return "other";
        String x = name.substring(p + 1).toLowerCase(Locale.ROOT);
        if (ext("x." + x, "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "tif", "tiff")) return "image";
        if (ext("x." + x, "mp3", "wav", "m4a", "aac", "flac", "ogg")) return "audio";
        if (ext("x." + x, "mp4", "mkv", "mov", "avi", "webm", "m4v")) return "video";
        if ("pdf".equals(x)) return "pdf";
        if (ext("x." + x, "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "ppt", "pptx", "csv")) return "document";
        return "other";
    }

    private static String baseName(String p) {
        String s = p.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        String n = slash >= 0 ? s.substring(slash + 1) : s;
        n = n.replaceAll("[\\x00-\\x1F\\x7F]", "_").replace("..", "_").trim();
        if (n.length() == 0 || ".".equals(n)) n = "unnamed";
        return sanitize(n);
    }

    private static String sanitize(String n) {
        String s = n.replace('/', '_').replace('\\', '_').replace(':', '_');
        while (s.startsWith(".")) s = "_" + s.substring(1);
        return s.length() > 180 ? s.substring(0, 180) : s;
    }

    private static String uniqueName(String candidate, Set<String> used) {
        String safe = candidate.replace('\\', '/');
        String dir = "";
        int slash = safe.lastIndexOf('/');
        if (slash >= 0) { dir = safe.substring(0, slash + 1); safe = safe.substring(slash + 1); }
        String stem = safe, ext = "";
        int dot = safe.lastIndexOf('.');
        if (dot > 0) { stem = safe.substring(0, dot); ext = safe.substring(dot); }
        String out = dir + stem + ext;
        int i = 2;
        while (!used.add(out)) out = dir + stem + "-" + (i++) + ext;
        return out;
    }

    private static String uniqueBase(File dest, String prefix) throws IOException {
        for (int i = 1; i < 100000; i++) {
            String b = prefix + (i == 1 ? "" : "-" + i);
            if (!new File(dest, b + ".zip").exists() && !new File(dest, b + ".zip.part").exists()
                    && !new File(dest, b + ".manifest.json").exists() && !new File(dest, b + ".summary.html").exists()) return b;
        }
        throw new IOException("归档目录中已有结果");
    }

    private static void moveIntoPlace(File from, File to) throws IOException {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(from.toPath(), to.toPath()); }
    }

    private static String sha256File(File file, Cancellation cancel) throws IOException {
        MessageDigest d = sha256();
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER)) {
            byte[] buf = new byte[BUFFER]; int r;
            while ((r = in.read(buf)) != -1) { checkCancelled(cancel); d.update(buf, 0, r); }
        }
        return hex(d.digest());
    }

    private static void writeUtf8(File file, String text) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), BUFFER)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    private static String q(String x) {
        if (x == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            switch (c) { case '\\': b.append("\\\\"); break; case '"': b.append("\\\""); break; case '\n': b.append("\\n"); break; case '\r': b.append("\\r"); break; case '\t': b.append("\\t"); break; default: if (c < 32) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
        }
        return b.append('"').toString();
    }

    private static String html(String x) {
        if (x == null) return "";
        return x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static long safeAdd(long a, long b) throws IOException {
        if (b < 0 || a > Long.MAX_VALUE - b) throw new IOException("文件大小计算溢出");
        return a + b;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) b.append(String.format(Locale.ROOT, "%02x", x & 255));
        return b.toString();
    }

    private static void notify(Listener l, String phase, int done, int total) { if (l != null) l.update(phase, done, total); }
    private static void checkCancelled(Cancellation c) throws IOException { if (c != null && c.isCancelled()) throw new IOException("操作已取消"); }
    private static void cleanup(List<File> files) { for (File f : files) if (f != null) try { Files.deleteIfExists(f.toPath()); } catch (IOException ignored) { } }
}
