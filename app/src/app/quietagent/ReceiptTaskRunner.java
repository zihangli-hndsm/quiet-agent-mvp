package app.quietagent;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import app.quietagent.receipt.ReceiptManifest;
import app.quietagent.receipt.ReceiptParser;
import app.quietagent.security.AuthorizationManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Serialized, cancellable receipt snapshot/OCR/package pipeline for a consumed job. */
public final class ReceiptTaskRunner {
    public static final int MAX_IMAGES = 30;
    public static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;
    public static final long MAX_TOTAL_BYTES = 200L * 1024L * 1024L;
    private static final int BUFFER = 32 * 1024;
    private static final int MAX_BITMAP_DIMENSION = 2048;

    public interface Listener { void update(String phase, int done, int total); }
    public interface Cancellation { boolean isCancelled(); }

    public static final class Result {
        public final File archive;
        public final File manifest;
        public final File csv;
        public final File summary;
        public final File auditSnapshot;
        public final File credential;
        public final int scanned;
        public final int selected;
        public final int included;
        public final int duplicates;
        public final Long recognizedTotalCents;
        public final String archiveSha256;
        Result(File archive, File manifest, File csv, File summary, File auditSnapshot, File credential,
               int scanned, int selected, int included, int duplicates, Long total, String hash) {
            this.archive = archive; this.manifest = manifest; this.csv = csv; this.summary = summary;
            this.auditSnapshot = auditSnapshot; this.credential = credential; this.scanned = scanned;
            this.selected = selected; this.included = included; this.duplicates = duplicates;
            this.recognizedTotalCents = total; this.archiveSha256 = hash;
        }
    }

    private final ContentResolver resolver;
    private final ReceiptJobStore jobs;

    public ReceiptTaskRunner(ContentResolver resolver, ReceiptJobStore jobs) {
        if (resolver == null || jobs == null) throw new IllegalArgumentException("OCR依赖不能为空");
        this.resolver = resolver; this.jobs = jobs;
    }

    /** Service convenience entry point: consume by job id before opening any selected URI. */
    public Result run(String jobId, AuthorizationManager authorization, ReceiptOcr ocr,
                      Listener listener, Cancellation cancellation) throws IOException {
        jobs.consume(jobId, authorization);
        return run(jobId, ocr, listener, cancellation);
    }

    /** Run only after ReceiptJobStore.consume has persisted the one-shot authorization marker. */
    public Result run(String jobId, ReceiptOcr ocr, Listener listener, Cancellation cancellation) throws IOException {
        if (ocr == null) throw new IllegalArgumentException("OCR组件不能为空");
        checkCancelled(cancellation);
        ReceiptJobStore.Pending pending = jobs.readForService(jobId);
        File dir = jobs.jobDir(jobId);
        if (new File(dir, ".complete").isFile()) throw new IOException("票据任务已经完成");
        File temp = new File(dir, ".receipt-temp");
        if (!temp.exists() && !temp.mkdirs()) throw new IOException("无法创建票据临时目录");
        boolean rawCleared = false;
        boolean success = false;
        File archive = new File(dir, "archive.zip");
        File manifest = new File(dir, "manifest.json");
        File csv = new File(dir, "receipts.csv");
        File summary = new File(dir, "summary.html");
        File audit = new File(dir, "audit-snapshot");
        File credential = new File(dir, "credential");
        try {
            if (pending.spec.type() != app.quietagent.security.TaskSpec.TaskType.OCR) {
                throw new IOException("票据任务类型不支持原文件目录");
            }
            Snapshot snapshot = snapshot(pending.sourceUris, temp, cancellation, listener);
            jobs.clearRawUri(jobId);
            rawCleared = true;
            List<ReceiptManifest.Row> rows = recognize(snapshot, ocr, cancellation, listener);
            ReceiptManifest receiptManifest = new ReceiptManifest("本地票据来源", rows);
            checkCancelled(cancellation);
            File manifestPart = new File(dir, "manifest.json.part");
            File csvPart = new File(dir, "receipts.csv.part");
            File summaryPart = new File(dir, "summary.html.part");
            File auditPart = new File(dir, "audit-snapshot.part");
            writeText(manifestPart, receiptManifest.toJson());
            checkCancelled(cancellation);
            writeText(csvPart, receiptManifest.toCsv());
            checkCancelled(cancellation);
            writeText(summaryPart, summaryHtml(receiptManifest, pending.summary));
            checkCancelled(cancellation);
            writeText(auditPart, auditSnapshot(snapshot));
            checkCancelled(cancellation);
            Map<String, File> metadata = new HashMap<String, File>();
            metadata.put("manifest.json", manifestPart);
            metadata.put("receipts.csv", csvPart);
            metadata.put("summary.html", summaryPart);
            metadata.put("audit-snapshot", auditPart);
            File archivePart = new File(dir, "archive.zip.part");
            writeArchive(archivePart, snapshot.unique, metadata, cancellation, listener);
            verifyArchive(archivePart, snapshot.unique, metadata, cancellation);
            String archiveHash = sha256File(archivePart, cancellation);
            publishPart(new File(dir, "archive.zip.part"), archive);
            checkCancelled(cancellation);
            publishPart(new File(dir, "manifest.json.part"), manifest);
            checkCancelled(cancellation);
            publishPart(new File(dir, "receipts.csv.part"), csv);
            checkCancelled(cancellation);
            publishPart(new File(dir, "summary.html.part"), summary);
            checkCancelled(cancellation);
            publishPart(new File(dir, "audit-snapshot.part"), audit);
            notify(listener, "verified", snapshot.unique.size(), snapshot.images.size());
            checkCancelled(cancellation);
            success = true;
            return new Result(archive, manifest, csv, summary, audit, credential, snapshot.scanned,
                    snapshot.images.size(), snapshot.unique.size(), snapshot.images.size() - snapshot.unique.size(),
                    receiptManifest.recognizedTotalCents, archiveHash);
        } finally {
            try { if (!rawCleared) jobs.clearRawUri(jobId); } catch (Exception ignored) { }
            deleteTree(temp);
            if (!success) cleanupOutputs(dir);
            try { ocr.close(); } catch (Exception ignored) { }
        }
    }

    private Snapshot snapshot(List<String> sourceUris, File temp, Cancellation cancel, Listener listener) throws IOException {
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (int i = 0; i < sourceUris.size(); i++) {
            checkCancelled(cancel);
            candidates.add(candidate(Uri.parse(sourceUris.get(i))));
        }
        if (candidates.isEmpty()) throw new IOException("没有选择照片");
        List<ImageItem> all = new ArrayList<ImageItem>();
        List<ImageItem> unique = new ArrayList<ImageItem>();
        Map<String, ImageItem> firstByHash = new HashMap<String, ImageItem>();
        long total = 0;
        for (int i = 0; i < candidates.size(); i++) {
            checkCancelled(cancel);
            Candidate c = candidates.get(i);
            File part = new File(temp, safeId(c.uri.toString() + "\n" + i) + ".part");
            CopyResult copied = copyAndValidate(c, part, total, cancel);
            total = safeAdd(total, copied.size);
            ImageItem prior = firstByHash.get(copied.hash);
            ImageItem item = new ImageItem(sha256(c.uri.toString() + "\n" + i + "\n" + c.path), c.path, copied.hash, copied.size, prior == null ? part : null, c.mime, prior == null ? null : prior.sourceId);
            all.add(item);
            if (prior == null) { firstByHash.put(copied.hash, item); unique.add(item); }
            else delete(part);
            notify(listener, "snapshot", i + 1, candidates.size());
        }
        return new Snapshot(candidates.size(), all, unique);
    }

    private Candidate candidate(Uri uri) throws IOException {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) throw new IOException("照片来源授权无效");
        String name = uri.getLastPathSegment();
        String mime;
        try { mime = resolver.getType(uri); }
        catch (SecurityException error) { throw new IOException("照片读取授权无效", error); }
        long size = -1L;
        android.database.Cursor cursor;
        try { cursor = resolver.query(uri, new String[]{"_display_name", "_size", "mime_type"}, null, null, null); }
        catch (SecurityException error) { throw new IOException("照片读取授权无效", error); }
        if (cursor != null) {
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameCol = cursor.getColumnIndex("_display_name");
                    int sizeCol = cursor.getColumnIndex("_size");
                    int mimeCol = cursor.getColumnIndex("mime_type");
                    if (nameCol >= 0 && !cursor.isNull(nameCol)) name = cursor.getString(nameCol);
                    if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = cursor.getLong(sizeCol);
                    if (mimeCol >= 0 && !cursor.isNull(mimeCol)) mime = cursor.getString(mimeCol);
                }
            } finally { if (cursor != null) cursor.close(); }
        }
        if (name == null || name.trim().isEmpty()) name = "未命名";
        if (!isImage(name, mime)) throw new IOException("选择的文件不是 JPEG 或 PNG：" + name);
        if (size > MAX_IMAGE_BYTES) throw new IOException("单张图片超过20 MiB：" + name);
        return new Candidate(uri, name, mime, size);
    }

    private CopyResult copyAndValidate(Candidate c, File target, long currentTotal, Cancellation cancel) throws IOException {
        InputStream raw;
        try { raw = resolver.openInputStream(c.uri); }
        catch (SecurityException error) { throw new IOException("无法打开图片：" + c.path, error); }
        if (raw == null) throw new IOException("无法打开图片：" + c.path);
        MessageDigest digest = sha256Digest();
        long count = 0;
        try (InputStream in = new BufferedInputStream(raw, BUFFER); OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER)) {
            byte[] buffer = new byte[BUFFER]; int n;
            while ((n = in.read(buffer)) != -1) {
                checkCancelled(cancel); count = safeAdd(count, n);
                if (count > MAX_IMAGE_BYTES) throw new IOException("单张图片超过20 MiB：" + c.path);
                if (safeAdd(currentTotal, count) > MAX_TOTAL_BYTES) throw new IOException("图片总大小超过200 MiB限制");
                digest.update(buffer, 0, n); out.write(buffer, 0, n);
            }
        }
        String hash = hex(digest.digest());
        if (!validImage(target)) { delete(target); throw new IOException("图片不是有效的 JPEG 或 PNG：" + c.path); }
        return new CopyResult(hash, count);
    }

    private List<ReceiptManifest.Row> recognize(Snapshot snapshot, ReceiptOcr ocr, Cancellation cancel, Listener listener) throws IOException {
        Map<String, ReceiptParser.ParsedReceipt> parsed = new HashMap<String, ReceiptParser.ParsedReceipt>();
        for (int i = 0; i < snapshot.unique.size(); i++) {
            checkCancelled(cancel);
            ImageItem item = snapshot.unique.get(i);
            Bitmap bitmap = decodeScaled(item.tempFile);
            try {
                bitmap = rotate(bitmap, exifOrientation(item.tempFile));
                ReceiptOcr.Result result = ocr.recognize(bitmap, item.sourceName, new ReceiptOcr.Cancellation() {
                    public boolean isCancelled() { return cancel != null && cancel.isCancelled(); }
                });
                parsed.put(item.sourceId, ReceiptParser.parseLines(result == null ? Collections.<ReceiptParser.OcrLine>emptyList() : result.lines));
            } finally { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); }
            notify(listener, "ocr", i + 1, snapshot.unique.size());
        }
        List<ReceiptManifest.Row> rows = new ArrayList<ReceiptManifest.Row>();
        // ReceiptManifest rows use the same deterministic archive names as writeArchive.
        Map<String, String> names = archiveNames(snapshot.unique);
        rows.clear();
        for (ImageItem item : snapshot.images) {
            ReceiptParser.ParsedReceipt value = parsed.get(item.duplicateOf == null ? item.sourceId : item.duplicateOf);
            rows.add(new ReceiptManifest.Row(item.sourceId, item.sourceName, item.hash, item.size,
                    item.duplicateOf == null ? names.get(item.sourceId) : null, item.duplicateOf == null, item.duplicateOf, value));
        }
        return rows;
    }

    private void writeArchive(File file, List<ImageItem> unique, Map<String, File> metadata, Cancellation cancel, Listener listener) throws IOException {
        Map<String, String> names = archiveNames(unique);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file), BUFFER))) {
            byte[] buffer = new byte[BUFFER];
            for (int i = 0; i < unique.size(); i++) {
                ImageItem item = unique.get(i); String archivePath = names.get(item.sourceId);
                zip.putNextEntry(new ZipEntry(archivePath));
                try (InputStream in = new BufferedInputStream(new FileInputStream(item.tempFile), BUFFER)) {
                    int n; while ((n = in.read(buffer)) != -1) { checkCancelled(cancel); zip.write(buffer, 0, n); }
                }
                zip.closeEntry(); notify(listener, "archive", i + 1, unique.size());
            }
            for (Map.Entry<String, File> meta : metadata.entrySet()) {
                checkCancelled(cancel);
                zip.putNextEntry(new ZipEntry(meta.getKey()));
                try (InputStream in = new BufferedInputStream(new FileInputStream(meta.getValue()), BUFFER)) {
                    int n; while ((n = in.read(buffer)) != -1) { checkCancelled(cancel); zip.write(buffer, 0, n); }
                }
                zip.closeEntry();
            }
        }
    }

    private void verifyArchive(File archive, List<ImageItem> unique, Map<String, File> metadata, Cancellation cancel) throws IOException {
        Map<String, String> expected = new HashMap<String, String>();
        for (ImageItem item : unique) expected.put(archiveNames(unique).get(item.sourceId), item.hash);
        for (Map.Entry<String, File> meta : metadata.entrySet()) expected.put(meta.getKey(), sha256File(meta.getValue(), cancel));
        Set<String> seen = new HashSet<String>();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive), BUFFER))) {
            ZipEntry entry; byte[] buffer = new byte[BUFFER];
            while ((entry = zip.getNextEntry()) != null) {
                MessageDigest digest = sha256Digest(); int n;
                while ((n = zip.read(buffer)) != -1) { checkCancelled(cancel); digest.update(buffer, 0, n); }
                String expectedHash = expected.get(entry.getName());
                zip.closeEntry();
                if (expectedHash == null || !expectedHash.equals(hex(digest.digest())) || !seen.add(entry.getName())) throw new IOException("归档内容回读校验失败：" + entry.getName());
            }
        }
        if (seen.size() != expected.size()) throw new IOException("归档图片数量校验失败");
    }

    private static Map<String, String> archiveNames(List<ImageItem> items) {
        Map<String, String> out = new HashMap<String, String>(); Set<String> used = new HashSet<String>();
        for (ImageItem item : items) out.put(item.sourceId, "receipts/" + uniqueName(item.sourceName, used));
        return out;
    }

    private static String uniqueName(String path, Set<String> used) {
        String name = path.replace('\\', '/'); int slash = name.lastIndexOf('/'); name = slash < 0 ? name : name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]", "_"); if (name.length() == 0) name = "image.png";
        String stem = name, ext = ""; int dot = name.lastIndexOf('.'); if (dot > 0) { stem = name.substring(0, dot); ext = name.substring(dot); }
        String candidate = stem + ext; if (".".equals(candidate) || "..".equals(candidate)) candidate = "image" + ext;
        int n = 2; while (!used.add(candidate)) candidate = stem + "-" + (n++) + ext; return candidate;
    }

    private static String summaryHtml(ReceiptManifest manifest, String plan) {
        StringBuilder b = new StringBuilder("<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>票据整理结果</title><style>body{font-family:sans-serif;color:#174a39;background:#fff;padding:16px}table{border-collapse:collapse;width:100%;min-width:650px}th,td{border:1px solid #b8d8c8;padding:6px}.wrap{overflow-x:auto}</style><h1>票据整理结果</h1>");
        b.append("<p>计划：").append(html(plan)).append("</p><p>写入图片 ").append(manifest.uniqueCount()).append(" 张，重复跳过 ").append(manifest.duplicateCount()).append(" 张，需要复核 ").append(manifest.reviewCount()).append(" 张。</p><p>归档校验摘要将在授权凭据中记录。</p><div class=\"wrap\"><table><tr><th>来源</th><th>商户</th><th>日期</th><th>金额</th><th>说明</th></tr>");
        for (ReceiptManifest.Row row : manifest.rows) b.append("<tr><td>").append(html(row.sourceName)).append("</td><td>").append(html(row.merchant)).append("</td><td>").append(html(row.dateIso)).append("</td><td>").append(row.amountCents == null ? "待复核" : (row.amountCents / 100.0)).append("</td><td>").append(html(row.reviewReason == null ? (row.included ? "已写入" : "重复") : row.reviewReason)).append("</td></tr>");
        return b.append("</table></div>").toString();
    }

    private static String auditSnapshot(Snapshot snapshot) {
        StringBuilder b = new StringBuilder("{\"schema\":\"quiet-receipt-audit-snapshot-v1\",\"files\":[");
        for (int i = 0; i < snapshot.images.size(); i++) { if (i > 0) b.append(','); ImageItem x = snapshot.images.get(i); b.append("{\"sourceIdHash\":").append(q(x.sourceId)).append(",\"sha256\":").append(q(x.hash)).append(",\"size\":").append(x.size).append(",\"duplicateOfIdHash\":").append(x.duplicateOf == null ? "null" : q(x.duplicateOf)).append('}'); }
        return b.append("]}").toString();
    }

    private static Bitmap decodeScaled(File file) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("无法解码图片");
        int sample = 1; while (bounds.outWidth / sample > MAX_BITMAP_DIMENSION || bounds.outHeight / sample > MAX_BITMAP_DIMENSION) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options); if (bitmap == null) throw new IOException("无法解码图片"); return bitmap;
    }

    private static Bitmap rotate(Bitmap bitmap, int orientation) {
        if (orientation < 2 || orientation > 8) return bitmap;
        Matrix matrix = new Matrix();
        switch (orientation) {
            case 2: matrix.setScale(-1, 1); break;
            case 3: matrix.setRotate(180); break;
            case 4: matrix.setScale(1, -1); break;
            case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case 6: matrix.setRotate(90); break;
            case 7: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case 8: matrix.setRotate(270); break;
            default: return bitmap;
        }
        Bitmap out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true); if (out != bitmap) bitmap.recycle(); return out;
    }

    private static int exifOrientation(File file) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            int length = (int) Math.min(128 * 1024L, in.length());
            byte[] data = new byte[length]; in.readFully(data);
            if (length < 4 || (data[0] & 255) != 255 || (data[1] & 255) != 216) return 1;
            int p = 2;
            while (p + 4 <= length) {
                if ((data[p] & 255) != 255) { p++; continue; }
                int marker = data[p + 1] & 255; p += 2;
                if (marker == 0xda || marker == 0xd9) break;
                if (p + 2 > length) break;
                int segment = u16(data, p, false); if (segment < 2 || p + segment > length) break;
                if (marker == 0xe1 && segment >= 8 && data[p + 2] == 'E' && data[p + 3] == 'x' && data[p + 4] == 'i' && data[p + 5] == 'f') {
                    int t = p + 8; boolean little = data[t] == 'I' && data[t + 1] == 'I';
                    if ((little || (data[t] == 'M' && data[t + 1] == 'M')) && u16(data, t + 2, little) == 42) {
                        int ifd = t + u32(data, t + 4, little); if (ifd >= 0 && ifd + 2 <= length) {
                            int count = u16(data, ifd, little);
                            for (int i = 0; i < count && ifd + 2 + i * 12 + 12 <= length; i++) {
                                int entry = ifd + 2 + i * 12;
                                if (u16(data, entry, little) == 0x0112 && u16(data, entry + 2, little) == 3) {
                                    int orientation = u16(data, entry + 8, little); return orientation >= 1 && orientation <= 8 ? orientation : 1;
                                }
                            }
                        }
                    }
                }
                p += segment;
            }
        } catch (Exception ignored) { }
        return 1;
    }
    private static int u16(byte[] b, int p, boolean little) { return little ? (b[p] & 255) | ((b[p + 1] & 255) << 8) : ((b[p] & 255) << 8) | (b[p + 1] & 255); }
    private static int u32(byte[] b, int p, boolean little) { long x = little ? ((long)u16(b,p,true) | ((long)u16(b,p+2,true) << 16)) : (((long)u16(b,p,false) << 16) | u16(b,p+2,false)); return (int) x; }
    private static boolean validImage(File file) throws IOException { try (InputStream in = new FileInputStream(file)) { byte[] h = new byte[8]; int n = in.read(h); return n >= 3 && (h[0] == (byte)0xff && h[1] == (byte)0xd8 && h[2] == (byte)0xff || n == 8 && h[0] == (byte)0x89 && h[1] == 0x50 && h[2] == 0x4e && h[3] == 0x47 && h[4] == 0x0d && h[5] == 0x0a && h[6] == 0x1a && h[7] == 0x0a); } }
    private static boolean isImage(String name, String mime) { String n = name == null ? "" : name.toLowerCase(Locale.ROOT); return "image/jpeg".equalsIgnoreCase(mime) || "image/png".equalsIgnoreCase(mime) || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"); }
    private static void publishPart(File part, File target) throws IOException { if (target.exists() && !target.delete()) throw new IOException("结果文件已存在"); if (!part.renameTo(target)) throw new IOException("无法发布结果文件：" + target.getName()); }
    private static void writeText(File file, String value) throws IOException { try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), BUFFER)) { out.write(value.getBytes(StandardCharsets.UTF_8)); out.flush(); } }
    private static void cleanupOutputs(File dir) { String[] names = {"archive.zip.part","archive.zip","manifest.json.part","manifest.json","receipts.csv.part","receipts.csv","summary.html.part","summary.html","audit-snapshot.part","audit-snapshot",".complete.part"}; for (String name : names) delete(new File(dir, name)); }
    private static void deleteTree(File file) { if (file == null || !file.exists()) return; File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child); delete(file); }
    private static void delete(File file) { if (file != null && file.exists()) file.delete(); }
    private static void notify(Listener l, String phase, int done, int total) { if (l != null) l.update(phase, done, total); }
    private static void checkCancelled(Cancellation c) throws IOException { if (c != null && c.isCancelled()) throw new IOException("任务已取消"); }
    private static long safeAdd(long a, long b) throws IOException { if (b < 0 || a > Long.MAX_VALUE - b) throw new IOException("文件大小计算溢出"); return a + b; }
    private static MessageDigest sha256Digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); } }
    private static String sha256(String value) { MessageDigest d = sha256Digest(); return hex(d.digest(value.getBytes(StandardCharsets.UTF_8))); }
    private static String sha256File(File file, Cancellation cancel) throws IOException { MessageDigest d = sha256Digest(); try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER)) { byte[] b = new byte[BUFFER]; int n; while ((n = in.read(b)) != -1) { checkCancelled(cancel); d.update(b, 0, n); } } return hex(d.digest()); }
    private static String hex(byte[] value) { StringBuilder b = new StringBuilder(value.length * 2); for (byte x : value) b.append(String.format(Locale.ROOT, "%02x", x & 255)); return b.toString(); }
    private static String safeId(String value) { return sha256(value).substring(0, 32); }
    private static String q(String value) { if (value == null) return "null"; return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String html(String value) { if (value == null) return ""; return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    private static final class Candidate { final Uri uri; final String path, mime; final long size; Candidate(Uri u, String p, String m, long s) { uri=u; path=p; mime=m; size=s; } }
    private static final class CopyResult { final String hash; final long size; CopyResult(String h, long s) { hash=h; size=s; } }
    private static final class ImageItem { final String sourceId, sourceName, hash, duplicateOf, mime; final long size; final File tempFile; ImageItem(String i, String n, String h, long s, File f, String m, String d) { sourceId=i;sourceName=n;hash=h;size=s;tempFile=f;mime=m;duplicateOf=d; } }
    private static final class Snapshot { final int scanned; final List<ImageItem> images, unique; Snapshot(int s, List<ImageItem> i, List<ImageItem> u) { scanned=s;images=i;unique=u; } }
}
