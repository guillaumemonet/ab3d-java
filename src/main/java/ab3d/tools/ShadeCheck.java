package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Shading;
import ab3d.data.SpriteBank;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Does the shading rule rebuild every palette the source tree has, byte for byte?
 *
 * {@link Shading} claims that fourteen of a {@code .pal}'s fifteen rows follow
 * from the first, which is what lets this port carry thirty-two colours a sheet
 * instead of nine hundred and sixty bytes. That is only safe while the claim
 * holds against the files, so this rebuilds each one from its own top row and
 * compares the whole thing.
 *
 * A single differing byte means a sheet would be drawn in the wrong colours, so
 * the answer wanted here is none.
 */
public final class ShadeCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        int checked = 0;
        int bad = 0;
        long saved = 0;

        for (String name : SpriteBank.SHEETS) {
            Path pal = game.include(name + ".pal");
            if (!Files.isRegularFile(pal)) {
                System.out.printf("  %-16s no .pal in the tree%n", name);
                continue;
            }
            byte[] was = Files.readAllBytes(pal);
            int[] top = new int[Shading.COLOURS];
            for (int c = 0; c < top.length && c * 2 + 1 < was.length; c++) {
                top[c] = ((was[c * 2] & 0xff) << 8) | (was[c * 2 + 1] & 0xff);
            }
            byte[] built = Shading.ramp(top);
            checked++;
            if (Arrays.equals(was, built)) {
                saved += was.length - top.length * 2L;
                System.out.printf("  %-16s ok    %d bytes, %d of them kept%n",
                                  name, was.length, top.length * 2);
                continue;
            }
            bad++;
            System.out.printf("  %-16s WRONG %s%n", name, firstDiff(was, built));
        }

        System.out.printf("%n%d palettes, %d wrong%n", checked, bad);
        if (bad == 0) {
            System.out.printf("the rule carries %d bytes that need not be shipped%n",
                              saved);
        }
    }

    /** Where two byte arrays part company, said in a way that points at a colour. */
    private static String firstDiff(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return a.length + " bytes against " + b.length;
        }
        for (int i = 0; i < a.length; i += 2) {
            if (a[i] != b[i] || a[i + 1] != b[i + 1]) {
                int word = i / 2;
                return String.format("shade %d colour %d: file %03x, built %03x",
                                     word / Shading.COLOURS,
                                     word % Shading.COLOURS,
                                     ((a[i] & 0xff) << 8 | (a[i + 1] & 0xff)) & 0xfff,
                                     ((b[i] & 0xff) << 8 | (b[i + 1] & 0xff)) & 0xfff);
            }
        }
        return "no difference found";
    }
}
