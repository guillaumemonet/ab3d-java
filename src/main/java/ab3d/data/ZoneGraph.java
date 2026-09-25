package ab3d.data;

import java.util.ArrayList;
import java.util.List;

/**
 * The drawing instructions for one zone, held in {@code <name>.graph.bin}.
 *
 * The stream is tagged, exactly as the {@code polyloop} dispatch in
 * source/master.s reads it: a zone number, then a run of [type][record] pairs
 * ended by a negative type.
 *
 * <pre>
 *   &lt;0      end of this zone
 *    0      wall            26 bytes
 *    1, 2   floor / roof    variable
 *    3      set clip        no record
 *    4      object           2 bytes
 *    5      arc             (unused in the shipped build)
 *    6      light beam      (unused in the shipped build)
 *    7      water           like a floor
 *    8, 9   chunky floor    like a floor
 *   10, 11  bumpy floor     like a floor
 *   12      backdrop        no record
 *   13      see-through wall 26 bytes
 * </pre>
 *
 * The wall record is <b>26 bytes</b>, not the 28 that {@code itsawalldraw}
 * appears to read: parsing lev1 at 26 yields 264 walls with no invalid point
 * index and no height outside its own zone, while 28 leaves ten walls with
 * heights outside their zone. The routine's trailing {@code wallbrightoff} read
 * lands on the next entry's type word.
 */
public final class ZoneGraph {

    /**
     * Wall record size. {@code itsawalldraw} reads 28 bytes, which is what the
     * shipped levels use; the development levels in the source tree pack them at
     * 26, so the routine's trailing {@code wallbrightoff} read lands on the next
     * entry's type word there.
     */
    public static final int WALL_RECORD_SHIPPED = 28;
    public static final int WALL_RECORD_DEV = 26;

    /** Type tags from the polyloop dispatch. */
    public static final int T_WALL = 0;
    public static final int T_FLOOR = 1;
    public static final int T_ROOF = 2;
    public static final int T_SET_CLIP = 3;
    public static final int T_OBJECT = 4;
    public static final int T_ARC = 5;
    public static final int T_LIGHT_BEAM = 6;
    public static final int T_WATER = 7;
    public static final int T_BACKDROP = 12;
    public static final int T_SEE_WALL = 13;

    /**
     * One textured wall.
     *
     * @param pointA     index of the left point in the level point table
     * @param pointB     index of the right point
     * @param uLeft      texture column at the left end
     * @param uRight     texture column at the right end
     * @param tile       tile number; the engine scales it by 16 into {@code fromtile}
     * @param vOffset    texture row offset ({@code totalyoff})
     * @param texture    index into the wall texture table
     * @param vMask      {@code VALAND}, the vertical wrap mask
     * @param vShift     {@code VALSHIFT}, log2 of the texture height
     * @param uMask      {@code HORAND}, the horizontal wrap mask
     * @param top        world Y of the wall top
     * @param bottom     world Y of the wall bottom
     * @param seeThrough true for a type 13 wall, which does not occlude
     */
    public record Wall(int pointA, int pointB, int uLeft, int uRight,
                       int tile, int vOffset, int texture,
                       int vMask, int vShift, int uMask,
                       int top, int bottom, boolean seeThrough) {

        /** Texture width in texels, from the horizontal wrap mask. */
        public int textureWidth() {
            return uMask + 1;
        }

        /** Texture height in texels, from the vertical wrap mask. */
        public int textureHeight() {
            return vMask + 1;
        }
    }

    /**
     * A floor, roof or water surface: a polygon of level points at one height.
     *
     * After the corner list the record carries a skipped word -- the last corner,
     * which source/master.s steps over with {@code addq #2,a0} -- then the
     * texture scale, the offset into the floor sheet, and a brightness added to
     * the zone's own.
     */
    public record Surface(int type, int y, int[] points,
                          int scale, int tile, int brightness) {

        public boolean isRoof() {
            return type == T_ROOF;
        }

        public boolean isFloor() {
            return type == T_FLOOR || type == T_WATER || type == 8 || type == 9
                    || type == 10 || type == 11;
        }
    }

    public final int zoneNumber;
    public final List<Wall> walls = new ArrayList<>();
    public final List<Surface> surfaces = new ArrayList<>();
    /** Set when the stream ran off the end rather than hitting its terminator. */
    public final boolean truncated;

    ZoneGraph(BinReader g, int offset, int limit, int wallRecordSize, int surfaceTrailing) {
        this.zoneNumber = g.s16(offset);
        int p = offset + 2;
        boolean terminated = false;

        while (p + 2 <= limit) {
            int type = g.s16(p);
            p += 2;
            if (type < 0) {
                terminated = true;
                break;
            }
            switch (type) {
                case T_WALL, T_SEE_WALL -> {
                    if (p + wallRecordSize > limit) {
                        p = limit;
                        break;
                    }
                    walls.add(readWall(g, p, type == T_SEE_WALL));
                    p += wallRecordSize;
                }
                case T_FLOOR, T_ROOF, T_WATER, 8, 9, 10, 11 -> {
                    if (p + 4 > limit) {
                        p = limit;
                        break;
                    }
                    int y = g.s16(p);
                    int n = g.s16(p + 2);
                    int[] pts = new int[Math.max(0, n + 1)];
                    for (int i = 0; i < pts.length && g.inRange(p + 4 + i * 2, 2); i++) {
                        pts[i] = g.s16(p + 4 + i * 2);
                    }
                    int after = p + 4 + 2 * (n + 1);
                    surfaces.add(new Surface(type, y, pts,
                            g.inRange(after + 2, 2) ? g.s16(after + 2) : 0,
                            g.inRange(after + 4, 2) ? g.s16(after + 4) : 0,
                            g.inRange(after + 6, 2) ? g.s16(after + 6) : 0));
                    // 2 (y) + 2 (n) + 2*(n+1) corners + a trailing block whose
                    // size varies between builds. source/master.s's
                    // dontdrawreturn skip understates it; the value that makes
                    // every stream reach its terminator is the one that counts.
                    p += 4 + 2 * (n + 1) + surfaceTrailing;
                }
                case T_OBJECT -> p += 2;      // ObjDraw reads one word
                case T_SET_CLIP, T_BACKDROP -> { }
                default -> {
                    p = limit;                // unknown tag: stop rather than drift
                }
            }
        }
        this.truncated = !terminated;
    }

    private static Wall readWall(BinReader g, int p, boolean seeThrough) {
        return new Wall(
                g.s16(p), g.s16(p + 2),
                g.s16(p + 4), g.s16(p + 6),
                g.s16(p + 8), g.s16(p + 10), g.s16(p + 12),
                g.u8(p + 14), g.u8(p + 15), g.u16(p + 16),
                g.s32(p + 18), g.s32(p + 22),
                seeThrough);
    }
}
