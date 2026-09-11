package app.quietagent.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Immutable, non-sensitive description of one authorized task. */
public final class TaskSpec {
    public enum TaskType { ARCHIVE, OCR }

    /** Aliases make integration readable while retaining exactly two task types. */
    public static final TaskType FILE_ARCHIVE = TaskType.ARCHIVE;
    public static final TaskType OCR_TASK = TaskType.OCR;

    private final TaskType type;
    private final String scopeHash;
    private final String summary;
    private final String specHash;

    private TaskSpec(TaskType type, String scopeHash, String summary) {
        this.type = type;
        this.scopeHash = scopeHash;
        this.summary = cleanSummary(summary);
        this.specHash = sha256(type.name() + "\n" + scopeHash + "\n" + this.summary);
    }

    public static TaskSpec of(TaskType type, String scope, String summary) {
        if (type == null) throw new IllegalArgumentException("task type is required");
        if (scope == null || scope.trim().isEmpty()) throw new IllegalArgumentException("scope is required");
        return new TaskSpec(type, sha256(scope), summary);
    }

    public static TaskSpec archive(String scope, String summary) { return of(TaskType.ARCHIVE, scope, summary); }
    public static TaskSpec ocr(String scope, String summary) { return of(TaskType.OCR, scope, summary); }

    /** Restores a persisted non-sensitive task description after its raw scope has been cleared. */
    public static TaskSpec restore(TaskType type, String scopeHash, String summary, String expectedSpecHash) {
        if (type == null || scopeHash == null || !scopeHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("stored task scope is invalid");
        }
        if (expectedSpecHash == null || !expectedSpecHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("stored task hash is invalid");
        }
        TaskSpec restored = new TaskSpec(type, scopeHash.toLowerCase(Locale.ROOT), summary);
        if (!restored.specHash.equals(expectedSpecHash.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("stored task hash does not match");
        }
        return restored;
    }

    public TaskType type() { return type; }
    public String scopeHash() { return scopeHash; }
    public String summary() { return summary; }
    public String specHash() { return specHash; }

    /** Stable canonical summary for UI/audit callers; it contains no raw scope. */
    public String immutableSummary() {
        return type.name().toLowerCase(Locale.ROOT) + " · " + summary + " · scope#" + scopeHash.substring(0, 12);
    }

    private static String cleanSummary(String value) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("summary is required");
        String clean = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (clean.isEmpty() || clean.length() > 256) throw new IllegalArgumentException("summary length must be 1..256");
        return clean;
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
