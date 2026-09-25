package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.BrightAnim;

/** The points that carry an animation byte, and how far they actually move. */
public final class PointAnimCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_a");
        BrightAnim anim = new BrightAnim();

        int found = 0;
        for (int i = 0; i < lv.numPoints; i++) {
            int word = lv.pointBrightRaw[i];
            if ((byte) word < 0 || ((word >> 8) & 0xff) == 0) {
                continue;
            }
            found++;
            int lo = 999, hi = -999;
            BrightAnim a = new BrightAnim();
            for (int f = 0; f < 120; f++) {
                a.tick();
                int v = a.resolvePoint(word);
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            int kind = (word >> 8) & 0xf;
            int weight = (((word >> 8) >> 4) & 0xf) + 1;
            System.out.printf("  point %3d: word %5d, own %3d, animation %d "
                            + "weight %2d/16 -> swings %d..%d%n",
                            i, word, (byte) word, kind, weight, lo, hi);
        }
        System.out.printf("%s: %d points animate; unresolved they would all sit at "
                        + "their own value%n", lv.name, found);
    }
}
