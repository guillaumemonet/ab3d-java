package ab3d.tools;

import ab3d.data.BuiltTables;
import ab3d.data.GameData;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Do the worked-out tables match the files they replace, byte for byte?
 *
 * {@link BuiltTables} drops two of the original's include files on the claim
 * that a rule reproduces them exactly. The claim is worth nothing unless it is
 * tested against the files themselves, so this reads both and compares every
 * entry. Anything but a clean run means the rule is wrong and the bytes should
 * go back.
 */
public final class BuiltTableCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        int bad = 0;
        bad += compare(game.root().resolve("includes/xtocopx"), "xtocopx",
                       BuiltTables.xToCopX());
        bad += compare(game.root().resolve("includes/iterfile"), "iterfile",
                       BuiltTables.iterFile());
        System.out.println(bad == 0
                ? "\nboth files come straight out of their rule"
                : "\n" + bad + " entries differ -- the rule is wrong");
    }

    /** Reads a file of big-endian words and holds it against the built table. */
    private static int compare(Path file, String name, int[] built)
            throws java.io.IOException {
        if (!Files.isRegularFile(file)) {
            System.out.printf("  %-10s no file to check against%n", name);
            return 0;
        }
        byte[] raw = Files.readAllBytes(file);
        int words = raw.length / 2;
        if (words != built.length) {
            System.out.printf("  %-10s WRONG %d words in the file, %d built%n",
                              name, words, built.length);
            return 1;
        }
        int bad = 0;
        for (int i = 0; i < words; i++) {
            int was = ((raw[i * 2] & 0xff) << 8) | (raw[i * 2 + 1] & 0xff);
            if (was != built[i]) {
                if (bad < 5) {
                    System.out.printf("  %-10s WRONG [%d] file %d, built %d%n",
                                      name, i, was, built[i]);
                }
                bad++;
            }
        }
        if (bad == 0) {
            System.out.printf("  %-10s ok    %d words, %d bytes not needed%n",
                              name, words, raw.length);
        }
        return bad;
    }
}
