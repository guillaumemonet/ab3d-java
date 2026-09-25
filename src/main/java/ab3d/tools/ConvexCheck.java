package ab3d.tools;

import ab3d.data.FloorLine;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;

/**
 * Is a zone convex, so that "inside every bounding line" is a fair test?
 *
 * The centre of a room passes that test trivially. What it does not tell you is
 * whether a point near an edge does. This samples along each bounding line,
 * pushed a little into the room, and asks the same question: if those fail, the
 * boundary is made of segments that do not enclose a convex area, and any check
 * built on half-planes is measuring the wrong thing.
 */
public final class ConvexCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            int sampled = 0, failed = 0, zonesFailing = 0;
            for (Zone z : lv.zones) {
                boolean bad = false;
                for (int e : z.exitLines) {
                    if (e < 0 || e >= lv.floorLines.length) {
                        continue;
                    }
                    FloorLine fl = lv.floorLine(e);
                    // the middle of the line, pushed inward along its normal
                    long len = Math.max(1, fl.length);
                    int mx = fl.x1 + fl.dx / 2;
                    int mz = fl.z1 + fl.dz / 2;
                    // inward normal is (+dz, -dx): it is the direction that
                    // makes the cross product positive
                    int px = (int) (mx + (long) fl.dz * 60 / len);
                    int pz = (int) (mz - (long) fl.dx * 60 / len);
                    sampled++;
                    for (int o : z.exitLines) {
                        if (o >= 0 && o < lv.floorLines.length
                                && lv.floorLine(o).side(px, pz) < 0) {
                            failed++;
                            bad = true;
                            break;
                        }
                    }
                }
                if (bad) {
                    zonesFailing++;
                }
            }
            System.out.printf("%-8s %5d points just inside a bounding line: "
                            + "%d fail the half-plane test (%.0f%%), "
                            + "%d of %d zones affected%n",
                            name, sampled, failed, 100.0 * failed / Math.max(1, sampled),
                            zonesFailing, lv.zones.length);
        }
    }
}
