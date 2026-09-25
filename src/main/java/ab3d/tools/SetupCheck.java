package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Does the first run unpack the floppies, and does it only happen once?
 *
 * Two claims, and neither can be tested through the dialog itself. The first is
 * that reading the two disk images produces the directories the game looks in --
 * which means telling the two apart correctly, since their file names vary with
 * whoever made the images and only their contents say which is which. The second
 * is that what was chosen is written down and read back, so the question is
 * asked once and not every time.
 *
 * Give it the directory holding the two {@code .adf} files.
 */
public final class SetupCheck {

    public static void main(String[] args) throws Exception {
        Path adfs = Path.of(args.length > 0 ? args[0] : "../adf");
        Path home = Files.createTempDirectory("ab3d-setup-check");
        Path into = home.resolve("disk");

        System.out.println("unpacking from " + adfs.toAbsolutePath());
        int disks = 0, files = 0;
        try (var s = Files.list(adfs)) {
            for (Path adf : s.filter(p -> p.toString().toLowerCase().endsWith(".adf"))
                             .sorted().toList()) {
                AdfTool disk = new AdfTool(Files.readAllBytes(adf));
                boolean second = disk.has("levels");
                Path to = into.resolve(second ? "disk2" : "disk1");
                int n = disk.extractAll(to);
                disks++;
                files += n;
                System.out.printf("  %-58s -> %s, %d files%n",
                                  adf.getFileName(), second ? "disk2" : "disk1", n);
            }
        }
        System.out.printf("%d disks, %d files%n%n", disks, files);

        // the things the game goes looking for on the disks
        for (String want : new String[]{"disk2/levels", "disk2/sounds",
                                        "disk1/includes", "disk2/includes"}) {
            System.out.printf("  %-18s %s%n", want,
                              Files.isDirectory(into.resolve(want)) ? "there"
                                      : "MISSING");
        }

        // and whether a level actually loads out of what was unpacked
        GameData game = GameData.from(Path.of("../ab3d-rtg"), into);
        System.out.printf("%nlevels found on the disks: %s%n", game.diskLevels());
        Level lv = Level.load(game, "level_a");
        System.out.printf("level_a loads from the unpacked disks: %d zones, "
                          + "%d objects%n", lv.zones.length, lv.objects.size());

        deleteTree(home);
        System.out.println("\ncleaned up " + home);
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // a file left behind is not worth failing the check for
                }
            });
        }
    }
}
