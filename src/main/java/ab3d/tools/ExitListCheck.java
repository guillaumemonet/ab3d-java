package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;

/**
 * How long a zone's exit list really is.
 *
 * {@code checkwalls} stops at the first negative word; {@code checkotherwalls}
 * skips a {@code -1} and carries on to a {@code -2}. If the lists hold anything
 * after a {@code -1}, reading them the first way loses lines the second pass
 * needs.
 */
public final class ExitListCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            BinReader r = lv.data;
            int zonesWithMore = 0, shortTotal = 0, longTotal = 0, worst = 0;
            for (Zone z : lv.zones) {
                int at = z.exitListOffset;
                int shortLen = 0;
                while (r.inRange(at + shortLen * 2, 2) && r.s16(at + shortLen * 2) >= 0) {
                    shortLen++;
                }
                int longLen = 0, p = at;
                for (int guard = 0; guard < 512; guard++) {
                    if (!r.inRange(p, 2)) {
                        break;
                    }
                    int v = r.s16(p);
                    p += 2;
                    if (v == -2) {
                        break;
                    }
                    if (v >= 0) {
                        longLen++;
                    }
                }
                shortTotal += shortLen;
                longTotal += longLen;
                if (longLen > shortLen) {
                    zonesWithMore++;
                    worst = Math.max(worst, longLen - shortLen);
                }
            }
            System.out.printf("%-8s %3d zones | lines up to the first negative %d, "
                            + "lines up to -2 %d | %d zones hold more (worst +%d)%n",
                            name, lv.zones.length, shortTotal, longTotal,
                            zonesWithMore, worst);
        }
    }
}
