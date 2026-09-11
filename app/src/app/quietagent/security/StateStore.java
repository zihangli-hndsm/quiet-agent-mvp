package app.quietagent.security;

import java.io.IOException;

/** Durable state boundary used by AuthorizationManager. */
public interface StateStore {
    String read() throws IOException;
    void write(String value) throws IOException;
}
