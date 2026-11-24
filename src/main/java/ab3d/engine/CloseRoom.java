package ab3d.engine;

import ab3d.data.Level;
import ab3d.m68k.M68k;

/**
 * {@code FindCloseRoom}, from source/ObjectMove: which room a big sprite is
 * drawn in.
 *
 * An object is drawn by the zone its {@code GraphicRoom} names, clipped to
 * that zone's window, and a zone is drawn before the nearer ones that paint
 * over it. A worm ninety units wide standing by a doorway is in one room but
 * pokes into the next, and drawn with the far one it would be cut off by the
 * near one's walls. So the worm, the flying alien and the robot ask instead:
 *
 * <ol>
 * <li>take a step of {@code d0} towards the viewer, and turn it a quarter --
 *     a vector across the line of sight, as long as the thing is wide;</li>
 * <li>send a probe that far to one side and then to the other, through
 *     {@code MoveObject} with {@code exitfirst} set, so it stops at a wall,
 *     and keep every room either probe passed through, with the thing's own;</li>
 * <li>walk the frame's draw order from the far end and keep the last of those
 *     rooms met -- the one drawn latest, which is the nearest.</li>
 * </ol>
 *
 * Two departures. The probes use no height at all ({@code thingheight} is
 * nought and the steps a hundred thousand), so only a solid wall stops them;
 * that much is the original's. But its second probe starts from whatever room
 * the first one ended in, while starting from the thing's own point -- which
 * may not be inside that room at all. Here both start from the thing's own
 * room. And the draw order is the last frame's, since the objects move before
 * the frame is drawn; the original's loop has the same order of events.
 */
public final class CloseRoom {

    /** {@code move.l #100000,StepUpVal} and {@code StepDownVal}. */
    private static final int ANY_STEP = 100000;

    private final Level lv;
    private final MoveObject probe;
    /** The frame's {@code endoflist}, once there is a frame. */
    public OrderZones order;

    /** Calls, and how many picked a room other than the thing's own. */
    public int asked, moved;

    public CloseRoom(Level level) {
        this.lv = level;
        this.probe = new MoveObject(level);
        this.probe.exitFirst = true;               // st exitfirst
    }

    /**
     * @param dist   {@code d0}: half the width to look across
     * @param extLen {@code extlen}, which is whatever the caller last set
     * @return the room to draw the thing in
     */
    public int find(int base, int dist, int extLen, int viewX, int viewZ) {
        asked++;
        int zone = lv.data.s16(base + Obj.ZONE);
        int pt = lv.data.s16(base + Obj.POINT);
        if (order == null || zone < 0 || pt < 0 || pt >= lv.objectPointX.length) {
            return zone;
        }
        int x = lv.objectPointX[pt], z = lv.objectPointZ[pt];
        int y = lv.data.s16(base + Obj.HEIGHT) << 7;

        // jsr HeadTowards with speed d0: a step that long towards the viewer
        int dx = M68k.w(viewX - x), dz = M68k.w(viewZ - z);
        int len = (int) Math.sqrt((long) dx * dx + (long) dz * dz);
        if (len == 0) {
            return zone;
        }
        int sx = M68k.muls(dx, Math.min(dist, len)) / len;
        int sz = M68k.muls(dz, Math.min(dist, len)) / len;
        // sub.w newz from oldz into xd, newx less oldx into zd: a quarter turn
        int xd = -sz, zd = sx;

        java.util.Set<Integer> passed = new java.util.HashSet<>();
        passed.add(zone);
        for (int side = 1; side >= -1; side -= 2) {
            probe.enterStorey(lv.data.u8(base + Obj.IN_TOP) != 0);
            probe.move(zone, x, z, M68k.w(x + side * xd), M68k.w(z + side * zd),
                       y, y, extLen, 0, ANY_STEP, ANY_STEP, 0);
            passed.addAll(probe.roomPath);
        }

        // FINDCLOSELOOP: from endoflist backwards, the last match wins
        int pick = zone;
        for (int i = order.orderCount - 1; i >= 0; i--) {
            if (passed.contains(order.finalOrder[i])) {
                pick = order.finalOrder[i];
            }
        }
        if (pick != zone) {
            moved++;
        }
        return pick;
    }
}
