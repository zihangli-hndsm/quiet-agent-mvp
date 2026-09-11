package app.quietagent.test;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** Read-only provider for the 12 fictional receipt images bundled in the test APK. */
public final class FixtureProvider extends ContentProvider {
    public static final String AUTHORITY = "app.quietagent.test.fixtures";
    private static final String PREFIX = "receipts/";

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        String name = checkedName(uri);
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            if (!"r".equals(mode)) throw new IOException("fixture provider is read-only");
            String name = checkedName(uri);
            File cached = new File(getContext().getCacheDir(), "fixture-" + name);
            if (!cached.isFile()) {
                File parent = cached.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("cannot create fixture cache");
                AssetManager assets = getContext().getAssets();
                try (java.io.InputStream input = assets.open(PREFIX + name);
                     FileOutputStream output = new FileOutputStream(cached)) {
                    byte[] buffer = new byte[32 * 1024];
                    int n;
                    while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                    output.getFD().sync();
                }
            }
            return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException error) { throw error;
        } catch (Exception error) {
            FileNotFoundException failure = new FileNotFoundException(error.getMessage());
            failure.initCause(error);
            throw failure;
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String name = checkedName(uri);
        File cached;
        try {
            cached = cacheForQuery(name);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        String[] columns = projection == null || projection.length == 0
                ? new String[]{"_display_name", "_size", "mime_type"} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if ("_display_name".equals(columns[i]) || "display_name".equals(columns[i])) row[i] = name;
            else if ("_size".equals(columns[i]) || "size".equals(columns[i])) row[i] = cached.length();
            else if ("mime_type".equals(columns[i]) || "mime".equals(columns[i])) row[i] = getType(uri);
            else row[i] = null;
        }
        cursor.addRow(row);
        return cursor;
    }

    private File cacheForQuery(String name) throws IOException {
        File cached = new File(getContext().getCacheDir(), "fixture-" + name);
        if (!cached.isFile()) {
            // openFile performs the one canonical copy path and closes its descriptor here.
            ParcelFileDescriptor descriptor = openFile(Uri.parse("content://" + AUTHORITY + "/" + name), "r");
            try { descriptor.close(); } finally { }
        }
        return cached;
    }

    private static String checkedName(Uri uri) {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())) {
            throw new IllegalArgumentException("invalid fixture URI");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 1) throw new IllegalArgumentException("invalid fixture path");
        String name = segments.get(0);
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,96}\\.png")) throw new IllegalArgumentException("invalid fixture name");
        return name;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read-only"); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("read-only"); }
}
