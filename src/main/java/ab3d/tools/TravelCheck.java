package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.Doors;
import ab3d.engine.Frame68k;
import ab3d.engine.Lifts;

/**
 * Checks the height scaling against the level file itself.
 *
 * Neither routine reads the zone height it writes -- both overwrite it every
 * frame from their own record. So the value the file ships is a free witness: if
 * {@code (height >> 2) * 256} is right, that value should equal what one end of
 * the travel produces. Nothing forces the two to agree unless the relation is
 * the routine's.
 */
public final class TravelCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        int doorsOk = 0, doorsBad = 0, liftsOk = 0, liftsBad = 0;
        for (String name : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            // read the shipped heights before anything overwrites them
            int[] floor = new int[lv.zones.length];
            int[] roof = new int[lv.zones.length];
            for (int i = 0; i < lv.zones.length; i++) {
                floor[i] = lv.zone(i).floorHeight;
                roof[i] = lv.zone(i).roofHeight;
            }
            Frame68k f = new Frame68k(lv, game);

            for (Doors.Door d : f.doors.doors) {
                if (d.zone < 0 || d.zone >= lv.zones.length) {
                    continue;
                }
                if (matches(roof[d.zone], d.bottom, d.top)) {
                    doorsOk++;
                } else {
                    doorsBad++;
                    System.out.printf("  %s door zone %3d: file roof %7d, travel "
                                    + "%d..%d gives %d..%d%n", name, d.zone,
                                    roof[d.zone], d.bottom, d.top,
                                    scaled(d.bottom), scaled(d.top));
                }
            }
            for (Lifts.Lift l : f.lifts.lifts) {
                if (l.zone < 0 || l.zone >= lv.zones.length) {
                    continue;
                }
                if (matches(floor[l.zone], l.bottom, l.top)) {
                    liftsOk++;
                } else {
                    liftsBad++;
                    System.out.printf("  %s lift zone %3d: file floor %7d, travel "
                                    + "%d..%d gives %d..%d%n", name, l.zone,
                                    floor[l.zone], l.bottom, l.top,
                                    scaled(l.bottom), scaled(l.top));
                }
            }
        }
        System.out.printf("doors: %d match an end of their travel, %d do not%n",
                          doorsOk, doorsBad);
        System.out.printf("lifts: %d match an end of their travel, %d do not%n",
                          liftsOk, liftsBad);
    }

    private static int scaled(int height) {
        return (height >> 2) * 256;
    }

    private static boolean matches(int shipped, int bottom, int top) {
        return shipped == scaled(bottom) || shipped == scaled(top);
    }
}
