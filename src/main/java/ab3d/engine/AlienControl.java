package ab3d.engine;

import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.Zone;
import ab3d.m68k.M68k;

/**
 * The routines every enemy shares, from source/aliencontrol.s and
 * source/ObjectMove.
 *
 * aliencontrol.s is mostly a wrapper: it pulls in eleven enemy files of about
 * nine hundred lines each, which are near-copies of one another, and keeps for
 * itself the handful of things they all call. Those are what is here.
 *
 * Six of the eleven are never placed in any shipped level -- the robot, the
 * worm, the big red thing, the tree, the eyeball and the gas pipe -- so about
 * four and a half thousand lines of that total are dead weight in this build.
 */
public final class AlienControl {

    private final Level lv;
    private final SineTable sine;

    public AlienControl(Level level, SineTable sine) {
        this.lv = level;
        this.sine = sine;
    }

    /**
     * {@code ViewpointToDraw}: which of a sprite's four sides faces the viewer.
     *
     * Nought is towards, one is its right, two is away and three is its left,
     * and the answer comes from two products of the direction to the player
     * against the thing's own facing -- one along it and one across it. The
     * sprite frame is then that number times four plus the animation's own
     * frame, which is why every enemy sheet is four frames wide.
     *
     * The original runs {@code HeadTowards} first to pull the target point sixty
     * units short of the player. At the distances this is used over, that shifts
     * the direction by a fraction of a degree and never changes which quadrant
     * comes out, so the direction is taken straight -- and if it ever did
     * matter, it would matter only at arm's length, where the enemy is already
     * attacking rather than prowling.
     */
    public int viewpointToDraw(int objX, int objZ, int facing, int viewX, int viewZ) {
        int d0 = M68k.w(viewX - objX);
        int d1 = M68k.w(viewZ - objZ);
        int d2 = sine.sinByte(facing);          // Facing is a byte offset
        int d3 = sine.cosByte(facing);

        long across = (long) M68k.muls(d0, d3) - M68k.muls(d1, d2);   // d4
        long along = (long) M68k.muls(d1, d3) + M68k.muls(d0, d2);    // d0

        if (along > 0) {                        // FacingTowardsPlayer
            if (across > 0) {
                return across > along ? 1 : 0;  // FTPR: right, else towards
            }
            return -across > along ? 3 : 0;     // left, else towards
        }
        if (across > 0) {                       // FAPR
            return across > -along ? 1 : 2;     // right, else away
        }
        return across > along ? 3 : 2;          // left, else away
    }

    /**
     * {@code GoInDirection}: a step of {@code speed} along a facing.
     *
     * The sine is doubled after the multiply and taken from the high word, so a
     * step is the speed times two units of the table's full scale.
     *
     * @return the new x in the low half and the new z in the high half
     */
    public long goInDirection(int oldX, int oldZ, int facing, int speed) {
        long dx = (long) M68k.muls(sine.sinByte(facing), speed) * 2;
        long dz = (long) M68k.muls(sine.cosByte(facing), speed) * 2;
        int nx = M68k.w(oldX + (int) (dx >> 16));
        int nz = M68k.w(oldZ + (int) (dz >> 16));
        return ((long) (nz & 0xffff) << 16) | (nx & 0xffff);
    }

    public static int newX(long packed) {
        return (short) (packed & 0xffff);
    }

    public static int newZ(long packed) {
        return (short) ((packed >> 16) & 0xffff);
    }

    /**
     * {@code CanItBeSeen}: is the target's zone one this zone can see?
     *
     * Not a ray cast. The zone's own graphics list is the answer -- if the room
     * the target stands in appears in the list of rooms this one draws, it can
     * be seen -- and in the same room the only test is that both are on the same
     * storey. The list holds graph numbers rather than zone numbers, so each one
     * has to be resolved through its record's first word; the {@code worry} loop
     * in jg.s uses the raw number instead, which is looser but only decides
     * which objects are worth looking at.
     *
     * This matters to the player as much as to the enemy: {@code Player1Shot}
     * will not aim at anything whose "sees player one" bit is clear, and that
     * bit is set here. A thing that cannot see the player cannot be shot.
     */
    public boolean canItBeSeen(int fromZone, int toZone, boolean fromTop,
                               boolean toTop) {
        if (fromZone < 0 || fromZone >= lv.zones.length) {
            return false;
        }
        if (fromZone == toZone) {
            return fromTop == toTop;            // insameroom: eor.b d0,d1 / bne
        }
        Zone here = lv.zone(fromZone);
        for (Zone.GraphEntry e : here.graphics) {
            int g = e.graphNumber();
            if (g < 0) {
                break;                          // tst.w d1 / blt outlist
            }
            if (zoneOfGraph(g) == toZone) {
                return true;                    // isinlist: st CanSee
            }
        }
        return false;
    }

    /** What {@code HeadTowardsAng} leaves behind it. */
    public record Approach(int x, int z, boolean gotThere, int facing, int dist) {}

    /**
     * {@code HeadTowardsAng}: close on a point, stopping short of it.
     *
     * The distance is an integer square root by three rounds of Newton's method
     * -- the original starts from the highest set bit and refines, which is why
     * the same block appears three times over. Inside {@code Range} the mover
     * stays put and {@code GotThere} is set; outside it, it steps {@code speed}
     * along the line and stops at {@code Range}.
     *
     * The facing it returns is not exact. It is four rounds of a binary search
     * over the sine table, halving from two thousand and forty-eight, so it
     * lands within a sixteenth of a turn -- enough to pick which of a sprite's
     * four sides shows, which is all it is used for.
     */
    public Approach headTowardsAng(int oldX, int oldZ, int toX, int toZ,
                                   int speed, int range) {
        int xdiff = M68k.w(toX - oldX);
        int zdiff = M68k.w(toZ - oldZ);
        long sq = (long) M68k.muls(xdiff, xdiff) + M68k.muls(zdiff, zdiff);
        if (sq == 0) {
            return new Approach(oldX, oldZ, true, 0, 0);   // seq GotThere
        }
        int d0 = isqrt(sq);

        int nx = oldX, nz = oldZ;
        boolean gotThere = d0 <= range;             // cmp.w Range,d0 / sle
        if (!gotThere) {                            // .faraway
            int d3 = speed + range;
            if (d3 >= d0) {
                d3 = d0;
                gotThere = true;                    // st GotThere
            }
            d3 -= range;
            nx = M68k.w(oldX + M68k.muls(xdiff, d3) / d0);
            nz = M68k.w(oldZ + M68k.muls(zdiff, d3) / d0);
        }
        return new Approach(nx, nz, gotThere, angleOf(xdiff, zdiff, d0), d0);
    }

    /**
     * The square root {@code HeadTowardsAng} uses: the highest set bit, halved,
     * then refined.
     */
    private static int isqrt(long a) {
        int top = 31;
        while (top > 0 && (a & (1L << top)) == 0) {
            top--;
        }
        int d0 = 1 << (top >> 1);
        for (int i = 0; i < 3; i++) {
            long d1 = ((long) d0 * d0 - a) / 2 / d0;
            d0 -= (int) d1;
            if (d0 <= 0) {
                d0 = 1;                             // .stillnot0
            }
        }
        return d0;
    }

    /** {@code findanglop}: four halvings of the sine table's quarter. */
    private int angleOf(int xdiff, int zdiff, int dist) {
        int d = dist + 1;
        int sinRet = (int) (((long) xdiff << 16) / 2 / d);
        int cosRet = (int) (((long) zdiff << 16) / 2 / d);
        int d2 = 0, d6 = 2048;
        for (int i = 0; i < 4; i++) {
            int ang = (d2 * 2) & 8190;
            long cross = (long) M68k.muls(sine.cosByte(ang), sinRet)
                       - M68k.muls(sine.sinByte(ang), cosRet);
            if (cross >= 0) {
                d2 += d6 * 2;
            }
            d2 = (d2 - d6) & 4095;
            d6 >>= 1;
        }
        return (d2 * 2) & 8190;
    }

    /**
     * {@code RunAround}: the sidestep that stops an enemy walking straight in.
     *
     * The target point is pushed sideways by half the distance still to cover,
     * and which side depends on which side of the player's own facing the enemy
     * stands -- so the thing circles rather than charges, and circles the way the
     * player is not looking.
     */
    public long runAround(int oldX, int oldZ, int toX, int toZ, int objX, int objZ,
                          int playerX, int playerZ, int sinval, int cosval) {
        int d0 = M68k.asrW(M68k.w(oldX - toX), 1);
        int d1 = M68k.asrW(M68k.w(oldZ - toZ), 1);
        long side = (long) M68k.muls(M68k.w(objX - playerX), cosval)
                  - M68k.muls(M68k.w(objZ - playerZ), sinval);
        if (side >= 0) {
            d0 = -d0;                               // headleft is the other way
            d1 = -d1;
        }
        int nx = M68k.w(toX - d1);
        int nz = M68k.w(toZ + d0);
        return ((long) (nz & 0xffff) << 16) | (nx & 0xffff);
    }

    /** {@code move.l (a1,d1.w*8),a1 / cmp.w (a1),d0}: a graph record's own zone. */
    public int zoneOfGraph(int graph) {
        if (graph < 0 || graph >= lv.graphics.numZones()) {
            return -1;
        }
        int off = lv.graphics.zoneOffset(graph);
        return lv.graphics.data.inRange(off, 2) ? lv.graphics.data.s16(off) : -1;
    }
}
