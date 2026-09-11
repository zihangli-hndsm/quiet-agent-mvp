package app.quietagent.security;

import java.io.IOException;

/** JVM-test store; production should use AtomicFileStateStore. */
public final class MemoryStateStore implements StateStore {
    private String value = "";
    private boolean failWrites;

    @Override public synchronized String read() { return value; }

    @Override public synchronized void write(String next) throws IOException {
        if (failWrites) throw new IOException("injected state write failure");
        value = next;
    }

    public synchronized void setFailWrites(boolean fail) { failWrites = fail; }
    public synchronized String value() { return value; }
}
