package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;

/** What the per-point brightness table holds, at the stride jg.s uses. */
public final class BrightPtCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            int ulo = Integer.MAX_VALUE, uhi = Integer.MIN_VALUE, nonzero = 0;
            for (int i = 0; i < lv.numPoints; i++) {
                lo = Math.min(lo, lv.pointBright[i]);
                hi = Math.max(hi, lv.pointBright[i]);
                ulo = Math.min(ulo, lv.pointBrightUpper[i]);
                uhi = Math.max(uhi, lv.pointBrightUpper[i]);
                if (lv.pointBright[i] != 0) {
                    nonzero++;
                }
            }
            System.out.printf("%-8s %4d points | lower %d..%d (%d non-zero) "
                            + "upper %d..%d | animation selectors: %s%n",
                            name, lv.numPoints, lo, hi, nonzero, ulo, uhi,
                            lv.pointBrightAnim == 0 ? "none" : "present");
        }
    }
}
