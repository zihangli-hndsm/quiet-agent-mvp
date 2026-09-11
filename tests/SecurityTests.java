import app.quietagent.security.Authorization;
import app.quietagent.security.AuthorizationException;
import app.quietagent.security.AuthorizationManager;
import app.quietagent.security.MemoryStateStore;
import app.quietagent.security.TaskSpec;

/** Dependency-free JVM checks for v0.2 authorization and audit invariants. */
public final class SecurityTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        testImmutableSpecAndTwoTypes();
        testOneShotAndSpecInvalidation();
        testExpiryAndOutputBinding();
        testWriteFailureBlocksAuthorization();
        testLifecycleEventStateMachine();
        testFailureAndInterruptionLifecycle();
        testJobBinding();
        testCorruptStateFailsClosedAndTabSafe();
        System.out.println("SecurityTests OK (" + assertions + " assertions)");
    }

    private static void testImmutableSpecAndTwoTypes() {
        TaskSpec archive = TaskSpec.archive("content://private/tree/one", "按类型归档");
        TaskSpec ocr = TaskSpec.ocr("content://private/tree/one", "离线 OCR 摘要");
        check(archive.type() == TaskSpec.FILE_ARCHIVE, "archive type");
        check(ocr.type() == TaskSpec.OCR_TASK, "OCR type");
        check(!archive.specHash().equals(ocr.specHash()), "type changes spec hash");
        check(!archive.immutableSummary().contains("content://private"), "summary omits raw scope");
        check(archive.summary().equals("按类型归档"), "summary is stable");
    }

    private static void testOneShotAndSpecInvalidation() throws Exception {
        MemoryStateStore store = new MemoryStateStore();
        FixedClock clock = new FixedClock(1000L);
        AuthorizationManager manager = manager(store, clock);
        TaskSpec first = TaskSpec.archive("tree-a", "去重并归档");
        TaskSpec changed = TaskSpec.archive("tree-a", "按月份归档");
        Authorization a = manager.authorize(first);
        Authorization b = manager.authorize(changed);
        expectReason(() -> manager.consume(first, a.nonce()), AuthorizationException.Reason.SPEC_MISMATCH, "changed spec invalidates old nonce");
        manager.consume(changed, b.nonce());
        expectReason(() -> manager.consume(changed, b.nonce()), AuthorizationException.Reason.REPLAYED, "nonce is one-shot");
        String audit = manager.exportAuditCredential();
        check(audit.contains("AUTHORIZED") && audit.contains("INVALIDATED") && audit.contains("CONSUMED"), "audit lifecycle events");
    }

    private static void testExpiryAndOutputBinding() throws Exception {
        MemoryStateStore store = new MemoryStateStore();
        FixedClock clock = new FixedClock(10L);
        AuthorizationManager manager = new AuthorizationManager(store, clock, new AuthorizationManager.NonceSource() {
            public String nextNonce() { return "nonce-expiry-0000000000000000"; }
        }, 5L);
        TaskSpec spec = TaskSpec.ocr("sensitive-document-id", "OCR 摘要");
        Authorization auth = manager.authorize(spec);
        clock.value = 16L;
        expectReason(() -> manager.consume(spec, auth.nonce()), AuthorizationException.Reason.EXPIRED, "expired authorization");

        clock.value = 20L;
        Authorization fresh = manager.authorize(spec);
        manager.consume(spec, fresh.nonce());
        String outputHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        manager.recordOutput(spec, fresh.nonce(), outputHash);
        String audit = manager.exportAuditCredential();
        check(audit.contains(outputHash), "output hash is auditable");
        check(!audit.contains("sensitive-document-id") && !audit.contains("OCR 摘要"), "audit contains no raw sensitive fields");
        expectReason(() -> manager.recordOutput(spec, "wrong", outputHash), AuthorizationException.Reason.SPEC_MISMATCH, "output requires consumed nonce");
    }

    private static void testWriteFailureBlocksAuthorization() throws Exception {
        MemoryStateStore store = new MemoryStateStore();
        store.setFailWrites(true);
        AuthorizationManager manager = manager(store, new FixedClock(100L));
        expectReason(() -> manager.authorize(TaskSpec.archive("scope", "safe summary")), AuthorizationException.Reason.PERSISTENCE, "write failure blocks authorization");
        check(store.value().isEmpty(), "failed write leaves no authorization");
        store.setFailWrites(false);
        Authorization auth = manager.authorize(TaskSpec.archive("scope", "safe summary"));
        check(auth.nonce() != null && !auth.nonce().isEmpty(), "authorization is issued after durable write");
    }

    private static void testLifecycleEventStateMachine() throws Exception {
        MemoryStateStore store = new MemoryStateStore();
        FixedClock clock = new FixedClock(100L);
        AuthorizationManager manager = manager(store, clock);
        TaskSpec spec = TaskSpec.ocr("photo-scope", "离线票据整理");
        Authorization auth = manager.authorize(spec);

        expectReason(() -> manager.recordEvent(spec, auth.nonce(), AuthorizationManager.CANCEL_REQUESTED),
                AuthorizationException.Reason.SPEC_MISMATCH, "event requires consumed authorization");
        manager.consume(spec, auth.nonce());
        manager.recordEvent(spec, auth.nonce(), AuthorizationManager.CANCEL_REQUESTED);
        expectReason(() -> manager.recordEvent(spec, auth.nonce(), AuthorizationManager.CANCEL_REQUESTED),
                AuthorizationException.Reason.INVALID, "cancel request is one-shot");
        manager.recordEvent(spec, auth.nonce(), AuthorizationManager.CANCELLED);
        expectReason(() -> manager.recordEvent(spec, auth.nonce(), AuthorizationManager.FAILED),
                AuthorizationException.Reason.INVALID, "terminal state rejects another terminal event");
        expectReason(() -> manager.recordOutput(spec, auth.nonce(), validHash('b')),
                AuthorizationException.Reason.INVALID, "cancelled task cannot publish output");
        expectReason(() -> manager.recordEvent(spec, auth.nonce(), "RAW_OCR_TEXT"),
                AuthorizationException.Reason.INVALID, "arbitrary event is rejected");
        String audit = manager.exportAuditCredential();
        check(audit.contains("CANCEL_REQUESTED") && audit.contains("CANCELLED"), "cancel lifecycle is auditable");
        check(!audit.contains("photo-scope") && !audit.contains("票据整理"), "event audit contains no raw task fields");

        MemoryStateStore successfulStore = new MemoryStateStore();
        AuthorizationManager successful = manager(successfulStore, new FixedClock(200L));
        Authorization successfulAuth = successful.authorize(spec);
        successful.consume(spec, successfulAuth.nonce());
        expectReason(() -> successful.recordEvent(spec, successfulAuth.nonce(), AuthorizationManager.EXPORT_CONFIRMED),
                AuthorizationException.Reason.INVALID, "export confirmation requires output");
        successful.recordOutput(spec, successfulAuth.nonce(), validHash('a'));
        expectReason(() -> successful.recordOutput(spec, successfulAuth.nonce(), validHash('a')),
                AuthorizationException.Reason.REPLAYED, "output is one-shot");
        expectReason(() -> successful.recordOutput(spec, successfulAuth.nonce(), "not-a-digest"),
                AuthorizationException.Reason.REPLAYED, "second output cannot bypass one-shot gate");
        successful.recordEvent(spec, successfulAuth.nonce(), AuthorizationManager.EXPORT_CONFIRMED);
        successful.recordEvent(spec, successfulAuth.nonce(), AuthorizationManager.SHARE_SHEET_OPENED);
        successful.recordEvent(spec, successfulAuth.nonce(), AuthorizationManager.EXPORT_CONFIRMED);
        successful.recordEvent(spec, successfulAuth.nonce(), AuthorizationManager.SHARE_SHEET_OPENED);
        successful.recordEvent(spec, successfulAuth.nonce(), AuthorizationManager.CLEARED);
        expectReason(() -> successful.recordEvent(spec, successfulAuth.nonce(), AuthorizationManager.SHARE_SHEET_OPENED),
                AuthorizationException.Reason.INVALID, "share after clear is rejected");
        check(successful.exportAuditCredential().contains("EXPORT_CONFIRMED")
                && successful.exportAuditCredential().contains("SHARE_SHEET_OPENED")
                && successful.exportAuditCredential().contains("CLEARED"), "export lifecycle is auditable");
    }

    private static void testFailureAndInterruptionLifecycle() throws Exception {
        TaskSpec spec = TaskSpec.archive("tree", "本地归档");
        MemoryStateStore failedStore = new MemoryStateStore();
        AuthorizationManager failed = manager(failedStore, new FixedClock(300L));
        Authorization a = failed.authorize(spec); failed.consume(spec, a.nonce());
        failed.recordEvent(spec, a.nonce(), AuthorizationManager.FAILED);
        failed.recordEvent(spec, a.nonce(), AuthorizationManager.CLEARED);
        check(failed.exportAuditCredential().contains("FAILED") && failed.exportAuditCredential().contains("CLEARED"), "failed task can be cleared");

        MemoryStateStore interruptedStore = new MemoryStateStore();
        AuthorizationManager interrupted = manager(interruptedStore, new FixedClock(400L));
        Authorization b = interrupted.authorize(spec); interrupted.consume(spec, b.nonce());
        interrupted.recordEvent(spec, b.nonce(), AuthorizationManager.INTERRUPTED);
        expectReason(() -> interrupted.recordEvent(spec, b.nonce(), AuthorizationManager.CANCEL_REQUESTED),
                AuthorizationException.Reason.INVALID, "interrupted task is terminal");
        interrupted.recordEvent(spec, b.nonce(), AuthorizationManager.CLEARED);
        check(interrupted.exportAuditCredential().contains("INTERRUPTED"), "interrupted task is auditable");
    }

    private static void testJobBinding() throws Exception {
        MemoryStateStore store = new MemoryStateStore();
        AuthorizationManager manager = manager(store, new FixedClock(450L));
        TaskSpec spec = TaskSpec.ocr("photo-scope", "票据识别");
        Authorization auth = manager.authorize(spec);
        manager.consume("job-a", spec, auth.nonce());
        expectReason(() -> manager.recordEvent(spec, auth.nonce(), AuthorizationManager.FAILED),
                AuthorizationException.Reason.SPEC_MISMATCH, "legacy event API cannot cross a job-bound task");
        expectReason(() -> manager.recordEvent("job-b", spec, auth.nonce(), AuthorizationManager.FAILED),
                AuthorizationException.Reason.SPEC_MISMATCH, "wrong job cannot record event");
        manager.recordEvent("job-a", spec, auth.nonce(), AuthorizationManager.FAILED);
        expectReason(() -> manager.recordOutput("job-a", spec, auth.nonce(), validHash('c')),
                AuthorizationException.Reason.INVALID, "failed job cannot publish output");
        String audit = manager.exportAuditCredential();
        check(audit.contains("jobIdHash") && !audit.contains("job-a") && !audit.contains("job-b"), "job id is hashed in audit");
        check(audit.contains(spec.specHash()), "spec hash is auditable");

        MemoryStateStore outputStore = new MemoryStateStore();
        AuthorizationManager outputManager = manager(outputStore, new FixedClock(460L));
        Authorization outputAuth = outputManager.authorize(spec);
        outputManager.consume("job-output", spec, outputAuth.nonce());
        outputManager.recordOutput("job-output", spec, outputAuth.nonce(), validHash('d'));
        String outputAudit = outputManager.exportAuditCredential();
        check(outputAudit.contains("OUTPUT") && outputAudit.contains("jobIdHash"), "output event keeps job binding");
    }

    private static void testCorruptStateFailsClosedAndTabSafe() throws Exception {
        MemoryStateStore store = new MemoryStateStore();
        store.write("quiet-security-v1\ncurrent\tbad\tbad\tbad\t1\t2\ttrue\n");
        AuthorizationManager manager = manager(store, new FixedClock(500L));
        TaskSpec spec = TaskSpec.archive("scope", "summary");
        expectReason(() -> manager.authorize(spec), AuthorizationException.Reason.PERSISTENCE, "invalid hashes fail closed");

        MemoryStateStore duplicate = new MemoryStateStore();
        AuthorizationManager clean = manager(duplicate, new FixedClock(510L));
        Authorization auth = clean.authorize(spec);
        String state = duplicate.value();
        String currentLine = state.split("\\r?\\n")[1];
        duplicate.write(state + currentLine + "\n");
        expectReason(() -> clean.exportAuditCredential(), AuthorizationException.Reason.PERSISTENCE, "duplicate current fails closed");

        MemoryStateStore whitespace = new MemoryStateStore();
        whitespace.write(" \t\n");
        AuthorizationManager whitespaceManager = manager(whitespace, new FixedClock(520L));
        expectReason(() -> whitespaceManager.exportAuditCredential(), AuthorizationException.Reason.PERSISTENCE, "blank persisted state fails closed");

        MemoryStateStore safe = new MemoryStateStore();
        AuthorizationManager safeManager = manager(safe, new FixedClock(530L));
        Authorization tabSafe = safeManager.authorize(TaskSpec.archive("scope\twith\nseparator", "摘要\t带换行\n"));
        String audit = safeManager.exportAuditCredential();
        check(!audit.contains("scope\twith") && !audit.contains("摘要\t带换行"), "tab/newline fields are sanitized or hashed");
        check(tabSafe.specHash().matches("[0-9a-f]{64}"), "spec hashes remain canonical");
    }

    private static AuthorizationManager manager(MemoryStateStore store, FixedClock clock) {
        return new AuthorizationManager(store, clock, new AuthorizationManager.NonceSource() {
            private int count;
            public String nextNonce() { return "nonce-test-" + (++count) + "-0000000000000000"; }
        }, 60_000L);
    }

    private static void expectReason(Throwing action, AuthorizationException.Reason reason, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError(message + " (did not fail)");
        } catch (AuthorizationException expected) {
            check(expected.reason() == reason, message + " reason");
        }
    }

    private static String validHash(char c) {
        StringBuilder b = new StringBuilder(64);
        for (int i = 0; i < 64; i++) b.append(c);
        return b.toString();
    }

    private interface Throwing { void run() throws Exception; }

    private static final class FixedClock implements AuthorizationManager.Clock {
        long value;
        FixedClock(long value) { this.value = value; }
        public long now() { return value; }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        assertions++;
    }
}
