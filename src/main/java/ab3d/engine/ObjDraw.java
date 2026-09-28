package ab3d.engine;

import ab3d.data.GameObject;
import ab3d.m68k.M68k;

import java.util.List;

/**
 * {@code ObjDraw}, transcribed from source/objdraw3.chipram.
 *
 * The polygon loop reaches this with a two-byte record, and that record is not
 * an object: it is which part of the room to draw objects in. Zero means below
 * the water, one above it, anything else the whole room, and the choice sets the
 * top and bottom the sprites are clipped against. {@code ObjDraw} then walks the
 * <em>entire</em> object list itself, keeping the ones standing in the current
 * zone and on the current storey.
 *
 * Those survivors go into {@code depthtable}, eighty longwords of {depth,
 * index} kept sorted by an insertion that shuffles the tail down a slot at a
 * time. The table is pre-filled with {@code $80010000}, so a depth word of
 * {@code -32767} marks the unused slots and the draw loop stops at the first
 * one. An eighty-first object in view is dropped rather than sorted, and it is
 * the farthest that goes, since the list is walked from the front.
 */
public final class ObjDraw {

    /** Slots in {@code depthtable}. */
    public static final int MAX_SORTED = 80;
    /** {@code move.l #$80010000,(a3)+}: the depth word an unused slot holds. */
    private static final int EMPTY_DEPTH = (short) 0x8001;

    private final EngineState s;
    private final BitMapObj bitmap;
    /** The other renderer, for the objects that are models. May be absent. */
    private PolyObj poly;

    /** {@code depthtable}: depth and object index, nearest last. */
    private final int[] sortedDepth = new int[MAX_SORTED];
    private final int[] sortedIndex = new int[MAX_SORTED];

    /** {@code ty3d} and {@code by3d}, the room part in force. */
    public int ty3d, by3d;
    /** {@code currzone} and {@code DOUPPER}. */
    public int currZone;
    public boolean doUpper;

    /** Counters for checking. */
    public int considered, sorted, dropped, drawn, pixelsWritten;

    public ObjDraw(EngineState state, BitMapObj bitmap) {
        this.s = state;
        this.bitmap = bitmap;
    }

    /**
     * Gives it the model renderer.
     *
     * Separate from the constructor because the models and their textures may
     * not be there -- without them the sprites still draw, and the exit signs
     * and key indicators are simply missing, which is what this port did before
     * {@link PolyObj} existed.
     */
    public void setPoly(PolyObj p) {
        this.poly = p;
    }

    /** {@code move.w Facing(a0),ObjAng}: which way a model has been turned. */
    private int facingOf(GameObject o) {
        int base = s.level.ptrObjects + o.index * ab3d.data.GameObject.SIZE;
        if (!s.level.data.inRange(base, ab3d.data.GameObject.SIZE)) {
            return 0;
        }
        return s.level.data.s16(base + Obj.FACING);
    }

    /** The three room parts, as {@code ObjDraw} selects between them. */
    public static final int BEFORE_WATER = 0, AFTER_WATER = 1, FULL_ROOM = 2;

    /**
     * Draws every object of the current zone, farthest first.
     *
     * @param objects the level object list
     * @param top     {@code ty3d} for the part this record selected, and
     * @param bottom  its {@code by3d}
     */
    public void run(List<GameObject> objects, int top, int bottom) {
        ty3d = top;
        by3d = bottom;
        considered = 0;
        sorted = 0;
        dropped = 0;
        drawn = 0;
        pixelsWritten = 0;

        // move.w rightclip,d0 / sub.w leftclip,d0 / subq #1,d0 / ble
        if (M68k.w(s.rightClip - s.leftClip) - 1 <= 0) {
            return;
        }

        // emptytab
        for (int i = 0; i < MAX_SORTED; i++) {
            sortedDepth[i] = EMPTY_DEPTH;
            sortedIndex[i] = 0;
        }

        // insertanobj
        for (int i = 0; i < objects.size(); i++) {
            GameObject o = objects.get(i);
            // move.w (a1),d1 / blt sortedall
            if (o.pointIndex < 0) {
                break;
            }
            // cmp.w currzone,d2 / bne notinthiszone
            if (o.zone != currZone) {
                continue;
            }
            // move.b DOUPPER,d4 / eor.b d3,d4 / bne notinthiszone
            if (o.inUpperStorey != doUpper) {
                continue;
            }
            if (o.pointIndex >= s.objRotZ.length) {
                continue;
            }
            considered++;
            insert(s.objRotZ[o.pointIndex], i);
        }

        // gobackanddoanother
        for (int i = 0; i < MAX_SORTED; i++) {
            if (sortedDepth[i] <= 0) {
                break;                          // ble doneallinfront
            }
            GameObject o = objects.get(sortedIndex[i]);
            // cmp.b #$ff,6(a0) / bne BitMapObj: a width scale of $ff is not a
            // scale at all, it is the mark of a model
            if (!o.isSprite()) {
                if (poly != null && poly.draw(o, facingOf(o), ty3d, by3d)) {
                    drawn++;
                    pixelsWritten += poly.pixelsWritten;
                }
                continue;
            }
            if (bitmap.draw(o, ty3d, by3d)) {
                drawn++;
            }
            pixelsWritten += bitmap.pixelsWritten;
        }
    }

    /**
     * {@code stillinfront} and {@code finishedshift}.
     *
     * The scan stops at the first slot whose depth is not greater than the new
     * one, so the table runs from far to near and the draw loop needs no
     * reversal. The shift copies from the end backwards, which is what makes it
     * safe to overwrite in place.
     */
    private void insert(int depth, int index) {
        int at = 0;
        while (at < MAX_SORTED && depth < sortedDepth[at]) {
            at++;                               // cmp.w (a4),d1 / blt stillinfront
        }
        if (at >= MAX_SORTED) {
            dropped++;
            return;
        }
        for (int i = MAX_SORTED - 1; i > at; i--) {
            sortedDepth[i] = sortedDepth[i - 1];
            sortedIndex[i] = sortedIndex[i - 1];
        }
        sortedDepth[at] = depth;
        sortedIndex[at] = index;
        sorted++;
    }

    /** Depth of the entry at a slot, for checking the order. */
    public int depthAt(int slot) {
        return sortedDepth[slot];
    }
}
