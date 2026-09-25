package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.SpriteBank;

/** How much data the assembly parsers actually yield, before deciding its form. */
public final class TableSize {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        SpriteBank bank = new SpriteBank(game);
        int total = 0, slots = 0;
        for (int slot = 0; slot < 32; slot++) {
            int n = 0;
            while (bank.frame(slot, n) != null && n < 4096) {
                n++;
            }
            if (n > 0) {
                slots++;
                total += n;
            }
        }
        System.out.printf("SpriteBank: %d slots, %d frames in all (two ints each)%n",
                          slots, total);
    }
}
