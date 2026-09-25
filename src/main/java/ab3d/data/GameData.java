package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the original AB3D data files.
 *
 * The assembly sources address them through AmigaOS assigns:
 * {@code AB3D1:includes/...} for the shared graphics, and
 * {@code ab3d2:levels/...} for the per-level files. Both live under the
 * ab3d-rtg checkout, so a single root is enough.
 */
public final class GameData {

    private final Path root;

    public GameData(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Where the contents of the original floppies were extracted, if anywhere.
     * Levels there are named {@code levels/level_a/twolev.bin} and are packed.
     */
    private Path disk;

    /**
     * Resolves the roots from the {@code ab3d.root} and {@code ab3d.disk}
     * properties, or from what the first run remembered.
     *
     * A checkout run from its own tree gives the two properties and nothing else
     * happens. A packaged copy gives neither, and then {@code from} is what the
     * player was asked for once and it was written down.
     */
    public static GameData fromSystemProperty() {
        return from(Paths.get(System.getProperty("ab3d.root", "../ab3d-rtg")),
                    Paths.get(System.getProperty("ab3d.disk", "../adf-extract")));
    }

    public static GameData from(Path root, Path disk) {
        GameData g = new GameData(root);
        g.disk = disk.toAbsolutePath().normalize();
        return g;
    }

    public Path disk() {
        return disk;
    }

    /** Level names available from the extracted floppies, e.g. {@code level_a}. */
    public java.util.List<String> diskLevels() {
        java.util.List<String> out = new java.util.ArrayList<>();
        Path dir = disk == null ? null : disk.resolve("disk2/levels");
        if (dir != null && Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                s.filter(Files::isDirectory)
                 .filter(p -> Files.isRegularFile(p.resolve("twolev.bin")))
                 .map(p -> p.getFileName().toString())
                 .sorted()
                 .forEach(out::add);
            } catch (IOException ignored) {
                // an unreadable directory simply means no disk levels
            }
        }
        return out;
    }

    public Path root() {
        return root;
    }

    /** A file under {@code includes/}, i.e. the AmigaOS {@code AB3D1:includes/} assign. */
    public Path include(String name) {
        return root.resolve("includes").resolve(name);
    }

    /** The directory holding the shipped game levels. */
    public Path gameLevels() {
        return root.resolve("includes/levels/gamelevels");
    }

    /** The directory holding the development levels (jacklev, mikelev, tstlev, ...). */
    public Path devLevels() {
        return root.resolve("includes/levels");
    }

    /**
     * Finds the three files making up a level: {@code <name>.bin} (geometry),
     * {@code <name>.graph.bin} (graphics / doors / lifts / switches) and
     * {@code <name>.clips} (precomputed clip lists).
     */
    public LevelFiles levelFiles(String name) throws IOException {
        // A shipped level, straight off disk 2
        if (disk != null) {
            Path dir = disk.resolve("disk2/levels").resolve(name);
            Path bin = dir.resolve("twolev.bin");
            if (Files.isRegularFile(bin)) {
                return new LevelFiles(bin, dir.resolve("twolev.graph.bin"),
                        dir.resolve("twolev.clips"));
            }
        }
        for (Path dir : new Path[] { gameLevels(), devLevels() }) {
            Path bin = dir.resolve(name + ".bin");
            if (Files.isRegularFile(bin)) {
                Path graph = dir.resolve(name + ".graph.bin");
                Path clips = dir.resolve(name + ".clips");
                if (!Files.isRegularFile(clips)) {
                    // clev ships as "clev.clips.clips"
                    Path alt = dir.resolve(name + ".clips.clips");
                    if (Files.isRegularFile(alt)) {
                        clips = alt;
                    }
                }
                return new LevelFiles(bin, graph, clips);
            }
        }
        throw new IOException("No level named '" + name + "' under " + root);
    }

    public record LevelFiles(Path bin, Path graph, Path clips) {}
}
