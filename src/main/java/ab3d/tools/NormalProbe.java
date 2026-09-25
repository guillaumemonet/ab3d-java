package ab3d.tools;

import ab3d.data.FloorLine;
import ab3d.data.GameData;
import ab3d.data.Level;

/**
 * What the bytes at twelve and thirteen of a line record actually hold.
 *
 * {@code thisisawall2} treats them as the normal it holds a mover off by, so if
 * they are nought the standoff is nought and a mover walks until its centre is
 * on the line.
 */
public final class NormalProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_a");
        int zone = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        System.out.printf("zone %d's own lines:%n", zone);
        for (int e : lv.zone(zone).exitLines) {
            if (e < 0 || e >= lv.floorLines.length) {
                continue;
            }
            FloorLine fl = lv.floorLine(e);
            System.out.printf("  line %4d at (%5d,%5d) d(%5d,%5d) len %5d "
                              + "to zone %4d, normal (%4d,%4d)%n", e, fl.x1,
                              fl.z1, fl.dx, fl.dz, fl.length, fl.toZone,
                              fl.ox, fl.oz);
        }

        int zero = 0, total = 0;
        for (int i = 0; i < lv.floorLines.length; i++) {
            FloorLine fl = lv.floorLine(i);
            total++;
            if (fl.ox == 0 && fl.oz == 0) {
                zero++;
            }
        }
        System.out.printf("%nacross the level: %d of %d lines have no normal%n",
                          zero, total);
    }
}
