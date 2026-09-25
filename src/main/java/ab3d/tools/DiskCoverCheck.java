package ab3d.tools;

import ab3d.data.GameData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * How much of what the port reads is on the two floppies?
 *
 * The question matters because of what it decides: anything the disks carry, a
 * player already has, and anything they do not has to come from the source
 * release -- or be worked out, as {@link ab3d.data.BuiltTables} does for two of
 * them.
 *
 * It is easy to guess wrong here in both directions. The source tree's
 * {@code includes} is nine megabytes and looks like the whole game, but most of
 * it is working material that never shipped; disk one turns out to carry every
 * sprite sheet, the wall textures and the floor tiles. So this counts rather
 * than assumes: it walks the list of files the engine actually opens and asks,
 * of each, whether a disk has it.
 */
public final class DiskCoverCheck {

    /** {@code SHEETS} in SpriteBank, which is what the drawing code asks for. */
    private static final String[] SHEETS = {
        "alien2", "pickups", "bigbullet", "uglymonster", "flyingalien", "keys",
        "rockets", "barrel", "explosion", "lamps", "newmarine", "newgunsinhand",
        "tree", "worm", "bigclaws",
    };

    /** The tables with no rule behind them, read as bytes wherever they are. */
    private static final String[] BINARIES = {
        "bigsine", "backfile", "brightenfile", "constantfile", "floorpalscaled",
        "waterfile", "floortile",
    };

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        List<String> wanted = new ArrayList<>();
        for (String s : SHEETS) {
            wanted.add(s + ".wad");
            wanted.add(s + ".ptr");
        }
        wanted.addAll(List.of(BINARIES));
        for (String w : ab3d.data.WallTextures.files()) {
            wanted.add("walls/" + w);
        }

        int onDisk = 0;
        long diskBytes = 0;
        long treeBytes = 0;
        List<String> missing = new ArrayList<>();
        List<String> fromTree = new ArrayList<>();

        for (String name : wanted) {
            Path p = game.include(name);
            if (!Files.isRegularFile(p)) {
                missing.add(name);
                continue;
            }
            long size = Files.size(p);
            if (game.disk() != null && p.startsWith(game.disk())) {
                onDisk++;
                diskBytes += size;
            } else {
                fromTree.add(String.format("%-26s %7d", name, size));
                treeBytes += size;
            }
        }

        System.out.printf("%d of %d files come off the floppies (%d KB)%n",
                          onDisk, wanted.size(), diskBytes / 1024);
        System.out.printf("%n%d still come from the source tree (%d KB):%n",
                          fromTree.size(), treeBytes / 1024);
        fromTree.forEach(s -> System.out.println("  " + s));

        if (!missing.isEmpty()) {
            System.out.printf("%n%d are nowhere, and the port does without them:%n",
                              missing.size());
            missing.forEach(s -> System.out.println("  " + s));
        }

        System.out.println("\nworked out instead of read:");
        System.out.println("  xtocopx                        192");
        System.out.println("  iterfile                      2048");
    }

}
