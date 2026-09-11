package app.quietagent.security;

/** One authorization response. The nonce is intentionally returned only to the caller. */
public final class Authorization {
    private final String nonce;
    private final String specHash;
    private final String scopeHash;
    private final long issuedAt;
    private final long expiresAt;

    Authorization(String nonce, String specHash, String scopeHash, long issuedAt, long expiresAt) {
        this.nonce = nonce;
        this.specHash = specHash;
        this.scopeHash = scopeHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public String nonce() { return nonce; }
    public String specHash() { return specHash; }
    public String scopeHash() { return scopeHash; }
    public long issuedAt() { return issuedAt; }
    public long expiresAt() { return expiresAt; }
}
