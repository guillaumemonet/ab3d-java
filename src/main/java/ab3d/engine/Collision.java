package ab3d.engine;

import ab3d.data.ColBox;
import ab3d.data.Level;
import ab3d.m68k.M68k;

/**
 * {@code Collision}, from source/ObjectMove: things do not walk through each
 * other.
 *
 * The enemies call it before {@code MoveObject}, with {@code CollideFlags}
 * naming which types they may bump into, and the player does the same. It
 * walks the whole object list and takes the first that is in play, alive, on
 * the same storey, of a type the flags name, and inside a box:
 *
 * <ul>
 * <li>vertically, the mover's span -- from {@code newy} down
 *     {@code thingheight} -- has to meet the other's, which {@code ColBoxTable}
 *     puts from its second word below the object's height, its third word
 *     tall;</li>
 * <li>on the ground, the larger of the two distances, less the mover's own
 *     half-width, has to be inside the other's half-width -- a square, not a
 *     circle;</li>
 * <li>and the move has to bring them closer. Moving away from something
 *     already touching is always allowed, which is what stops two things
 *     locking together.</li>
 * </ul>
 *
 * A hit sets {@code hitwall} and the caller stays where it was. The alien
 * asks twice when it attacks: once for the player alone -- touching them is
 * arriving, and it bites from there -- and once for everything else.
 *
 * Without it nothing kept an alien off the player, and one that came within
 * eighty units could not be aimed at: {@code Player1Shot} drops a target whose
 * height difference, over forty-four, is more than twice the distance. They
 * pressed in, bit, and the shots went past them.
 */
public final class Collision {

    private final Level lv;
    private final ColBox boxes;

    /** Moves refused, for checking. */
    public int hits;

    public Collision(Level level, ColBox boxes) {
        this.lv = level;
        this.boxes = boxes;
    }

    /**
     * @param selfPoint   {@code CollId}: the mover's own point, skipped
     * @param selfType    the type whose half-width the mover has
     * @param newY        {@code newy}, the top of the mover, in level units
     * @param thingHeight {@code thingheight}
     * @param flags       {@code CollideFlags}
     * @return {@code hitwall}
     */
    public boolean blocked(int selfPoint, int selfType, boolean inTop, int newY,
                           int thingHeight, int oldX, int oldZ, int newX,
                           int newZ, int flags) {
        int d4 = newY >> 7;                          // asr.l #7,d4
        int d5 = (newY + thingHeight) >> 7;          // add.l thingheight / asr.l #7
        int own = boxes.width(selfType);             // lea (a2,d0.w*8),a3
        for (int base = lv.ptrObjects; lv.data.inRange(base, Obj.SIZE);
             base += Obj.SIZE) {
            int pt = lv.data.s16(base + Obj.POINT);
            if (pt < 0) {
                break;                               // blt checkedallcol
            }
            if (pt == selfPoint || pt >= lv.objectPointX.length) {
                continue;                            // cmp.w CollId,d0
            }
            if (lv.data.s16(base + Obj.ZONE) < 0
                    || lv.data.u8(base + Obj.NUM_LIVES) == 0) {
                continue;
            }
            if ((lv.data.u8(base + Obj.IN_TOP) != 0) != inTop) {
                continue;                            // eor.b d6,d1 / bne
            }
            int type = lv.data.s8(base + Obj.TYPE);
            if (type < 0 || type > 31 || (flags & (1 << type)) == 0) {
                continue;                            // btst d3,d7
            }
            // move.w 4(a0),d1 / sub.w 2(a2,d3.w*8),d1 / cmp.w d1,d5 / blt
            int low = M68k.w(lv.data.s16(base + Obj.HEIGHT) - boxes.height(type));
            if (d5 < low) {
                continue;
            }
            // add.w 4(a2,d3.w*8),d1 / cmp.w d1,d4 / bgt
            if (d4 > M68k.w(low + boxes.span(type))) {
                continue;
            }
            int ox = lv.objectPointX[pt], oz = lv.objectPointZ[pt];
            int ax = Math.abs(M68k.w(ox - newX));
            int az = Math.abs(M68k.w(oz - newZ));
            // cmp.w d1,d2 / ble .checkx: the larger of the two, less our own
            int gap = M68k.w(Math.max(ax, az) - own);
            if (gap > boxes.width(type)) {
                continue;                            // cmp.w (a2,d3.w*8) / bgt
            }
            long dNew = sq(M68k.w(ox - newX)) + sq(M68k.w(oz - newZ));
            long dOld = sq(M68k.w(ox - oldX)) + sq(M68k.w(oz - oldZ));
            if (dNew > dOld) {
                continue;                            // cmp.l d2,d7 / bgt: moving away
            }
            hits++;
            return true;                             // st hitwall
        }
        return false;
    }

    private static long sq(int v) {
        return (long) v * v;
    }
}
