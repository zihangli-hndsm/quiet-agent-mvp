import app.quietagent.core.Engine;
import app.quietagent.core.Plan;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pure-JVM bridge for exercising the real Engine against make_fixtures.py data.
 *
 * Usage:
 *   java -cp <classes> RunArchive <source-dir> <destination-dir> [request]
 *
 * It does not start Android, use a device, or synthesize a manifest. The three
 * output paths and Engine counters are printed for the Python verifier.
 */
public final class RunArchive {
    private RunArchive() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: RunArchive <source-dir> <destination-dir> [request]");
            System.exit(2);
        }
        final Path sourceRoot = Paths.get(args[0]).toAbsolutePath().normalize();
        final File destination = Paths.get(args[1]).toAbsolutePath().normalize().toFile();
        final String request = args.length == 3 ? args[2] : "去重，按类型归档";
        if (!Files.isDirectory(sourceRoot)) throw new IOException("source directory does not exist: " + sourceRoot);
        File[] existing = destination.listFiles();
        if (existing != null && existing.length != 0) throw new IOException("destination must be empty: " + destination);

        FixtureSource source = new FixtureSource(sourceRoot);
        Plan plan = Plan.parse(request);
        Engine.Result result = Engine.run(source, plan, destination,
                (phase, done, total) -> { if ("complete".equals(phase)) System.out.println("phase=complete"); },
                () -> false);
        System.out.println("archive=" + result.archive.getAbsolutePath());
        System.out.println("manifest=" + result.manifest.getAbsolutePath());
        System.out.println("summary=" + result.summary.getAbsolutePath());
        System.out.println("scanned=" + result.scanned + " selected=" + result.selected +
                " unique=" + result.unique + " duplicates=" + result.duplicates + " bytes=" + result.bytes);
        System.out.println("archiveSha256=" + result.archiveSha256);
    }

    private static final class FixtureSource implements Engine.Source {
        private final Path root;
        private final List<Engine.Entry> entries;

        FixtureSource(Path root) throws IOException {
            this.root = root;
            this.entries = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                        .forEach(path -> {
                            try {
                                String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
                                long size = Files.size(path);
                                long modified = Files.getLastModifiedTime(path).toMillis();
                                entries.add(new Engine.Entry(relative, relative, size, modified));
                            } catch (IOException ex) {
                                throw new SourceRuntimeException(ex);
                            }
                        });
            } catch (SourceRuntimeException ex) {
                throw ex.cause;
            }
        }

        @Override public List<Engine.Entry> list() { return new ArrayList<>(entries); }

        @Override public InputStream open(Engine.Entry entry) throws IOException {
            Path resolved = root.resolve(entry.path.replace('/', File.separatorChar)).normalize();
            if (!resolved.startsWith(root)) throw new IOException("source path escaped root: " + entry.path);
            return Files.newInputStream(resolved);
        }

        @Override public String description() { return "fixture source: " + root; }
    }

    private static final class SourceRuntimeException extends RuntimeException {
        final IOException cause;
        SourceRuntimeException(IOException cause) { super(cause); this.cause = cause; }
    }
}
