package app.quietagent.security;

import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** AtomicFile-backed state for Android production use. */
public final class AtomicFileStateStore implements StateStore {
    private final AtomicFile file;

    public AtomicFileStateStore(File location) {
        if (location == null) throw new IllegalArgumentException("location is required");
        File parent = location.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalArgumentException("cannot create state directory");
        }
        file = new AtomicFile(location);
    }

    @Override public synchronized String read() throws IOException {
        if (!file.getBaseFile().isFile()) return "";
        byte[] bytes = file.readFully();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override public synchronized void write(String value) throws IOException {
        if (value == null) throw new IOException("state is null");
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            file.finishWrite(output);
        } catch (IOException error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }
}
