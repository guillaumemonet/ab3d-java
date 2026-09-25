package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.SpriteSheet;

/**
 * Measures how a sprite sheet's columns are occupied, to settle how wide one
 * frame really is. Counts opaque pixels per pointer-table entry rather than
 * looking at a picture, so the answer is a number and not an impression.
 */
public final class FrameProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String name = args.length > 0 ? args[0] : "alien2";
        int height = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 128;

        SpriteSheet sheet = SpriteSheet.load(game, name);
        System.out.printf("%s: %d pointer entries, probing %d at height %d%n",
                name, sheet.columnCount(), count, height);

        int[] opaque = new int[count];
        for (int c = 0; c < count && c < sheet.columnCount(); c++) {
            for (int y = 0; y < height; y++) {
                if (sheet.pixel(c, y) != SpriteSheet.TRANSPARENT) {
                    opaque[c]++;
                }
            }
        }

        // One line per 32 columns, a character per column: '.' empty, digits by fill
        for (int base = 0; base < count; base += 32) {
            StringBuilder sb = new StringBuilder(String.format("  %3d: ", base));
            for (int c = base; c < base + 32 && c < count; c++) {
                int f = opaque[c] * 10 / Math.max(1, height);
                sb.append(opaque[c] == 0 ? '.' : (char) ('0' + Math.min(9, f)));
            }
            sb.append("   variants:");
            for (int c = base; c < base + 32 && c < count; c += 8) {
                sb.append(' ').append(sheet.variant(c));
            }
            System.out.println(sb);
        }

        // Where do runs of empty columns fall? Those are frame boundaries.
        System.out.print("  empty runs at:");
        int run = 0;
        for (int c = 0; c < count; c++) {
            if (opaque[c] == 0) {
                run++;
            } else {
                if (run >= 2) {
                    System.out.printf(" [%d..%d]", c - run, c - 1);
                }
                run = 0;
            }
        }
        System.out.println();
    }
}
