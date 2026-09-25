package ab3d.engine;

import ab3d.data.BinReader;
import ab3d.m68k.M68k;

/**
 * {@code itsawalldraw}, transcribed from source/wallroutine3.chipmem.
 *
 * The routine reads a 28-byte record, decides whether the wall can be seen at
 * all, and only then hands it to the subdivision that rasterises it. The
 * decisions are the interesting part and they come in a fixed order:
 *
 * <ol>
 *   <li>a wall whose top is not above its bottom faces away;</li>
 *   <li>with either end behind the viewer, the routine works out where the wall
 *       crosses and compares that against the projected column of the other end,
 *       which is what rejects a wall seen from its back face;</li>
 *   <li>with both ends in front, it rejects anything wholly off one side, and
 *       anything whose left column is not left of its right column.</li>
 * </ol>
 *
 * Brightness comes from the per-point table, biased by 300 and by the record's
 * own {@code wallbrightoff}.
 */
public final class WallDraw {

    /** {@code max3ddiv}: how many times the subdivision may halve a wall. */
    public static final int MAX_3D_DIV = 5;
    /** The bias {@code add.w #300,d0} applies to every point brightness. */
    private static final int BRIGHT_BIAS = 300;
    /** {@code cmp.w #95,d3}: the rightmost column a wall may start at. */
    private static final int LAST_COLUMN = 95;

    /** What the routine decided, and the parameters it would pass on. */
    public static final class Wall {
        public int pointA, pointB;
        public int leftEnd, rightEnd;
        public int fromTile;        // the record's tile, scaled by 16
        public int totalYoff;
        public int textureIndex;
        public int valAnd, valShift, horAnd;
        public int topOfWall, botOfWall;   // already relative to the eye
        public int wallBrightOff;
        public int leftBright, rightBright;
        /** False when one of the rejection tests fired. */
        public boolean visible;
        /** Which test rejected it, for checking the transcription. */
        public String rejectedBy;
    }

    /** How often each decision path was taken, so no branch goes untested. */
    public int pathBothInFront, pathFirstBehind, pathSecondBehind, pathBothBehind;
    public int divideUnusable;
    /** Sampled crossing tests, for checking the comparison is the right way round. */
    public final java.util.List<long[]> crossingSamples = new java.util.ArrayList<>();

    private final EngineState s;

    public WallDraw(EngineState state) {
        this.s = state;
    }

    /**
     * Reads and tests one wall record.
     *
     * @param g  the level's graphics file
     * @param at offset of the record, just past its type word
     */
    public Wall read(BinReader g, int at) {
        Wall w = new Wall();

        // move.w (a0)+,d0 / move.w (a0)+,d2 / move.w (a0)+,leftend / move.w (a0)+,d5
        w.pointA = g.s16(at);
        w.pointB = g.s16(at + 2);
        w.leftEnd = g.s16(at + 4);
        w.rightEnd = g.s16(at + 6);
        // move.w (a0)+,d1 / asl.w #4,d1 / move.w d1,fromtile
        w.fromTile = M68k.w(g.s16(at + 8) << 4);
        w.textureIndex = g.s16(at + 12);
        w.valAnd = g.u8(at + 14);
        w.valShift = g.u8(at + 15);
        w.horAnd = g.u16(at + 16);
        // move.w totalyoff,d1 / add.w wallyoff,d1 / and.w VALAND,d1: the record's
        // own offset plus the eye height, wrapped by the texture height. Reading
        // the record alone leaves the wall texture fixed while the eye moves.
        w.totalYoff = M68k.w(M68k.w(g.s16(at + 10) + s.wallYoff)) & w.valAnd;
        // move.l (a0)+,topofwall / sub.l d6,topofwall   (d6 = yoff)
        w.topOfWall = g.s32(at + 18) - s.yoff;
        w.botOfWall = g.s32(at + 22) - s.yoff;
        w.wallBrightOff = g.s16(at + 26);

        int d0 = w.pointA, d2 = w.pointB;
        if (d0 < 0 || d0 >= s.onScreen.length || d2 < 0 || d2 >= s.onScreen.length) {
            w.rejectedBy = "point out of range";
            return w;
        }

        // move.l topofwall,d3 / cmp.l botofwall,d3 / bge wallfacingaway
        if (w.topOfWall >= w.botOfWall) {
            w.rejectedBy = "top not above bottom";
            return w;
        }

        int depthA = s.depth(d0);           // tst.w 6(a5,d0*8)
        int depthB = s.depth(d2);           // tst.w 6(a5,d2*8)

        if (depthA <= 0) {
            // tst.w 6(a5,d2*8) / ble wallfacingaway
            if (depthB <= 0) {
                pathBothBehind++;
                w.rejectedBy = "both ends behind";
                return w;
            }
            // cliptotestfirstbehind
            pathFirstBehind++;
            if (!crossingKeepsWall(d0, d2, true)) {
                w.rejectedBy = "first end behind, facing away";
                return w;
            }
        } else if (depthB <= 0) {
            // cliptotestsecbehind
            pathSecondBehind++;
            if (!crossingKeepsWall(d2, d0, false)) {
                w.rejectedBy = "second end behind, facing away";
                return w;
            }
        } else {
            pathBothInFront++;
            // pastclip: both ends in front, so the columns decide
            int left = s.onScreen[d0];
            int right = s.onScreen[d2];
            if (left > LAST_COLUMN) {
                w.rejectedBy = "starts past the right edge";
                return w;
            }
            if (left >= right) {
                w.rejectedBy = "left column not left of right";
                return w;
            }
            if (right < 0) {
                w.rejectedBy = "ends left of the screen";
                return w;
            }
        }

        // cant_tell: brightness from the per-point table
        w.leftBright = pointBright(d0) + BRIGHT_BIAS + w.wallBrightOff;
        w.rightBright = pointBright(d2) + BRIGHT_BIAS + w.wallBrightOff;
        w.visible = true;
        return w;
    }

    /**
     * The {@code cliptotest*behind} pair: with one end behind the viewer, work
     * out where the wall crosses the eye plane and compare that against the
     * other end's projected column.
     *
     * @param front the end that is in front, as the routine orders its operands
     * @param behind the end that is behind
     * @param firstBehind true for {@code cliptotestfirstbehind}, whose comparison
     *                    rejects on greater-or-equal rather than less-or-equal
     */
    private boolean crossingKeepsWall(int behind, int front, boolean firstBehind) {
        // move.l (a5,dA*8),d3 / sub.l (a5,dB*8),d3
        int d3 = s.rotatedX[behind] - s.rotatedX[front];
        // move.w 6(a5,dA*8),d6 / sub.w 6(a5,dB*8),d6
        int d6 = M68k.w(s.depth(behind) - s.depth(front));
        if (d6 == 0) {
            divideUnusable++;
            return true;                    // a divide by zero would trap
        }
        // divs d6,d3 / muls 6(a5,dB*8),d3 / neg.l d3 / add.l (a5,dB*8),d3
        d3 = M68k.muls(M68k.w(M68k.divsInto(d3, d6)), s.depth(front));
        d3 = -d3 + s.rotatedX[front];
        // move.w (a6,dB*2),d6 / sub.w #47,d6 / ext.l d6
        int edge = M68k.extL(M68k.w(s.onScreen[front] - EngineState.CENTRE_X));
        // cmp.l d6,d3 / bge (or ble) wallfacingaway
        if (crossingSamples.size() < 400) {
            crossingSamples.add(new long[] { firstBehind ? 1 : 0, d3, edge });
        }
        return firstBehind ? d3 < edge : d3 > edge;
    }

    /** {@code PointBrights}, one word per point, read at {@code (a5,d0.w*4)}. */
    private int pointBright(int point) {
        int[] table = s.currentPointBright != null
                ? s.currentPointBright : s.level.pointBright;
        return point >= 0 && point < table.length ? table[point] : 0;
    }
}
