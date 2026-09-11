package app.quietagent.security;

import java.io.IOException;

public final class AuthorizationException extends IOException {
    public enum Reason { INVALID, SPEC_MISMATCH, REPLAYED, EXPIRED, PERSISTENCE }
    private final Reason reason;

    AuthorizationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
