package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;

/**
 * Dumps the door list.
 *
 * Layout from {@code DoorRoutine} in source/anims: bottom and top of the travel,
 * the current height, the speed, a pointer to a wall record, the zone whose roof
 * the door is, then the condition word. A bottom of 999 ends the list.
 */
public final class DoorTool {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            BinReader g = lv.graphics.data;
            int at = lv.graphics.doorDataOffset;
            System.out.printf("%s: door list at %d%n", name, at);
            int n = 0;
            while (g.inRange(at, 16) && n < 40) {
                int bottom = g.s16(at);
                if (bottom == 999) {
                    break;
                }
                int top = g.s16(at + 2);
                int height = g.s16(at + 4);
                int speed = g.s16(at + 6);
                int wallPtr = g.s32(at + 8);
                int zone = g.s16(at + 12);
                String zi = zone >= 0 && zone < lv.zones.length
                        ? String.format("floor %d roof %d", lv.zone(zone).floorHeight,
                                        lv.zone(zone).roofHeight)
                        : "out of range";
                System.out.printf("  door %2d: travel %5d..%5d height %5d speed %3d "
                                + "wall @%d zone %3d (%s)%n",
                                n, bottom, top, height, speed, wallPtr, zone, zi);
                // skip to the next: conditions, trigger bytes, then a wall list
                int p = at + 14;
                p += 2;                       // conditions
                p += 2;                       // trigger bytes
                while (g.inRange(p, 2) && g.s16(p) >= 0) {
                    p += 2 + 4 + 4;           // line, wall pointer, texture pointer
                }
                at = p + 2;
                n++;
            }
            System.out.printf("  %d doors read%n", n);
        }
    }
}
