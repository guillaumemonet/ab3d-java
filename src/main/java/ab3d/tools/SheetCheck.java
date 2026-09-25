package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.SpriteBank;
import ab3d.data.SpriteSheet;

/** How much of each sprite sheet is actually non-transparent. */
public final class SheetCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        SpriteBank bank = new SpriteBank(game);
        for (int slot = 0; slot < 18; slot++) {
            SpriteSheet sh = bank.sheet(slot);
            if (sh == null) {
                System.out.printf("slot %2d  (no sheet)%n", slot);
                continue;
            }
            int cols = sh.columnCount(), blank = 0, solid = 0, opaque = 0, read = 0;
            for (int c = 0; c < cols; c++) {
                if (sh.isBlank(c)) {
                    blank++;
                    continue;
                }
                solid++;
                for (int y = 0; y < 64; y++) {
                    read++;
                    if (sh.pixel(c, y) != SpriteSheet.TRANSPARENT) {
                        opaque++;
                    }
                }
            }
            System.out.printf("slot %2d %-16s %4d columns, %4d blank, "
                            + "%5.1f%% opaque over the rest%n",
                            slot, sh.name, cols, blank,
                            read == 0 ? 0.0 : 100.0 * opaque / read);
        }
    }
}
