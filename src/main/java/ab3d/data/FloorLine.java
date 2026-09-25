package ab3d.data;

/**
 * One wall segment, 16 bytes in the level file (the assembly indexes them with
 * {@code asl.w #4} on the line number).
 *
 * <pre>
 *   0 (w)  x1, z1 of the segment start -- always an entry of the point table
 *   4 (w)  dx, dz, the segment delta
 *   8 (b)  zone reached by crossing the line
 *   9 (b)  0, or 0xff together with byte 8 for a solid wall
 *  10 (w)  segment length
 *  12 (b)  two bytes derived from the segment orientation
 *  14 (w)  unused in the shipped data
 * </pre>
 *
 * The zone lives in the <em>high byte</em> of the word at offset 8: all 186
 * zones of lev1 appear there, and byte 9 is 0 for every portal. Reading offset 8
 * as a signed word -- which is what newsource/defs.s implies -- would misread
 * every zone from 128 up as a solid wall, so the port reads the byte.
 */
public final class FloorLine {

    public static final int SIZE = 16;

    /** Start point of the segment. */
    public final int x1, z1;
    /** Segment direction, used by the cross-product side test. */
    public final int dx, dz;
    /** Zone entered when crossing this line, or -1 when the line is solid. */
    public final int toZone;
    /** Length of the segment, used to step the wall texture. */
    public final int length;
    /** Orientation bytes at offset 12. */
    public final int ox, oz;
    /**
     * Where the record starts, so the word at offset 14 can be written.
     *
     * That word is how a mover tells a door it is there: {@code MoveObject} does
     * {@code or.w wallflags,14(a2)} when it comes within reach of the line, and
     * {@code DoorRoutine} reads it, resets it to {@code $8000} and matches it
     * against the door's own trigger mask.
     */
    public final int offset;

    /**
     * How the neighbouring zone is stored at offset 8. The shipped levels use a
     * plain signed word, which is what the assembly reads; the development
     * levels in the source tree put the zone in the high byte instead, with the
     * low byte zero and 0xffff for a solid wall.
     */
    public enum Encoding { WORD, HIGH_BYTE }

    FloorLine(BinReader r, int off, Encoding encoding) {
        this.offset = off;
        this.x1 = r.s16(off);
        this.z1 = r.s16(off + 2);
        this.dx = r.s16(off + 4);
        this.dz = r.s16(off + 6);

        int hi = r.u8(off + 8);
        int lo = r.u8(off + 9);
        if (encoding == Encoding.HIGH_BYTE) {
            this.toZone = (hi == 0xff && lo == 0xff) ? -1 : hi;
        } else {
            this.toZone = r.s16(off + 8);
        }

        this.length = r.s16(off + 10);
        this.ox = r.s8(off + 12);
        this.oz = r.s8(off + 13);
    }

    public boolean isSolid() {
        return toZone < 0;
    }

    /** End point of the segment. */
    public int x2() {
        return x1 + dx;
    }

    public int z2() {
        return z1 + dz;
    }

    /**
     * Sign of the cross product for point (x, z), as objectmove.s computes it:
     * {@code >= 0} means the point is on the side the engine treats as inside.
     */
    public long side(int x, int z) {
        return (long) (x - x1) * dz - (long) (z - z1) * dx;
    }

    @Override
    public String toString() {
        return String.format("FloorLine[(%d,%d)->(%d,%d) len=%d toZone=%s]",
                x1, z1, x2(), z2(), length, isSolid() ? "WALL" : Integer.toString(toZone));
    }
}
