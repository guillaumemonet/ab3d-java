package ab3d.engine;

import ab3d.m68k.M68k;

/**
 * {@code CalcAndDraw}, transcribed from source/wallroutine3.chipmem.
 *
 * Takes the point list {@link WallSubdivide} produced and turns consecutive
 * pairs into screen strips: each pair gives a left and a right column, the
 * texture column at each end, the depth at each end, and the top and bottom rows
 * the wall occupies there. Every one of those rows is a plain {@code divs} of a
 * world height by the depth, which is where the perspective comes from.
 *
 * Two details are kept because they are the original's, not because they look
 * deliberate: the routine skips leading points that are behind the viewer and
 * gives up entirely if it never finds two in front, and the bottom row of a
 * strip's right edge is biased by 41 where the top uses 40. That extra row
 * carries over into the next strip's left edge.
 */
public final class CalcAndDraw {

    /** {@code maxscrdiv}: how far the strip rasteriser may subdivide again. */
    public static final int MAX_SCR_DIV = 8;
    /** The bias the brightness carries through the buffer and back out. */
    private static final int BRIGHT_BIAS = 300;

    /** One entry of {@code store}, the 28-byte record handed to the rasteriser. */
    public static final class Strip {
        public int leftColumn, rightColumn;       // +0, +2
        public int leftTexColumn, rightTexColumn; // +4, +6
        public int leftDepth, rightDepth;         // +8, +10
        public int leftTop, rightTop;             // +12, +14
        public int leftBottom, rightBottom;       // +16, +18
        public int leftBright, rightBright;       // +24, +26

        @Override
        public String toString() {
            return String.format("cols %d..%d rows %d..%d/%d..%d depth %d..%d u %d..%d",
                    leftColumn, rightColumn, leftTop, leftBottom,
                    rightTop, rightBottom, leftDepth, rightDepth,
                    leftTexColumn, rightTexColumn);
        }
    }

    private final EngineState s;

    /** Strips the last run produced, in left-to-right order. */
    public final java.util.List<Strip> strips = new java.util.ArrayList<>();
    /** Strips rejected because they fell wholly outside the clip bounds. */
    public int offLeft, offRight;

    public CalcAndDraw(EngineState state) {
        this.s = state;
    }

    /**
     * @param pts        the subdivided wall
     * @param topOfWall  world Y of the wall top, already relative to the eye
     * @param botOfWall  world Y of the wall bottom
     * @param multCount  {@code multcount}, how many points the walk may consume
     */
    public void run(WallSubdivide pts, int topOfWall, int botOfWall, int multCount) {
        strips.clear();
        offLeft = 0;
        offRight = 0;

        int a1 = 0;
        int d7 = multCount;

        // .findfirstinfront: skip points behind the viewer
        int d0, d1, d4, lbr;
        while (true) {
            if (a1 >= pts.count) {
                return;
            }
            d1 = pts.x[a1];
            d0 = M68k.w(pts.depth[a1]);
            if (d0 > 0) {
                break;
            }
            a1++;
            if (--d7 < 0) {
                return;                 // rts: no two points were in front
            }
        }
        // .foundinfront
        d4 = pts.column(a1);
        lbr = pts.bright(a1);
        a1++;

        // divs d0,d1 / add.w #47,d1
        d1 = M68k.w(M68k.w(M68k.divsInto(d1, d0)) + EngineState.CENTRE_X);

        int strTop = rowOf(topOfWall, d0, EngineState.CENTRE_Y);
        int strBot = rowOf(botOfWall, d0, EngineState.CENTRE_Y);

        // .computeloop
        while (a1 < pts.count) {
            int d2 = M68k.w(pts.depth[a1]);     // move.w 4(a1),d2
            if (d2 <= 0) {
                return;                          // ble: rts
            }
            int d3 = pts.x[a1];                  // move.l (a1),d3
            d3 = M68k.w(M68k.w(M68k.divsInto(d3, d2)) + EngineState.CENTRE_X);
            int d5 = pts.column(a1);             // move.w 6(a1),d5

            Strip st = new Strip();
            st.leftTop = strTop;                 // move.w strtop,12(a0)
            st.leftBottom = strBot;              // move.w strbot,16(a0)

            int newTop = rowOf(topOfWall, d2, EngineState.CENTRE_Y);
            strTop = newTop;
            st.rightTop = newTop;                // move.w d6,14(a0)

            // the bottom of the right edge is biased one row further down
            int newBot = rowOf(botOfWall, d2, EngineState.CENTRE_Y + 1);
            strBot = newBot;
            st.rightBottom = newBot;             // move.w d6,18(a0)

            if (d3 < s.leftClip) {
                offLeft++;                       // .alloffleft
            } else if (d1 >= s.rightClip) {
                offRight++;                      // .alloffright: rts in the original
                return;
            } else {
                st.leftColumn = d1;
                st.rightColumn = d3;
                st.leftTexColumn = d4;
                st.rightTexColumn = d5;
                st.leftDepth = d0;
                st.rightDepth = d2;
                st.leftBright = M68k.w(lbr - BRIGHT_BIAS);
                st.rightBright = M68k.w(pts.bright(a1) - BRIGHT_BIAS);
                strips.add(st);
            }

            // advance: the right edge becomes the next strip's left edge
            d1 = d3;
            d0 = d2;
            d4 = d5;
            lbr = pts.bright(a1);
            a1++;
            if (--d7 < 0) {
                return;                          // dbra d7,.computeloop
            }
        }
    }

    /** {@code divs d0,d5 / add.w #40,d5}: a world height projected to a row. */
    private static int rowOf(int worldY, int depth, int centre) {
        return M68k.w(M68k.w(M68k.divsInto(worldY, depth)) + centre);
    }
}
