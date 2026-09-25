package ab3d.tools;

import ab3d.data.FloorLine;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.engine.MoveObject;
import ab3d.game.Player;

/**
 * Does walking into a wall leave the player inside the room?
 *
 * {@code MoveObject} slides a blocked move along the wall, and the sliding is
 * approximate: it divides by the line's length plus the mover's reach and leaves
 * a little of the overshoot behind each time. {@code REFUSE_STRAY} exists to
 * throw away a move that ends outside every room, and it is off -- so if the
 * residue ever carries the player through, the renderer is being asked to draw a
 * room from outside it, and everything in that room is behind the viewer.
 */
public final class StrayProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_a");
        Player p = new Player(lv);
        int angle = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        double a = angle / 4096.0 * 2 * Math.PI;
        int dx = (int) Math.round(Math.sin(a) * 24);
        int dz = (int) Math.round(Math.cos(a) * 24);

        int wasOutside = 0;
        for (int step = 0; step < 90; step++) {
            p.move(dx, dz);
            boolean in = inside(lv, p.camera.zone, p.camera.x, p.camera.z);
            if (!in) {
                wasOutside++;
            }
            if (step % 10 == 0 || !in) {
                System.out.printf("  step %2d: (%5d,%5d) zone %3d  %s  "
                                  + "nearest line %d away%n", step, p.camera.x,
                                  p.camera.z, p.camera.zone,
                                  in ? "inside" : "OUTSIDE THE ROOM",
                                  nearest(lv, p.camera.zone, p.camera.x, p.camera.z));
            }
        }
        System.out.printf("%ncounters: beyond %d, byHeight %d, bySegment %d, "
                          + "slid %d, refused %d%n  second pass: seen %d, open %d, "
                          + "notPast %d, noStraddle %d, oldOutside %d, slid %d%n",
                          MoveObject.beyond, MoveObject.byHeight,
                          MoveObject.bySegment, MoveObject.slid,
                          MoveObject.refused, MoveObject.p2seen, MoveObject.p2open,
                          MoveObject.p2notPast, MoveObject.p2noStraddle,
                          MoveObject.p2oldOutside, MoveObject.p2slid);
        System.out.printf("%nangle %d: ended %s, %d of 90 steps outside; "
                          + "%d moves refused as stray in all%n", angle,
                          inside(lv, p.camera.zone, p.camera.x, p.camera.z)
                                  ? "inside" : "OUTSIDE",
                          wasOutside, MoveObject.refused);
    }

    /** On the inner side of every one of the room's own lines? */
    private static boolean inside(Level lv, int zi, int x, int z) {
        if (zi < 0 || zi >= lv.zones.length) {
            return false;
        }
        for (int e : lv.zone(zi).exitLines) {
            if (e >= 0 && e < lv.floorLines.length
                    && lv.floorLine(e).side(x, z) < 0) {
                return false;
            }
        }
        return true;
    }

    /** How far the point sits from the line it is closest to being past. */
    private static int nearest(Level lv, int zi, int x, int z) {
        if (zi < 0 || zi >= lv.zones.length) {
            return -1;
        }
        long best = Long.MAX_VALUE;
        Zone room = lv.zone(zi);
        for (int e : room.exitLines) {
            if (e < 0 || e >= lv.floorLines.length) {
                continue;
            }
            FloorLine fl = lv.floorLine(e);
            long len = (long) Math.sqrt((long) fl.dx * fl.dx + (long) fl.dz * fl.dz);
            if (len == 0) {
                continue;
            }
            best = Math.min(best, Math.abs(fl.side(x, z)) / len);
        }
        return best == Long.MAX_VALUE ? -1 : (int) best;
    }
}
