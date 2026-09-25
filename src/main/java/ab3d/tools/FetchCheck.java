package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.game.DiskFetch;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Does the download route give a playable set of disks?
 *
 * The archive is served through a redirect and holds both floppies, so the
 * things that can go wrong are getting the redirect, recognising which entries
 * are disk images, and telling the two disks apart. The last is the quiet one:
 * the names inside the archive are whoever's who made them, and only the
 * contents say which is the level disk.
 */
public final class FetchCheck {

    public static void main(String[] args) throws Exception {
        Path into = Files.createTempDirectory("ab3d-fetch-check");
        try {
            int disks = DiskFetch.fetch(into, System.out::println);
            System.out.printf("%n%d disks unpacked into %s%n", disks, into);

            for (String want : new String[]{"disk1/includes", "disk2/levels",
                                            "disk2/sounds"}) {
                System.out.printf("  %-16s %s%n", want,
                                  Files.isDirectory(into.resolve(want)) ? "there"
                                          : "MISSING");
            }

            GameData game = GameData.from(Path.of("../ab3d-rtg"), into);
            System.out.printf("%nlevels on them: %d%n", game.diskLevels().size());
            Level lv = Level.load(game, "level_a");
            System.out.printf("level_a loads: %d zones, %d objects%n",
                              lv.zones.length, lv.objects.size());
        } finally {
            try (var s = Files.walk(into)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // a leftover file is not worth failing the check for
                    }
                });
            }
            System.out.println("\ncleaned up");
        }
    }
}
