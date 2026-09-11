package app.quietagent.security;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Issues one-shot authorizations and appends a privacy-preserving audit trail.
 * No raw OCR text, source URI, request, or other sensitive field is persisted.
 */
public final class AuthorizationManager {
    public interface Clock { long now(); }
    public interface NonceSource { String nextNonce(); }

    private static final String VERSION = "quiet-security-v1";
    private static final long DEFAULT_TTL = 10 * 60 * 1000L;
    public static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String EXPORT_CONFIRMED = "EXPORT_CONFIRMED";
    public static final String SHARE_SHEET_OPENED = "SHARE_SHEET_OPENED";
    public static final String CLEARED = "CLEARED";
    private static final Set<String> EVENT_ALLOWLIST = new HashSet<String>(Arrays.asList(
            "INVALIDATED", "AUTHORIZED", "CONSUMED", "OUTPUT",
            CANCEL_REQUESTED, CANCELLED, FAILED, INTERRUPTED,
            EXPORT_CONFIRMED, SHARE_SHEET_OPENED, CLEARED));
    private final StateStore store;
    private final Clock clock;
    private final NonceSource nonceSource;
    private final long ttlMillis;

    public AuthorizationManager(StateStore store) {
        this(store, new Clock() { public long now() { return System.currentTimeMillis(); } },
                new NonceSource() {
                    private final SecureRandom random = new SecureRandom();
                    public String nextNonce() {
                        byte[] bytes = new byte[32];
                        random.nextBytes(bytes);
                        StringBuilder out = new StringBuilder(64);
                        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
                        return out.toString();
                    }
                }, DEFAULT_TTL);
    }

    public AuthorizationManager(StateStore store, Clock clock, NonceSource nonceSource, long ttlMillis) {
        if (store == null || clock == null || nonceSource == null) throw new IllegalArgumentException("security dependencies are required");
        if (ttlMillis <= 0) throw new IllegalArgumentException("authorization TTL must be positive");
        this.store = store;
        this.clock = clock;
        this.nonceSource = nonceSource;
        this.ttlMillis = ttlMillis;
    }

    /** Persist-before-return: a failed write never yields a usable authorization. */
    public synchronized Authorization authorize(TaskSpec spec) throws AuthorizationException {
        if (spec == null) throw failure(AuthorizationException.Reason.INVALID, "spec is required");
        SecurityState old = load();
        long now = clock.now();
        String nonce = nonceSource.nextNonce();
        if (nonce == null || nonce.length() < 16) throw failure(AuthorizationException.Reason.INVALID, "nonce source returned an invalid nonce");
        Authorization authorization = new Authorization(nonce, spec.specHash(), spec.scopeHash(), now, now + ttlMillis);
        SecurityState next = old.copy();
        if (old.current != null && !old.current.consumed) {
            next.events.add(AuditEvent.of("INVALIDATED", now, old.current.specHash, old.current.scopeHash, old.current.nonceHash, ""));
        }
        next.current = Current.from(authorization);
        next.events.add(AuditEvent.of("AUTHORIZED", now, spec.specHash(), spec.scopeHash(), hash(nonce), ""));
        persist(next);
        return authorization;
    }

    /** Atomically consumes the nonce and records the authorization event. */
    public synchronized void consume(TaskSpec spec, String nonce) throws AuthorizationException {
        consumeWithJobHash("", spec, nonce);
    }

    /** Atomically consumes the nonce and binds all subsequent events to a job id hash. */
    public synchronized void consume(String jobId, TaskSpec spec, String nonce) throws AuthorizationException {
        if (jobId == null || jobId.trim().isEmpty()) throw failure(AuthorizationException.Reason.INVALID, "job id is required");
        consumeWithJobHash(hash(jobId), spec, nonce);
    }

    private void consumeWithJobHash(String jobIdHash, TaskSpec spec, String nonce) throws AuthorizationException {
        if (spec == null || nonce == null || nonce.isEmpty()) throw failure(AuthorizationException.Reason.INVALID, "spec and nonce are required");
        SecurityState state = load();
        Current current = state.current;
        if (current == null || !current.specHash.equals(spec.specHash()) || !current.scopeHash.equals(spec.scopeHash())) {
            throw failure(AuthorizationException.Reason.SPEC_MISMATCH, "authorization does not match the current spec");
        }
        if (current.consumed || !current.nonceHash.equals(hash(nonce))) {
            throw failure(AuthorizationException.Reason.REPLAYED, "authorization nonce was already used or is invalid");
        }
        long now = clock.now();
        if (now > current.expiresAt) throw failure(AuthorizationException.Reason.EXPIRED, "authorization has expired");
        SecurityState next = state.copy();
        next.current = current.withConsumed(jobIdHash);
        next.events.add(AuditEvent.of("CONSUMED", now, current.specHash, current.scopeHash, current.nonceHash, jobIdHash, ""));
        persist(next);
    }

    /** Records only a validated output digest after a consumed authorization. */
    public synchronized void recordOutput(TaskSpec spec, String nonce, String outputHash) throws AuthorizationException {
        recordOutputWithJobHash("", spec, nonce, outputHash);
    }

    /** Records output while requiring the job id bound at consume time. */
    public synchronized void recordOutput(String jobId, TaskSpec spec, String nonce, String outputHash) throws AuthorizationException {
        if (jobId == null || jobId.trim().isEmpty()) throw failure(AuthorizationException.Reason.INVALID, "job id is required");
        recordOutputWithJobHash(hash(jobId), spec, nonce, outputHash);
    }

    private void recordOutputWithJobHash(String jobIdHash, TaskSpec spec, String nonce, String outputHash) throws AuthorizationException {
        if (spec == null || nonce == null || outputHash == null) throw failure(AuthorizationException.Reason.INVALID, "output record fields are required");
        SecurityState state = load();
        Current current = requireConsumedCurrent(state, jobIdHash, spec, nonce);
        if (hasEvent(state, current, "OUTPUT")) throw failure(AuthorizationException.Reason.REPLAYED, "output was already recorded");
        if (hasTerminalEvent(state, current)) throw failure(AuthorizationException.Reason.INVALID, "output cannot follow a terminal task event");
        String safeOutputHash = normalizeHash(outputHash);
        SecurityState next = state.copy();
        next.events.add(AuditEvent.of("OUTPUT", clock.now(), current.specHash, current.scopeHash, current.nonceHash, current.jobIdHash, safeOutputHash));
        persist(next);
    }

    /**
     * Records a lifecycle event bound to the currently consumed task and nonce.
     * Event names are a closed set; callers cannot write arbitrary audit text.
     * Export and share events require a successfully recorded output digest.
     */
    public synchronized void recordEvent(TaskSpec spec, String nonce, String event) throws AuthorizationException {
        recordEventWithJobHash("", spec, nonce, event);
    }

    /** Records a lifecycle event and requires the job id bound at consume time. */
    public synchronized void recordEvent(String jobId, TaskSpec spec, String nonce, String event) throws AuthorizationException {
        if (jobId == null || jobId.trim().isEmpty()) throw failure(AuthorizationException.Reason.INVALID, "job id is required");
        recordEventWithJobHash(hash(jobId), spec, nonce, event);
    }

    private void recordEventWithJobHash(String jobIdHash, TaskSpec spec, String nonce, String event) throws AuthorizationException {
        if (spec == null || nonce == null || nonce.isEmpty() || event == null) {
            throw failure(AuthorizationException.Reason.INVALID, "event fields are required");
        }
        if (!EVENT_ALLOWLIST.contains(event) || "INVALIDATED".equals(event) || "AUTHORIZED".equals(event)
                || "CONSUMED".equals(event) || "OUTPUT".equals(event)) {
            throw failure(AuthorizationException.Reason.INVALID, "event is not writable");
        }
        SecurityState state = load();
        Current current = requireConsumedCurrent(state, jobIdHash, spec, nonce);
        if (!canRecordEvent(state, current, event)) {
            throw failure(AuthorizationException.Reason.INVALID, "event is not valid for the current task state");
        }
        SecurityState next = state.copy();
        next.events.add(AuditEvent.of(event, clock.now(), current.specHash, current.scopeHash, current.nonceHash, current.jobIdHash, ""));
        persist(next);
    }

    /** JSON audit credential contains hashes, event names, timestamps, and output hashes only. */
    public synchronized String exportAuditCredential() throws AuthorizationException {
        SecurityState state = load();
        StringBuilder json = new StringBuilder(512);
        json.append("{\"schema\":\"").append(VERSION).append("\",\"events\":[");
        for (int i = 0; i < state.events.size(); i++) {
            if (i > 0) json.append(',');
            json.append(state.events.get(i).json());
        }
        return json.append("]}").toString();
    }

    private SecurityState load() throws AuthorizationException {
        try { return SecurityState.parse(store.read()); }
        catch (Exception error) { throw failure(AuthorizationException.Reason.PERSISTENCE, "无法读取授权审计状态"); }
    }

    private void persist(SecurityState state) throws AuthorizationException {
        try { store.write(state.serialize()); }
        catch (IOException error) { throw failure(AuthorizationException.Reason.PERSISTENCE, "无法保存授权审计状态"); }
    }

    private static AuthorizationException failure(AuthorizationException.Reason reason, String message) {
        return new AuthorizationException(reason, message);
    }

    private static String hash(String value) { return TaskSpec.sha256(value); }

    private static String normalizeHash(String value) throws AuthorizationException {
        String trim = value.trim().toLowerCase(Locale.ROOT);
        if (!trim.matches("[0-9a-f]{64}")) {
            throw failure(AuthorizationException.Reason.INVALID, "output hash must be a SHA-256 digest");
        }
        return trim;
    }

    private static Current requireConsumedCurrent(SecurityState state, String jobIdHash, TaskSpec spec, String nonce) throws AuthorizationException {
        Current current = state.current;
        if (current == null || !current.consumed || !current.specHash.equals(spec.specHash())
                || !current.scopeHash.equals(spec.scopeHash()) || !current.nonceHash.equals(hash(nonce))
                || !current.jobIdHash.equals(jobIdHash)) {
            throw failure(AuthorizationException.Reason.SPEC_MISMATCH, "event is not tied to a consumed authorization");
        }
        return current;
    }

    private static boolean canRecordEvent(SecurityState state, Current current, String event) {
        boolean terminal = hasTerminalEvent(state, current);
        if (CANCEL_REQUESTED.equals(event)) {
            return !terminal && !hasEvent(state, current, event) && !hasEvent(state, current, "OUTPUT");
        }
        if (CANCELLED.equals(event)) {
            return !terminal && !hasEvent(state, current, "OUTPUT")
                    && hasEvent(state, current, CANCEL_REQUESTED) && !hasEvent(state, current, event);
        }
        if (FAILED.equals(event) || INTERRUPTED.equals(event)) {
            return !terminal && !hasEvent(state, current, "OUTPUT") && !hasEvent(state, current, event);
        }
        if (EXPORT_CONFIRMED.equals(event)) {
            return !terminal && hasEvent(state, current, "OUTPUT");
        }
        if (SHARE_SHEET_OPENED.equals(event)) {
            return !terminal && hasEvent(state, current, "OUTPUT") && hasEvent(state, current, EXPORT_CONFIRMED);
        }
        if (CLEARED.equals(event)) {
            return !hasEvent(state, current, CLEARED)
                    && (hasEvent(state, current, "OUTPUT") || terminal);
        }
        return false;
    }

    private static boolean hasEvent(SecurityState state, Current current, String event) {
        for (AuditEvent candidate : state.events) {
            if (candidate.event.equals(event) && candidate.specHash.equals(current.specHash)
                    && candidate.scopeHash.equals(current.scopeHash) && candidate.nonceHash.equals(current.nonceHash)
                    && candidate.jobIdHash.equals(current.jobIdHash)) return true;
        }
        return false;
    }

    private static boolean hasTerminalEvent(SecurityState state, Current current) {
        return hasEvent(state, current, CANCELLED) || hasEvent(state, current, FAILED)
                || hasEvent(state, current, INTERRUPTED) || hasEvent(state, current, CLEARED);
    }

    private static final class Current {
        final String specHash, scopeHash, nonceHash, jobIdHash;
        final long issuedAt, expiresAt;
        final boolean consumed;
        Current(String specHash, String scopeHash, String nonceHash, String jobIdHash, long issuedAt, long expiresAt, boolean consumed) {
            this.specHash = specHash; this.scopeHash = scopeHash; this.nonceHash = nonceHash;
            this.jobIdHash = jobIdHash;
            this.issuedAt = issuedAt; this.expiresAt = expiresAt; this.consumed = consumed;
        }
        static Current from(Authorization a) { return new Current(a.specHash(), a.scopeHash(), hash(a.nonce()), "", a.issuedAt(), a.expiresAt(), false); }
        Current withConsumed(String boundJobIdHash) { return new Current(specHash, scopeHash, nonceHash, boundJobIdHash, issuedAt, expiresAt, true); }
        String line() { return join("current", specHash, scopeHash, nonceHash, jobIdHash, Long.toString(issuedAt), Long.toString(expiresAt), Boolean.toString(consumed)); }
        static Current parse(String[] p) {
            if (p.length != 7 && p.length != 8) throw new IllegalArgumentException("invalid current record");
            if (!isHash(p[1]) || !isHash(p[2]) || !isHash(p[3])) throw new IllegalArgumentException("invalid current hash");
            String jobIdHash = p.length == 8 ? p[4] : "";
            if (!isHashOrEmpty(jobIdHash)) throw new IllegalArgumentException("invalid job id hash");
            int offset = p.length == 8 ? 1 : 0;
            long issued = Long.parseLong(p[4 + offset]); long expires = Long.parseLong(p[5 + offset]);
            if (issued < 0 || expires <= issued) throw new IllegalArgumentException("invalid current timestamps");
            String consumed = p[6 + offset];
            if (!"true".equals(consumed) && !"false".equals(consumed)) throw new IllegalArgumentException("invalid consumed flag");
            return new Current(p[1], p[2], p[3], jobIdHash, issued, expires, Boolean.parseBoolean(consumed));
        }
    }

    private static final class AuditEvent {
        final String event, specHash, scopeHash, nonceHash, jobIdHash, outputHash;
        final long timestamp;
        AuditEvent(String event, long timestamp, String specHash, String scopeHash, String nonceHash, String jobIdHash, String outputHash) {
            this.event = event; this.timestamp = timestamp; this.specHash = specHash; this.scopeHash = scopeHash;
            this.nonceHash = nonceHash; this.jobIdHash = jobIdHash; this.outputHash = outputHash;
        }
        static AuditEvent of(String event, long timestamp, String specHash, String scopeHash, String nonceHash, String outputHash) {
            return new AuditEvent(event, timestamp, specHash, scopeHash, nonceHash, "", outputHash);
        }
        static AuditEvent of(String event, long timestamp, String specHash, String scopeHash, String nonceHash, String jobIdHash, String outputHash) {
            return new AuditEvent(event, timestamp, specHash, scopeHash, nonceHash, jobIdHash, outputHash);
        }
        String line() { return join("event", event, Long.toString(timestamp), specHash, scopeHash, nonceHash, jobIdHash, outputHash); }
        String json() {
            return "{\"event\":\"" + event + "\",\"timestamp\":" + timestamp +
                    ",\"specHash\":\"" + specHash + "\",\"scopeHash\":\"" + scopeHash +
                    "\",\"nonceHash\":\"" + nonceHash + "\",\"jobIdHash\":\"" + jobIdHash +
                    "\",\"outputHash\":\"" + outputHash + "\"}";
        }
        static AuditEvent parse(String[] p) {
            if (p.length != 7 && p.length != 8) throw new IllegalArgumentException("invalid audit record");
            if (!EVENT_ALLOWLIST.contains(p[1]) || !isHash(p[3]) || !isHash(p[4]) || !isHash(p[5])) {
                throw new IllegalArgumentException("invalid audit event");
            }
            long timestamp = Long.parseLong(p[2]);
            if (timestamp < 0) throw new IllegalArgumentException("invalid audit timestamp");
            String jobIdHash = p.length == 8 ? p[6] : "";
            String output = p.length == 8 ? p[7] : p[6];
            if (!isHashOrEmpty(jobIdHash)) throw new IllegalArgumentException("invalid event job id hash");
            if ("OUTPUT".equals(p[1])) { if (!isHash(output)) throw new IllegalArgumentException("invalid output hash"); }
            else if (!output.isEmpty()) throw new IllegalArgumentException("unexpected output hash");
            return new AuditEvent(p[1], timestamp, p[3], p[4], p[5], jobIdHash, output);
        }
    }

    private static final class SecurityState {
        Current current;
        final List<AuditEvent> events = new ArrayList<AuditEvent>();
        SecurityState copy() {
            SecurityState out = new SecurityState(); out.current = current; out.events.addAll(events); return out;
        }
        String serialize() {
            StringBuilder out = new StringBuilder(VERSION).append('\n');
            if (current != null) out.append(current.line()).append('\n');
            for (AuditEvent event : events) out.append(event.line()).append('\n');
            return out.toString();
        }
        static SecurityState parse(String value) {
            SecurityState out = new SecurityState();
            if (value == null || value.length() == 0) return out;
            if (value.trim().isEmpty()) throw new IllegalArgumentException("blank security state");
            String[] lines = value.split("\\r?\\n");
            if (lines.length == 0 || !VERSION.equals(lines[0])) throw new IllegalArgumentException("invalid security state version");
            boolean currentSeen = false;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].trim().isEmpty()) continue;
                String[] parts = lines[i].split("\\t", -1);
                if (parts.length == 0) throw new IllegalArgumentException("invalid security state line");
                if ("current".equals(parts[0])) {
                    if (currentSeen) throw new IllegalArgumentException("duplicate current record");
                    currentSeen = true; out.current = Current.parse(parts);
                }
                else if ("event".equals(parts[0])) out.events.add(AuditEvent.parse(parts));
                else throw new IllegalArgumentException("unknown security state line");
            }
            validate(out);
            return out;
        }

        private static void validate(SecurityState state) {
            if (state.current == null) {
                if (!state.events.isEmpty()) throw new IllegalArgumentException("events without current authorization");
                return;
            }
            Current current = state.current;
            boolean authorized = false;
            boolean consumed = false;
            boolean output = false;
            boolean cancelRequested = false;
            boolean terminal = false;
            boolean exportConfirmed = false;
            boolean cleared = false;
            for (AuditEvent event : state.events) {
                if (!event.specHash.equals(current.specHash) || !event.scopeHash.equals(current.scopeHash)
                        || !event.nonceHash.equals(current.nonceHash)) continue;
                if ("AUTHORIZED".equals(event.event)) {
                    if (!event.jobIdHash.isEmpty()) throw new IllegalArgumentException("invalid authorization history");
                    authorized = true;
                } else {
                    if (!event.jobIdHash.equals(current.jobIdHash)) throw new IllegalArgumentException("event job binding mismatch");
                    if ("CONSUMED".equals(event.event)) {
                        if (!authorized || consumed) throw new IllegalArgumentException("invalid consumed history");
                        consumed = true;
                    } else if ("OUTPUT".equals(event.event)) {
                        if (!consumed || output || terminal) throw new IllegalArgumentException("invalid output history");
                        output = true;
                    } else if (CANCEL_REQUESTED.equals(event.event)) {
                        if (!consumed || cancelRequested || output || terminal) throw new IllegalArgumentException("invalid cancel history");
                        cancelRequested = true;
                    } else if (CANCELLED.equals(event.event)) {
                        if (!cancelRequested || output || terminal) throw new IllegalArgumentException("invalid cancelled history");
                        terminal = true;
                    } else if (FAILED.equals(event.event) || INTERRUPTED.equals(event.event)) {
                        if (!consumed || output || terminal) throw new IllegalArgumentException("invalid terminal history");
                        terminal = true;
                    } else if (EXPORT_CONFIRMED.equals(event.event)) {
                        if (!output || terminal) throw new IllegalArgumentException("invalid export history");
                        exportConfirmed = true;
                    } else if (SHARE_SHEET_OPENED.equals(event.event)) {
                        if (!exportConfirmed || terminal) throw new IllegalArgumentException("invalid share history");
                    } else if (CLEARED.equals(event.event)) {
                        if (cleared || (!output && !terminal)) throw new IllegalArgumentException("invalid clear history");
                        cleared = true;
                        terminal = true;
                    }
                }
            }
            if (!authorized || current.consumed != consumed || (!current.consumed && !current.jobIdHash.isEmpty())) {
                throw new IllegalArgumentException("authorization history does not match current state");
            }
        }
    }

    private static String join(String... values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null || values[i].indexOf('\t') >= 0 || values[i].indexOf('\r') >= 0 || values[i].indexOf('\n') >= 0) {
                throw new IllegalArgumentException("unsafe audit field");
            }
            if (i > 0) out.append('\t'); out.append(values[i]);
        }
        return out.toString();
    }

    private static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean isHashOrEmpty(String value) { return value != null && (value.isEmpty() || isHash(value)); }
}
