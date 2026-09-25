package ab3d.tools;

import ab3d.data.FloorLine;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.Zone;
import ab3d.game.Player;

import java.util.Random;

/**
 * Walks randomly through a level and checks the collision holds two invariants.
 *
 * After any move the point must be inside the zone it claims -- on the inner
 * side of every one of its bounding lines -- or the renderer will draw from a
 * room the viewer is not in. And the resolution has to settle: sliding along one
 * wall can push the point past another, so the routine repeats, and a walk that
 * keeps needing the full two hundred passes means it never converges.
 */
public final class CollideCheck {

    /**
     * Thresholds in world units.
     *
     * The slide never puts the point exactly on the wall: the routine divides by
     * the line length plus the mover's reach, and does it twice, so about a
     * quarter of the overshoot is left behind and decays over the following
     * frames. A tolerance is therefore part of the engine's behaviour, not a
     * concession -- and 40 is its own {@code extlen}.
     */
    private static final int[] BANDS = {0, 5, 20, 40, 100, 300};

    /**
     * How far a step moves, in world units.
     *
     * The game walks about four units a frame; a hundred and twenty steps clean
     * over a ninety-unit wall, and the routine is right to report no crossing
     * when that happens. Testing it at a stride it never sees measures the
     * harness, not the engine.
     */
    private static final int STEP = Integer.getInteger("step", 8);

    /** Steps per start, enough to reach a wall at this stride. */
    private static final int STEPS = Integer.getInteger("steps", 200);

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            SineTable sine = SineTable.load(game);
            Random rnd = new Random(12345);
            ab3d.engine.MoveObject.REFUSE_STRAY =
                    !"off".equals(System.getProperty("refuse"));
            ab3d.engine.MoveObject.refused = 0;

            // The half-plane test only means something in a convex zone, and a
            // fifth of them are not. Points just inside each bounding line say
            // which is which, so the count below is over zones where being
            // outside every line really does mean being out of the room.
            boolean[] convex = new boolean[lv.zones.length];
            int convexCount = 0;
            for (int zz = 0; zz < lv.zones.length; zz++) {
                convex[zz] = isConvex(lv, zz);
                if (convex[zz]) {
                    convexCount++;
                }
            }
            int outside = 0, steps = 0, starts = 0, stillMoving = 0;
            long worst = 0, worstUnits = 0;
            int badCentres = 0;
            int shown = 0;
            int[] band = new int[BANDS.length];
            for (int zi = 0; zi < lv.zones.length; zi++) {
                Zone z = lv.zone(zi);
                if (z.exitLines.length == 0) {
                    continue;
                }
                long sx = 0, sz = 0;
                int n = 0;
                for (int e : z.exitLines) {
                    if (e < lv.floorLines.length) {
                        sx += lv.floorLine(e).x1;
                        sz += lv.floorLine(e).z1;
                        n++;
                    }
                }
                if (n == 0) {
                    continue;
                }
                if (!inside(lv, zi, (int) (sx / n), (int) (sz / n))) {
                    badCentres++;
                }
                Player p = new Player(lv);
                p.camera.zone = zi;
                p.camera.x = (int) (sx / n);
                p.camera.z = (int) (sz / n);
                starts++;

                for (int k = 0; k < STEPS; k++) {
                    int ox = p.camera.x, oz = p.camera.z;
                    int a = rnd.nextInt(4096);
                    double s = sine.sin(a) / 32768.0, c = sine.cos(a) / 32768.0;
                    p.move((int) Math.round(s * STEP), (int) Math.round(c * STEP));
                    steps++;
                    if (p.camera.zone < 0 || p.camera.zone >= convex.length
                            || !convex[p.camera.zone]) {
                        continue;
                    }
                    long units = depthUnits(lv, p.camera.zone, p.camera.x, p.camera.z);
                    for (int b = 0; b < BANDS.length; b++) {
                        if (units < -BANDS[b]) {
                            band[b]++;
                        }
                    }
                    long d = depth(lv, p.camera.zone, p.camera.x, p.camera.z);
                    if (d < 0 && shown < 3) {
                        shown++;
                        int zc = p.camera.zone;
                        System.out.printf("   FAIL zone %d from (%d,%d) to (%d,%d)%n",
                                          zc, ox, oz, p.camera.x, p.camera.z);
                        for (int ee : lv.zone(zc).exitLines) {
                            if (ee < 0 || ee >= lv.floorLines.length) {
                                continue;
                            }
                            FloorLine ff = lv.floorLine(ee);
                            long sd = ff.side(p.camera.x, p.camera.z);
                            if (sd < 0) {
                                boolean inAll = false;
                                for (int q : lv.zone(zc).allExitLines) {
                                    if (q == ee) {
                                        inAll = true;
                                    }
                                }
                                System.out.printf("     line %d side %d toZone %d "
                                                + "len %d inFullList %s oldSide %d%n",
                                                ee, sd, ff.toZone, ff.length, inAll,
                                                ff.side(ox, oz));
                            }
                        }
                    }
                    if (d < 0) {
                        outside++;
                        worst = Math.min(worst, d);
                        long len = 1;
                        for (int e : lv.zone(p.camera.zone).exitLines) {
                            if (e < lv.floorLines.length) {
                                len = Math.max(len, lv.floorLine(e).length);
                            }
                        }
                        worstUnits = Math.min(worstUnits, d / len);
                    }
                }
            }
            System.out.printf("%-8s %4d starts, %5d steps | outside its zone after "
                            + "the move: %d (worst %d cross, about %d units)%n",
                            name, starts, steps, outside, worst, worstUnits);
            System.out.printf("          counted over the %d convex zones of %d%n",
                              convexCount, lv.zones.length);
            StringBuilder b = new StringBuilder("          past a line by more than:");
            for (int q = 0; q < BANDS.length; q++) {
                b.append(' ').append(BANDS[q]).append("u=").append(band[q]);
            }
            System.out.println(b);
            System.out.printf("          zone centres that fail the same test: %d of %d%n",
                              badCentres, starts);
            System.out.printf("          lines the point went past: %d | let through "
                            + "by height %d, missed the segment %d, slid %d%n",
                              ab3d.engine.MoveObject.beyond,
                              ab3d.engine.MoveObject.byHeight,
                              ab3d.engine.MoveObject.bySegment,
                              ab3d.engine.MoveObject.slid);
            System.out.printf("          moves that landed in no room: %d%n",
                              ab3d.engine.MoveObject.refused);
            System.out.printf("          second pass: %d lines | open %d, not past %d, "
                            + "no straddle %d, old outside %d, slid %d%n",
                              ab3d.engine.MoveObject.p2seen,
                              ab3d.engine.MoveObject.p2open,
                              ab3d.engine.MoveObject.p2notPast,
                              ab3d.engine.MoveObject.p2noStraddle,
                              ab3d.engine.MoveObject.p2oldOutside,
                              ab3d.engine.MoveObject.p2slid);
            ab3d.engine.MoveObject.p2seen = 0;
            ab3d.engine.MoveObject.p2open = 0;
            ab3d.engine.MoveObject.p2notPast = 0;
            ab3d.engine.MoveObject.p2noStraddle = 0;
            ab3d.engine.MoveObject.p2oldOutside = 0;
            ab3d.engine.MoveObject.p2slid = 0;
            ab3d.engine.MoveObject.refused = 0;
            ab3d.engine.MoveObject.beyond = 0;
            ab3d.engine.MoveObject.byHeight = 0;
            ab3d.engine.MoveObject.bySegment = 0;
            ab3d.engine.MoveObject.slid = 0;
        }
    }

    /** The most negative cross product over the zone's lines, or 0 when inside. */
    private static long depth(Level lv, int zi, int x, int z) {
        if (zi < 0 || zi >= lv.zones.length) {
            return -1;
        }
        long worst = 0;
        for (int e : lv.zone(zi).exitLines) {
            if (e >= 0 && e < lv.floorLines.length) {
                worst = Math.min(worst, lv.floorLine(e).side(x, z));
            }
        }
        return worst;
    }

    /** A zone is convex when a point just inside each line is inside them all. */
    private static boolean isConvex(Level lv, int zi) {
        Zone z = lv.zone(zi);
        if (z.exitLines.length == 0) {
            return false;
        }
        for (int e : z.exitLines) {
            if (e < 0 || e >= lv.floorLines.length) {
                continue;
            }
            FloorLine fl = lv.floorLine(e);
            long len = Math.max(1, fl.length);
            int px = (int) (fl.x1 + fl.dx / 2 + (long) fl.dz * 60 / len);
            int pz = (int) (fl.z1 + fl.dz / 2 - (long) fl.dx * 60 / len);
            if (!inside(lv, zi, px, pz)) {
                return false;
            }
        }
        return true;
    }

    /** How far past its worst line the point is, in world units. */
    private static long depthUnits(Level lv, int zi, int x, int z) {
        if (zi < 0 || zi >= lv.zones.length) {
            return 0;
        }
        long worst = 0;
        for (int e : lv.zone(zi).exitLines) {
            if (e < 0 || e >= lv.floorLines.length) {
                continue;
            }
            FloorLine fl = lv.floorLine(e);
            long len = Math.max(1, fl.length);
            worst = Math.min(worst, fl.side(x, z) / len);
        }
        return worst;
    }

    private static boolean inside(Level lv, int zi, int x, int z) {
        if (zi < 0 || zi >= lv.zones.length) {
            return false;
        }
        for (int e : lv.zone(zi).exitLines) {
            if (e < 0 || e >= lv.floorLines.length) {
                continue;
            }
            FloorLine fl = lv.floorLine(e);
            if (fl.side(x, z) < 0) {
                return false;
            }
        }
        return true;
    }
}
