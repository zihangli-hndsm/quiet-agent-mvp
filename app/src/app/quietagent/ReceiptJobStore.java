package app.quietagent;

import android.content.Context;

import app.quietagent.security.AuthorizationManager;
import app.quietagent.security.TaskSpec;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Private pending state for a receipt job. URI strings are never sent to the service as arguments. */
public final class ReceiptJobStore {
    private static final String PENDING = "pending.properties";
    private static final String CONSUMED = ".consumed";
    private static final String CREDENTIAL = "credential";
    private static final int MAX_SOURCES = 30;
    private final File root;
    private final Context context;

    public ReceiptJobStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "receipt-jobs");
        if (!root.exists() && !root.mkdirs() && !root.isDirectory()) throw new IllegalStateException("无法创建票据任务目录");
    }

    public File root() { return root; }

    /** Persists the authorized task description and the selected single-file content URIs. */
    public synchronized String createPending(TaskSpec spec, String nonce, List<String> sourceUris) throws IOException {
        if (spec == null) throw new IllegalArgumentException("任务说明不能为空");
        if (nonce == null || nonce.length() < 16 || nonce.indexOf('\n') >= 0 || nonce.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("授权凭据无效");
        }
        if (sourceUris == null || sourceUris.isEmpty() || sourceUris.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("请选择1至30张照片");
        }
        List<String> cleanUris = new ArrayList<String>(sourceUris.size());
        for (String value : sourceUris) {
            if (value == null || !value.startsWith("content://") || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("照片来源必须是本地内容 URI");
            }
            cleanUris.add(value);
        }
        String id = newJobId();
        File dir = dir(id);
        if (!dir.mkdirs()) throw new IOException("无法创建票据任务目录");
        Properties props = new Properties();
        props.setProperty("id", id);
        props.setProperty("taskType", spec.type().name());
        props.setProperty("specHash", spec.specHash());
        props.setProperty("scopeHash", spec.scopeHash());
        props.setProperty("summary", spec.summary());
        props.setProperty("nonce", nonce);
        props.setProperty("sourceCount", Integer.toString(cleanUris.size()));
        for (int i = 0; i < cleanUris.size(); i++) props.setProperty("sourceUri." + i, cleanUris.get(i));
        atomicProperties(new File(dir, PENDING), props);
        SemanticFiles.adopt(context, spec.summary(), dir);
        return id;
    }

    /** The old tree-shaped API is rejected: receipt jobs must use the authorized picker list. */
    @Deprecated
    public synchronized String createPending(String rawUri, String summary) {
        throw new IllegalArgumentException("票据任务必须通过 createPending(spec, nonce, sourceUris) 创建");
    }

    /** Consumes the persisted one-shot authorization without requiring URI arguments. */
    public synchronized void consume(String jobId, AuthorizationManager authorization) throws IOException {
        if (authorization == null) throw new IllegalArgumentException("授权管理器不能为空");
        Pending pending = readPending(jobId);
        consume(jobId, pending.spec, pending.nonce, authorization);
    }

    /** Consumes the persisted one-shot authorization and checks the caller's preview values. */
    public synchronized void consume(String jobId, TaskSpec spec, String nonce, AuthorizationManager authorization) throws IOException {
        if (authorization == null) throw new IllegalArgumentException("授权管理器不能为空");
        File dir = dirChecked(jobId);
        Pending pending = readPending(jobId);
        if (!pending.spec.specHash().equals(spec == null ? "" : spec.specHash()) || !pending.nonce.equals(nonce)) {
            throw new IOException("授权任务与已保存选择不一致");
        }
        if (new File(dir, CONSUMED).isFile()) throw new IOException("票据任务授权已消费");
        // The unbound consume API is intentionally not used.
        authorization.consume(jobId, pending.spec, pending.nonce);
        writeTextAtomic(new File(dir, CONSUMED), pending.spec.specHash() + "\n" + System.currentTimeMillis() + "\n");
    }

    /** Records the final ZIP digest, then exports the redacted credential outside the ZIP. */
    public synchronized void recordOutputAndPublishCredential(String jobId, String archiveSha256,
                                                               AuthorizationManager authorization) throws IOException {
        if (authorization == null) throw new IllegalArgumentException("授权管理器不能为空");
        if (archiveSha256 == null || !archiveSha256.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("归档摘要无效");
        File dir = dirChecked(jobId);
        if (!new File(dir, CONSUMED).isFile()) throw new IOException("票据任务尚未获得授权");
        Pending pending = readMetadata(jobId);
        authorization.recordOutput(jobId, pending.spec, pending.nonce, archiveSha256.toLowerCase(java.util.Locale.ROOT));
        writeTextAtomic(new File(dir, CREDENTIAL), authorization.exportAuditCredential());
    }

    /** Service entry point: URI strings are returned only after the consumed marker exists. */
    public synchronized Pending readForService(String jobId) throws IOException {
        File dir = dirChecked(jobId);
        if (!new File(dir, CONSUMED).isFile()) throw new IOException("票据任务尚未获得授权");
        return readPending(jobId);
    }

    /** Reads persisted spec/nonce for service authorization; opens no source URI. */
    public synchronized Pending readPendingForAuthorization(String jobId) throws IOException {
        return readPending(jobId);
    }

    public synchronized File jobDir(String jobId) throws IOException { return dirChecked(jobId); }

    /** Removes all raw URI strings immediately after the copied snapshot is complete. */
    public synchronized void clearRawUri(String jobId) throws IOException {
        File file = new File(dirChecked(jobId), PENDING);
        Properties props = readProperties(file);
        int count = parseCount(props);
        for (int i = 0; i < count; i++) props.remove("sourceUri." + i);
        atomicProperties(file, props);
    }

    public synchronized String credential(String jobId) throws IOException {
        File file = new File(dirChecked(jobId), CREDENTIAL);
        if (!file.isFile()) throw new IOException("授权凭据不存在");
        return readText(file);
    }

    /** Records an allowed post-completion audit event and refreshes the outer credential. */
    public synchronized void recordEventAndPublishCredential(String jobId, String event,
                                                              AuthorizationManager authorization) throws IOException {
        if (authorization == null) throw new IllegalArgumentException("授权管理器不能为空");
        File dir = dirChecked(jobId);
        if (!new File(dir, ".complete").isFile()) throw new IOException("票据任务尚未完成");
        Pending pending = readMetadata(jobId);
        authorization.recordEvent(jobId, pending.spec, pending.nonce, event);
        writeTextAtomic(new File(dir, CREDENTIAL), authorization.exportAuditCredential());
    }

    /** Deletes exactly one validated app-private job directory. */
    public synchronized void deletePrivateJob(String jobId) throws IOException {
        File dir = dirChecked(jobId);
        deleteTreeChecked(dir);
        if (dir.exists()) throw new IOException("无法清除任务本地数据");
    }

    /** Cleans a hard-interrupted job on the next app start while retaining a redacted credential. */
    public synchronized void recoverInterrupted(String jobId, AuthorizationManager authorization) throws IOException {
        File dir = dirChecked(jobId);
        if (new File(dir, ".complete").isFile()) return;
        Pending pending = readMetadata(jobId);
        try { clearRawUri(jobId); } catch (Exception ignored) { }
        File temp = new File(dir, ".receipt-temp");
        if (temp.exists()) deleteTreeChecked(temp);
        File semantic = new File(dir, ".semantic");
        if (semantic.exists()) deleteTreeChecked(semantic);
        cleanupUnpublished(jobId);
        try {
            authorization.recordEvent(jobId, pending.spec, pending.nonce, AuthorizationManager.INTERRUPTED);
        } catch (Exception ignored) { }
        writeTextAtomic(new File(dir, CREDENTIAL), authorization.exportAuditCredential());
    }

    /** Publishes the shareable completion marker only after output recording and credential export. */
    public synchronized void publishComplete(String jobId, String archiveSha256) throws IOException {
        if (archiveSha256 == null || !archiveSha256.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("归档摘要无效");
        File dir = dirChecked(jobId);
        if (!new File(dir, CONSUMED).isFile() || !new File(dir, CREDENTIAL).isFile()) {
            throw new IOException("授权输出尚未记录");
        }
        File archive = new File(dir, "archive.zip");
        if (!archive.isFile()) throw new IOException("归档文件不存在");
        if (!archiveSha256.equalsIgnoreCase(sha256File(archive))) throw new IOException("归档摘要与文件不一致");
        File marker = new File(dir, ".complete");
        if (marker.exists()) throw new IOException("票据任务已经完成");
        writeTextAtomic(new File(dir, ".complete.part"), archiveSha256.toLowerCase(java.util.Locale.ROOT) + "\n");
        if (!new File(dir, ".complete.part").renameTo(marker)) throw new IOException("无法发布票据完成标记");
    }

    /** Removes unpublished result files after a final authorization/output failure. */
    public synchronized void cleanupUnpublished(String jobId) throws IOException {
        File dir = dirChecked(jobId);
        if (new File(dir, ".complete").isFile()) throw new IOException("已完成任务不能清理");
        for (String temp : new String[]{"archive-output", ".semantic"}) {
            File staged = new File(dir, temp);
            if (staged.exists()) deleteTreeChecked(staged);
        }
        String[] names = {"archive.zip", "archive.zip.part", "manifest.json", "manifest.json.part",
                "receipts.csv", "receipts.csv.part", "summary.html", "summary.html.part",
                "audit-snapshot", "audit-snapshot.part", ".complete.part", "credential"};
        for (String name : names) {
            File file = new File(dir, name);
            if (file.exists() && !file.delete()) throw new IOException("无法清理票据任务文件：" + name);
        }
    }

    /** Returns an output only after the final completion marker is present. */
    public synchronized File resultFile(String jobId, String name) throws IOException {
        File dir = dirChecked(jobId);
        if (!new File(dir, ".complete").isFile()) throw new IOException("票据任务尚未完成");
        if (!("archive.zip".equals(name) || "manifest.json".equals(name) || "receipts.csv".equals(name)
                || "summary.html".equals(name) || "audit-snapshot".equals(name) || "credential".equals(name))) {
            throw new IOException("不允许读取该票据结果文件");
        }
        File result = new File(dir, name);
        if (!result.isFile()) throw new IOException("票据结果文件不存在");
        return result;
    }

    private Pending readPending(String jobId) throws IOException {
        File file = new File(dirChecked(jobId), PENDING);
        Properties props = readProperties(file);
        if (!jobId.equals(props.getProperty("id"))) throw new IOException("票据任务编号不匹配");
        Pending metadata = readMetadata(props, jobId);
        int count = parseCount(props);
        List<String> uris = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            String value = props.getProperty("sourceUri." + i);
            if (value == null || !value.startsWith("content://")) throw new IOException("票据任务来源已损坏");
            uris.add(value);
        }
        return new Pending(jobId, metadata.spec, metadata.nonce, uris);
    }

    private Pending readMetadata(String jobId) throws IOException {
        Properties props = readProperties(new File(dirChecked(jobId), PENDING));
        return readMetadata(props, jobId);
    }

    private static Pending readMetadata(Properties props, String jobId) throws IOException {
        String typeText = props.getProperty("taskType");
        TaskSpec.TaskType type;
        try { type = TaskSpec.TaskType.valueOf(typeText); }
        catch (Exception error) { throw new IOException("票据任务类型已损坏", error); }
        final TaskSpec spec;
        try { spec = TaskSpec.restore(type, props.getProperty("scopeHash"), props.getProperty("summary"), props.getProperty("specHash")); }
        catch (RuntimeException error) { throw new IOException("票据任务说明已损坏", error); }
        String nonce = props.getProperty("nonce");
        if (nonce == null || nonce.length() < 16) throw new IOException("授权凭据已损坏");
        return new Pending(jobId, spec, nonce, Collections.<String>emptyList());
    }

    private static int parseCount(Properties props) throws IOException {
        int count;
        try { count = Integer.parseInt(props.getProperty("sourceCount", "0")); }
        catch (NumberFormatException error) { throw new IOException("票据任务数量已损坏", error); }
        if (count < 1 || count > MAX_SOURCES) throw new IOException("票据任务数量已损坏");
        return count;
    }

    private File dirChecked(String id) throws IOException {
        if (id == null || !id.matches("receipt-[0-9]+-[a-f0-9]{8}")) throw new IOException("无效票据任务编号");
        File dir = new File(root, id).getCanonicalFile();
        if (!root.getCanonicalFile().equals(dir.getParentFile())) throw new IOException("票据任务路径越界");
        if (!dir.isDirectory()) throw new IOException("票据任务不存在");
        return dir;
    }

    private File dir(String id) { return new File(root, id); }
    private static String newJobId() { return "receipt-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8); }

    private static Properties readProperties(File file) throws IOException {
        if (!file.isFile()) throw new IOException("票据任务规格不存在");
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) { props.load(in); }
        return props;
    }

    private static String readText(File file) throws IOException {
        if (file.length() > 1024 * 1024) throw new IOException("票据任务凭据过大");
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0, n;
            while (offset < data.length && (n = in.read(data, offset, data.length - offset)) != -1) offset += n;
            return new String(data, 0, offset, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String sha256File(File file) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IOException("系统不支持 SHA-256", error); }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private static void deleteTreeChecked(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) deleteTreeChecked(child);
            else if (!child.delete()) throw new IOException("无法清除任务文件");
        }
        if (!file.delete()) throw new IOException("无法清除任务目录");
    }

    private static void atomicProperties(File file, Properties props) throws IOException {
        File part = new File(file.getParentFile(), file.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) { props.store(out, null); out.getFD().sync(); }
        if (file.exists() && !file.delete()) throw new IOException("无法替换票据任务规格");
        if (!part.renameTo(file)) throw new IOException("无法发布票据任务规格");
    }

    private static void writeTextAtomic(File file, String value) throws IOException {
        File part = new File(file.getParentFile(), file.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.getFD().sync();
        }
        if (file.exists() && !file.delete()) throw new IOException("无法替换票据任务文件");
        if (!part.renameTo(file)) throw new IOException("无法发布票据任务文件");
    }

    public static final class Pending {
        public final String jobId;
        public final TaskSpec spec;
        public final String nonce;
        public final List<String> sourceUris;
        /** Compatibility convenience for callers that expect exactly one source. */
        public final String sourceUri;
        public final String summary;
        Pending(String id, TaskSpec task, String value, List<String> uris) {
            jobId = id; spec = task; nonce = value;
            sourceUris = Collections.unmodifiableList(new ArrayList<String>(uris));
            sourceUri = sourceUris.isEmpty() ? null : sourceUris.get(0); summary = task.summary();
        }
    }
}
