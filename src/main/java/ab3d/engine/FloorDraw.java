package ab3d.engine;

import ab3d.data.BinReader;
import ab3d.data.FloorTexture;
import ab3d.m68k.M68k;

/**
 * {@code itsafloordraw}, transcribed from source/master.s.
 *
 * A floor or ceiling is a polygon at one height. The routine first decides
 * whether any of it can be seen, then classifies each corner, then walks the
 * edges into span tables, and finally fills those spans from the floor sheet.
 *
 * Two things about it are worth stating up front.
 *
 * The eye height is scaled differently here than for walls: the setup keeps
 * {@code flooryoff = yoff * 4} where the wall path uses {@code wallyoff = yoff &
 * 63}. Mixing the two puts a floor at the wrong distance.
 *
 * The span filler, {@code doacrossline}, is not assembly in the source tree at
 * all -- it is a 960-byte binary blob, and newsource has no readable equivalent.
 * Disassembling it shows straight-line code with no return: 96 entries, one per
 * view column, each reading the byte already at that screen slot, looking it up
 * in a palette and writing back a colour word. The caller jumps in at the entry
 * for its first column and falls through to the end, which is how it draws a run
 * of exactly the right length.
 *
 * Which palette each column uses alternates between three in a fixed pattern --
 * ordered dithering that smooths the brightness gradient along a span. That
 * pattern is data, not logic, so it is captured here rather than invented.
 */
public final class FloorDraw {

    /** {@code d0} on entry: 1 draws a floor, 2 a ceiling. */
    public static final int FLOOR = 1, ROOF = 2;


    /** Corner classification, the flags {@code d4}, {@code d5} and {@code d6} hold. */
    public static final class Corners {
        /** {@code d5}: at least one corner is on screen, or behind the viewer. */
        public boolean anyVisible;
        /** {@code d4}: a corner falls off the left. */
        public boolean offLeft;
        /** {@code d6}: a corner falls off the right. */
        public boolean offRight;
        /** {@code anyclipping}: something needs clipping. */
        public boolean anyClipping;
    }

    /** What the setup decided before any drawing. */
    public static final class Setup {
        /** False when one of the early tests sent the routine to dontdrawreturn. */
        public boolean visible;
        /** {@code above}: set when a ceiling is being drawn from below. */
        public boolean above;
        /** {@code bottomline}: rows between the horizon and the clip edge. */
        public int bottomLine;
        /** {@code ypos}: the plane's height above the eye, scaled by 64. */
        public int ypos;
        /** {@code minz}: depth of the furthest visible row. */
        public int minZ;
        /** {@code distaddr}: the unscaled height difference. */
        public int distAddr;
        /** Number of corners the record carries. */
        public int sides;
        /** Bytes the record occupies, so the dispatch can step over it. */
        public int recordBytes;
        /** Which test sent the routine to dontdrawreturn, for checking. */
        public String rejectedBy;
    }

    private final EngineState s;

    public FloorDraw(EngineState state) {
        this.s = state;
    }

    /**
     * The opening of the routine: the tests that can reject the surface outright,
     * and the values the rest of it works from.
     *
     * @param kind {@link #FLOOR} or {@link #ROOF}
     * @param at   offset of the record, just past its type word
     */
    public Setup setup(BinReader g, int at, int kind) {
        Setup r = new Setup();
        int d6 = M68k.w(g.s16(at));              // move.w (a0)+,d6 : ypos of poly
        r.sides = g.s16(at + 2);

        // The RTG build rejects a surface outside the room part before doing any
        // other work:
        //   cmp.l TOPOFROOM,d7 / blt checkforwater
        //   cmp.l BOTOFROOM,d7 / bgt dontdrawreturn
        // checkforwater only raises the water flag and then falls into the same
        // record skip, so both branches are rejections. master.s has neither.
        int d7room = M68k.aslL(M68k.extL(d6), 6);
        if (d7room < s.topOfRoom) {
            r.rejectedBy = "above the room";
            return r;
        }
        if (d7room > s.botOfRoom) {
            r.rejectedBy = "below the room";
            return r;
        }
        // dontdrawreturn skips 2 (ypos) + 2 (sides) + 2*sides + 10
        r.recordBytes = 4 + 2 * r.sides + 10;

        // move.w leftclip,d7 / cmp.w rightclip,d7 / bge dontdrawreturn
        if (s.leftClip >= s.rightClip) {
            r.rejectedBy = "no columns to draw";
            return r;
        }
        // move.w botclip,d7 / sub.w #40,d7 / ble dontdrawreturn
        int d7 = M68k.w(s.botClip - EngineState.CENTRE_Y);
        if (d7 <= 0) {
            r.rejectedBy = "clip leaves no rows below the horizon";
            return r;
        }

        d6 = M68k.w(d6 - s.floorYoff);           // sub.w flooryoff,d6
        int d0 = kind;
        if (d6 == 0) {
            r.rejectedBy = "plane at eye height";
            return r;
        }
        if (d6 < 0) {
            // aboveplayer: btst #1,d0 -- the tag is a mask, not a number, so a
            // surface marked as both floor and roof (the water tag passes 3)
            // passes this test as well as the one below. master.s compares for
            // equality here and would reject it.
            if ((d0 & ROOF) == 0) {
                r.rejectedBy = "floor above the eye";
                return r;
            }
            d7 = M68k.w(EngineState.CENTRE_Y - s.topClip);
            if (d7 <= 0) {                       // ble.s dontdrawreturn
                r.rejectedBy = "clip leaves no rows above the horizon";
                return r;
            }
            d0 = FLOOR;                          // move.w #1,d0
            r.above = true;
            d6 = M68k.w(-d6);
        }
        // below: btst #0,d0
        if ((d0 & FLOOR) == 0) {
            r.rejectedBy = "roof below the eye";
            return r;
        }

        r.distAddr = d6;                         // move.w d6,distaddr
        r.ypos = M68k.muls(d6, 64);              // muls #64,d6 / move.l d6,ypos
        r.minZ = M68k.w(M68k.divsInto(r.ypos, d7));  // divs d7,d6 / move.w d6,minz
        r.bottomLine = d7;
        r.visible = true;
        return r;
    }

    /**
     * {@code cornerprocessloop}: classifies every corner of the polygon.
     *
     * A corner behind the viewer counts as visible and forces clipping; one in
     * front is placed against the clip columns. The surface is drawn when any
     * corner is on screen, or when corners fall off both sides at once, which is
     * the polygon spanning the whole view.
     */
    public Corners classifyCorners(BinReader g, int at, Setup setup) {
        Corners c = new Corners();
        for (int i = 0; i <= setup.sides; i++) {
            int point = g.s16(at + 4 + i * 2);   // move.w (a0)+,d0
            if (point < 0 || point >= s.onScreen.length) {
                continue;
            }
            int depth = s.depth(point);          // move.w 6(a1,d0.w*8),d1
            if (depth <= 0) {                    // ble .canttell
                c.anyVisible = true;
                c.anyClipping = true;
                continue;
            }
            int column = s.onScreen[point];      // move.w (a2,d0.w*2),d3
            if (column <= s.leftClip) {          // cmp.w leftclip,d3 / bgt .nol
                c.offLeft = true;
                c.anyClipping = true;
            } else if (column >= s.rightClip) {  // cmp.w rightclip,d3 / blt .nor
                c.offRight = true;
                c.anyClipping = true;
            } else {
                c.anyVisible = true;             // .nor: st d5
            }
        }
        return c;
    }

    /**
     * {@code tst.b d5 / bne somefloortodraw / eor.b d4,d6 / bne dontdrawreturn}.
     *
     * The second test draws when the two off-screen flags <em>agree</em>, not
     * only when both are set: corners falling off both sides at once means the
     * polygon spans the view. Requiring both to be true instead would also
     * reject the case where neither is, which the exclusive-or lets through.
     */
    public static boolean shouldDraw(Corners c) {
        return c.anyVisible || c.offLeft == c.offRight;
    }

    /** Span tables: the left and right screen column of each row. */
    public final int[] leftSide = new int[EngineState.VIEW_ROWS + 2];
    public final int[] rightSide = new int[EngineState.VIEW_ROWS + 2];
    /**
     * {@code leftbrighttab} and {@code rightbrighttab}: the interpolated corner
     * brightness at each end of a row, filled alongside the side tables when the
     * Gouraud floor is on.
     */
    public final int[] leftBrightTab = new int[EngineState.VIEW_ROWS + 2];
    public final int[] rightBrightTab = new int[EngineState.VIEW_ROWS + 2];
    /** {@code gourfloor}: whether to carry brightness across the surface. */
    public boolean gouraud;
    /** {@code top} and {@code bottom}: the rows the polygon actually covers. */
    public int top, bottom;
    /** {@code drawit}: set once an edge contributed a span. */
    public boolean drawIt;
    /** Edges dropped because their clipping path is not transcribed yet. */
    public int edgesSkipped;

    /**
     * Where each edge ended up, so a surface that draws nothing can be told
     * apart from one the routine genuinely skips.
     */
    public int edgeBothBehind, edgeBadPoint, edgeOverflow, edgeClipFailed,
            edgeFlat, edgeDrawn;
    /** Smallest and largest corner depth seen against minZ, for scale checking. */
    public int depthMin, depthMax;

    /**
     * {@code sideloop}: walks each edge of the polygon into the span tables.
     *
     * An edge running down the screen fills the right table, one running up
     * fills the left after swapping its ends. Each is stepped with the routine's
     * own DDA: x advances by the whole part of {@code dx/dy} every row, and an
     * error accumulator carrying the remainder adds one more step when it goes
     * negative.
     *
     * The two walkers are not mirror images. The right one writes the column
     * before taking its step, the left one after, so a left edge sits one row
     * further on than a right edge would.
     */
    public void buildSpans(BinReader g, int at, Setup setup) {
        top = EngineState.VIEW_ROWS;             // move.w #80,top
        bottom = -1;                             // move.w #-1,bottom
        drawIt = false;                          // move.w #0,drawit
        edgesSkipped = 0;
        edgeBothBehind = 0; edgeBadPoint = 0; edgeOverflow = 0;
        edgeClipFailed = 0; edgeFlat = 0; edgeDrawn = 0;
        depthMin = Integer.MAX_VALUE; depthMax = Integer.MIN_VALUE;
        // The tables are only meaningful where this surface wrote them; the
        // original relies on top and bottom bounding that, but leaving stale
        // values behind makes a partial walk look like a malformed span.
        java.util.Arrays.fill(leftSide, Integer.MIN_VALUE);
        java.util.Arrays.fill(rightSide, Integer.MIN_VALUE);
        java.util.Arrays.fill(leftBrightTab, 0);
        java.util.Arrays.fill(rightBrightTab, 0);

        // The loop reads one corner and peeks the next without consuming it, so
        // the word the routine steps over afterwards is really the closing
        // corner. Reading them linearly keeps that, where wrapping modulo the
        // count would quietly assume the polygon closes on its first corner.
        int sides = setup.sides;
        for (int i = 0; i <= sides; i++) {
            int d1 = g.s16(at + 4 + i * 2);
            int d3 = g.s16(at + 4 + (i + 1) * 2);
            if (d1 < 0 || d1 >= s.onScreen.length || d3 < 0 || d3 >= s.onScreen.length) {
                edgeBadPoint++;
                continue;
            }
            int d6 = setup.minZ;
            // move.w (a4,d1.w*4),fbr / move.w (a4,d3.w*4),sbr
            int fbr = cornerBright(d1);
            int sbr = cornerBright(d3);
            int d4 = s.depth(d1);                // first z
            int d5 = s.depth(d3);                // sec z
            depthMin = Math.min(depthMin, Math.min(d4, d5));
            depthMax = Math.max(depthMax, Math.max(d4, d5));

            int col0, row0, col2, row2;

            if (d4 > d6) {
                if (d5 > d6) {
                    // bothinfront: both ends are near enough to project directly
                    col0 = M68k.w(s.onScreen[d1]);
                    col2 = M68k.w(s.onScreen[d3]);
                    row0 = M68k.w(M68k.divsInto(setup.ypos, d4));
                    row2 = M68k.w(M68k.divsInto(setup.ypos, d5));
                } else {
                    // the second end is behind: clip it to the nearest depth.
                    // Its brightness is interpolated the same way its x is.
                    sbr = M68k.w(interpolate(sbr, fbr, M68k.w(d6 - d4),
                                             M68k.w(d5 - d4)));
                    Integer clipped = clipToMinZ(d3, d1, d5, d4, d6, setup);
                    if (clipped == null) {
                        edgeClipFailed++;
                        continue;
                    }
                    col0 = M68k.w(s.onScreen[d1]);
                    row0 = M68k.w(M68k.divsInto(setup.ypos, d4));
                    col2 = clipped;
                    row2 = setup.bottomLine;
                }
            } else {
                if (d5 <= d6) {
                    edgeBothBehind++;
                    continue;                    // bothbehind
                }
                // the first end is behind
                fbr = M68k.w(interpolate(fbr, sbr, M68k.w(d6 - d5),
                                         M68k.w(d4 - d5)));
                Integer clipped = clipToMinZ(d1, d3, d4, d5, d6, setup);
                if (clipped == null) {
                    edgeClipFailed++;
                    continue;
                }
                col0 = clipped;
                row0 = setup.bottomLine;
                col2 = M68k.w(s.onScreen[d3]);
                row2 = M68k.w(M68k.divsInto(setup.ypos, d5));
            }

            if (row0 == row2) {
                edgeFlat++;
            } else {
                edgeDrawn++;
            }
            walkEdge(col0, row0, col2, row2, fbr, sbr);
        }
    }

    /**
     * Where an edge crosses the nearest visible depth, as a screen column.
     *
     * The routine interpolates the rotated X between the two ends in proportion
     * to how far {@code minz} sits between their depths, shifting down by seven
     * before the multiply and back up after so the product stays in range, then
     * projects the result at {@code minz}.
     *
     * @param behind the end that is too near to draw
     * @param front  the end that is far enough
     */
    private Integer clipToMinZ(int behind, int front, int zBehind, int zFront,
                               int minZ, Setup setup) {
        int dz = M68k.w(zBehind - zFront);       // sub.w
        if (dz == 0) {
            return null;
        }
        int dx = M68k.asrL(s.rotatedX[behind] - s.rotatedX[front], 7);
        int t = M68k.w(minZ - zFront);           // sub.w
        int x = M68k.muls(dx, t);
        x = M68k.aslL(M68k.extL(M68k.w(M68k.divsInto(x, dz))), 7);
        x += s.rotatedX[front];                  // add.l
        return M68k.w(M68k.w(M68k.divsInto(x, minZ)) + EngineState.CENTRE_X);
    }

    /**
     * {@code move.w X,d0 / sub.w Y,d0 / muls d6,d0 / divs dz,d0 / add.w Y,d0}:
     * the corner brightness where an edge crosses the nearest visible depth.
     */
    private static int interpolate(int from, int to, int num, int den) {
        return M68k.w(M68k.w(M68k.divsInto(
                M68k.muls(M68k.w(from - to), num), den)) + to);
    }

    /** {@code PointBrightsPtr}: the corner brightness of the current storey. */
    private int cornerBright(int point) {
        if (point < 0) {
            return 0;
        }
        int[] tab = s.currentPointBright != null
                ? s.currentPointBright
                : (s.doUpper ? s.level.pointBrightUpper : s.level.pointBright);
        return point < tab.length ? tab[point] : 0;
    }

    /** {@code lineclipped}: picks a side, orders the ends, then steps the edge. */
    private void walkEdge(int d0, int d1, int d2, int d3, int fbr, int sbr) {
        if (d1 == d3) {
            return;                              // lineflat
        }
        drawIt = true;                           // st drawit
        int[] table;
        int[] brightTab;
        boolean isLeft;
        if (d3 > d1) {
            table = rightSide;                   // lineonright
            brightTab = rightBrightTab;
            isLeft = false;
        } else {
            table = leftSide;
            brightTab = leftBrightTab;
            isLeft = true;
            int t = d1; d1 = d3; d3 = t;         // exg d1,d3
            t = d0; d0 = d2; d2 = t;             // exg d0,d2
        }
        // Both branches start at the brightness of whichever end came out on
        // top: lineonright begins at fbr, and the left branch begins at sbr
        // because the exchange has already put the second corner first.
        int startBr = isLeft ? sbr : fbr;
        int endBr = isLeft ? fbr : sbr;

        if (d1 < top) {                          // cmp.w top,d1 / bge .nonewtop
            top = d1;
        }
        if (d3 > bottom) {                       // cmp.w bottom,d3 / ble .nonewbot
            bottom = d3;
        }

        int dy = M68k.w(d3 - d1);                // sub.w d1,d3
        int dx = M68k.w(d2 - d0);                // sub.w d0,d2
        if (dy == 0) {
            return;
        }
        boolean goingLeft = dx < 0;
        if (isLeft) {
            d0 = M68k.w(d0 - 1);                 // sub.w #1,d0, only on this side
        }
        if (goingLeft) {
            dx = M68k.w(-dx);                    // neg.w d2
        }

        // Which of the four loops runs decides whether the column is stored
        // before or after it steps. It is not the direction alone: the left
        // table stores first when going right, the right table when going left.
        boolean writeBefore = isLeft != goingLeft;

        int div = M68k.divs(M68k.extL(dx), dy);
        int whole = M68k.w(div);                 // move.w d2,d6 : the quotient
        int rem = M68k.w(div >> 16);             // swap d2 : the remainder
        int acc = dy;                            // move.w d3,d4
        int rows = M68k.w(dy - 1);               // move.w d3,d5 / subq #1,d5
        int bigStep = M68k.w(whole + 1);         // move.w d6,d1 / addq #1,d1

        // ext.l d2 / asl.w #8 / asl.w #3 / divs d3 / ext.l / asl.l #5: the
        // brightness step as 16.16. The two word shifts run on the low word
        // only, so a large corner difference wraps there -- which is what the
        // original does and what the tables were authored against.
        int step = 0;
        if (gouraud) {
            int b = M68k.extL(M68k.w(endBr - startBr));
            b = M68k.aslW(b, 8);
            b = M68k.aslW(b, 3);
            step = M68k.aslL(M68k.extL(M68k.w(M68k.divsInto(b, dy))), 5);
        }
        // moveq #0,d1 / move.w sbr,d1 / swap d1: zero-extended, then the
        // integer part moves to the high word
        int bacc = M68k.uw(startBr) << 16;

        int row = d1;
        for (int n = rows; n >= 0; n--) {
            if (row < 0 || row >= table.length) {
                break;
            }
            if (writeBefore) {
                table[row] = d0;
            }
            if (gouraud) {
                brightTab[row] = M68k.w(bacc >> 16);   // swap d1 / move.w d1,(a4)+
                bacc += step;                          // add.l d2,d1
            }
            acc = M68k.w(acc - rem);
            if (acc < 0) {
                d0 = M68k.w(goingLeft ? d0 - bigStep : d0 + bigStep);
                acc = M68k.w(acc + dy);
            } else {
                d0 = M68k.w(goingLeft ? d0 - whole : d0 + whole);
            }
            if (!writeBefore) {
                table[row] = d0;
            }
            row++;
        }
    }

    /** One row of a surface, ready for the span filler. */
    public static final class Row {
        /** Row in the span tables, before it is turned into a screen row. */
        public int row;
        /** Screen columns the span covers, already clipped. */
        public int left, right;
        /** Depth at this row: {@code ypos / row}. */
        public int depth;
    }

    /** Rows the last fill produced. */
    public final java.util.List<Row> rows = new java.util.ArrayList<>();
    /**
     * {@code disttobot}: how many rows remain below the current one.
     *
     * {@code pastscale} sets it to {@code 39 - top} and each row drawn takes one
     * off, and the water ripple clamps its displacement against it so the sample
     * never reaches past the bottom of the surface. master.s does not compute it
     * at all.
     */
    public int distToBot;

    /** Rows dropped by the per-row column clip. */
    public int rowsClipped;

    /**
     * {@code pastscale} through {@code dofloor}: clips the row range, then walks
     * it building one span per row.
     *
     * A ceiling and a floor clip against different bounds -- the ceiling works
     * outward from the horizon towards {@code topclip} and walks up the screen,
     * the floor down towards {@code botclip} -- which is why the two branches are
     * kept apart rather than folded into one with a sign.
     *
     * The depth of a row is {@code ypos / row}, the inverse of the projection the
     * edge walk used, and the row is held at a minimum of one so the divide at
     * the horizon cannot blow up.
     */
    public void fillRows(Setup setup, Corners corners) {
        rows.clear();
        rowsClipped = 0;
        if (!drawIt) {
            return;                              // tst.b drawit / beq dontdrawfloor
        }

        int d1 = top;
        int d7 = bottom;

        if (setup.above) {
            int d3 = M68k.w(EngineState.CENTRE_Y - s.topClip);
            int d4 = M68k.w(EngineState.CENTRE_Y - s.botClip);
            if (d1 >= d3 || d7 < d4) {
                return;
            }
            if (d1 < d4) {
                d1 = d4;                         // .nocliptoproof
            }
            if (d7 >= d3) {
                d7 = d3;
            }
        } else {
            int d4 = M68k.w(s.botClip - EngineState.CENTRE_Y);
            if (d1 >= d4) {
                return;
            }
            int d3 = M68k.w(s.topClip - EngineState.CENTRE_Y);
            if (d1 < d3) {
                d1 = d3;                         // .nocliptopfloor
            }
            if (d7 <= d3) {
                return;
            }
            if (d7 >= d4) {
                d7 = d4;                         // .noclipbotfloor
            }
        }

        // doneclip
        int count = M68k.w(d7 - d1);
        if (count <= 0) {
            return;
        }
        int scaled = M68k.muls(setup.distAddr, 64);   // muls #64,d0

        for (int i = 0; i < count; i++) {
            int r = d1 + i;
            if (r < 0 || r >= leftSide.length) {
                continue;
            }
            int l = leftSide[r];
            int rt = rightSide[r];
            if (l == Integer.MIN_VALUE || rt == Integer.MIN_VALUE) {
                rowsClipped++;
                continue;
            }

            // dofloor: the right edge is exclusive, both ends clipped
            int d2 = M68k.w(rt + 1);
            if (d2 <= s.leftClip) {
                rowsClipped++;
                continue;
            }
            if (d2 > s.rightClip) {
                d2 = s.rightClip;                // noclipright
            }
            int dl = l;
            if (dl >= s.rightClip) {
                rowsClipped++;
                continue;
            }
            if (dl < s.leftClip) {
                dl = s.leftClip;                 // noclipleft
            }
            if (d2 <= dl) {
                rowsClipped++;
                continue;
            }

            // the row's depth, with the horizon row held away from zero
            int divisor = r == 0 ? 1 : r;

            Row out = new Row();
            out.row = r;
            out.left = dl;
            out.right = d2;
            out.depth = M68k.w(M68k.divsInto(scaled, divisor));
            rows.add(out);
        }
    }

    /** Bytes per row of {@code floorscalecols}, from the {@code floorbright} table. */
    public static final int FLOOR_PALETTE_ROW_BYTES = 512;
    /** Rows that table can reach: its largest entry is {@code 512 * 14}. */
    public static final int FLOOR_SHADES = 15;

    /**
     * {@code FloorLine}'s shade selection.
     *
     * The brightness is the surface's own {@code lighttype}, a fixed bias of
     * five, and the row's depth divided by 64, clamped to 0..28. That index goes
     * through {@code floorbright}, which steps by {@code 512 * ((i + 1) / 2)} --
     * two brightness levels per palette row, as the wall table does with 64.
     *
     * The ceiling of 28 gives row 14, which is exactly the last row
     * {@code floorpalscaled} holds, so the two agree without clamping twice.
     */
    public static int floorShadeRow(int lightType, int depth) {
        int d1 = M68k.w(lightType + 5);          // move.w lighttype,d1 / add.w #5,d1
        int d2 = M68k.w(depth) >> 8;             // asr.w #8,d2
        d1 = M68k.w(d1 + d2);
        if (d1 < 0) {                            // bge .fixedbright
            d1 = 0;
        }
        if (d1 > 28) {                           // cmp.w #28,d1 / ble .smallbright
            d1 = 28;
        }
        return (d1 + 1) / 2;                     // floorbright: 512 * ((i+1)/2)
    }

    /** Where a span starts in the tile, and how far it steps per column. */
    public static final class Walk {
        /** Texture index at the span's first column: v in the high byte, u in the low. */
        public int start;
        /** World step per screen column, already scaled down by 64. */
        public int stepX, stepZ;
        /** The unshifted starting world position, kept for the smooth path. */
        public int smoothX, smoothZ;
    }

    /**
     * {@code pastfloorbright}: the horizontal walk across one span.
     *
     * A row is at a single depth, so one screen column is a fixed step in world
     * space: depth times cosine along x, minus depth times sine along z, shifted
     * by the surface's own scale. The starting point is pulled back by three
     * quarters of a step in one axis and three halves in the other, which is what
     * centres the sampling on the span rather than its edge.
     *
     * The index is built by masking x to 63 and z to 63 rows of 256 and merging
     * them into one word -- a 64 by 64 tile inside a plane 256 texels wide, which
     * is the shape the floor sheet actually has.
     *
     * @param depth the row's depth, {@code d0} on entry
     * @param scale the surface's {@code scaleval}
     */
    public Walk walkFor(int depth, int scale, int leftEdge) {
        Walk w = new Walk();

        int d1 = M68k.muls(depth, s.cosval);     // change in x across the width
        int d2 = -M68k.muls(depth, s.sinval);    // change in z, negated

        // scaleprog: a positive scale shifts left, a negative one right
        if (scale > 0) {
            d1 = M68k.aslL(d1, scale);
            d2 = M68k.aslL(d2, scale);
        } else if (scale < 0) {
            d1 = M68k.asrL(d1, -scale);
            d2 = M68k.asrL(d2, -scale);
        }

        int d3 = d1;                             // z cos
        int d5 = d3;
        int d6 = M68k.asrL(d3, 1);
        d3 = M68k.asrL(d3 + d6, 1);              // three quarters of z cos

        int d4 = d2;                             // z sin
        d6 = M68k.asrL(d4, 1) + d4;              // three halves of z sin
        d4 = -(d4 + d3);                         // start x
        d6 = M68k.asrL(d6, 1);                   // three quarters of z sin
        d5 = d5 - d6;                            // start z

        w.smoothX = M68k.w(d4);                  // startsmoothx
        w.smoothZ = M68k.w(d5);                  // startsmoothz

        int hiX = M68k.w(M68k.swap(d4));         // swap d4
        int hiZ = M68k.asrL(d5, 8);              // asr.l #8,d5
        hiZ = M68k.w(hiZ + s.szoff);             // add.w szoff,d5
        hiX = M68k.w(hiX + s.sxoff);             // add.w sxoff,d4
        hiX &= 63;                               // and.w #63,d4
        hiZ &= 63 * 256;                         // and.w #63*256,d5
        w.start = (hiZ & 0xff00) | (hiX & 0xff); // move.b d4,d5

        w.stepX = M68k.asrL(d1, 6);              // asr.l #6,d1
        w.stepZ = M68k.asrL(d2, 6);

        // leftedge scales the walk forward to the span's first column
        if (leftEdge != 0) {
            w.start = advance(w.start, w.stepX, w.stepZ, leftEdge);
        }
        return w;
    }

    /** Steps a packed index forward, wrapping u at 64 and v at 64 rows of 256. */
    public static int advance(int index, int stepX, int stepZ, int columns) {
        int u = (index & 0xff) + M68k.asrL(stepX * columns, 16);
        int v = ((index >> 8) & 0xff) + M68k.asrL(stepZ * columns, 16);
        return ((v & 63) << 8) | (u & 63);
    }

    /** Brightness levels {@code brightentab} holds, 256 entries each. */

    /** Texture indices pass one leaves for pass two, one per column slot. */
    private final byte[] indexBuffer =
            new byte[EngineState.VIEW_COLUMNS * EngineState.VIEW_ROWS];
    /** Set where pass one actually wrote, so pass two converts nothing stale. */
    private final boolean[] written =
            new boolean[EngineState.VIEW_COLUMNS * EngineState.VIEW_ROWS];

    /** Columns each pass touched, for checking. */
    public int indicesWritten, coloursWritten;
    /** Colours written that came out black, and the indices behind them. */
    public int coloursBlack, indexZero;

    /** One row's walk: where it starts, how it steps, and the fraction it carries. */
    public static final class RowWalk {
        /** The packed coordinate, v in bits 8-13 and u in bits 0-5. */
        public int acc;
        /** The packed step, with the z fraction in the high half. */
        public int step;
        /** The same step with one more v, for the carry copy of the loop. */
        public int stepPlus;
        /** The horizontal fraction accumulator and its per-column step. */
        public int frac, fracStep;
    }

    /**
     * {@code pastfloorbright} up to the point where the pixel loop starts.
     *
     * Kept apart so a check can drive the real arithmetic rather than a copy of
     * it -- two hand copies of this disagreed with each other once already, and
     * the copy was the one that was wrong.
     */
    public RowWalk rowWalk(int depth, int scaleVal, int leftEdge) {
        int d0 = depth;


            // How far the world moves across the whole screen width at this depth
            int d1 = M68k.muls(d0, M68k.w(s.cosval));      // change in x
            int d2 = -M68k.muls(d0, M68k.w(s.sinval));     // change in z

            // scaleprog: a signed shift, up for a positive scaleval
            if (scaleVal > 0) {
                d1 = M68k.aslL(d1, scaleVal);
                d2 = M68k.aslL(d2, scaleVal);
            } else if (scaleVal < 0) {
                d1 = M68k.asrL(d1, -scaleVal);
                d2 = M68k.asrL(d2, -scaleVal);
            }

            // The left edge of the row in world space: three quarters of one
            // step back along each axis, which is what puts the sample at the
            // middle of the leftmost texel rather than on its corner.
            int d3 = d1;                                   // z cos
            int d6 = M68k.asrL(d3, 1);
            int d5 = d1;
            d3 = M68k.asrL(d3 + d6, 1);                    // three quarters of zcos

            int d4 = d2;                                   // z sin
            d6 = M68k.asrL(d4, 1);
            d6 = d4 + d6;                                  // one and a half zsin
            d4 = -(d4 + d3);                               // start x
            d6 = M68k.asrL(d6, 1);                         // three quarters of zsin
            d5 = d5 - d6;                                  // start z

            d4 = d4 + s.sxoff;                             // add.l sxoff,d4
            d5 = d5 + s.szoff;

            // moveq #0,d6 / move.w leftedge,d6: step to the clipped left edge
            int leftEdge2 = M68k.uw(leftEdge);
            if (leftEdge2 != 0) {
                int keepX = d1, keepZ = d2;
                d4 = d4 + M68k.asrL((int) ((long) leftEdge2 * d1), 6);
                d5 = d5 + M68k.asrL((int) ((long) leftEdge2 * d2), 6);
                d1 = keepX;
                d2 = keepZ;
            }

            int startSmoothX = M68k.w(d4);
            int startSmoothZ = M68k.w(d5);

            // Pack the world position into one word: u in the low six bits,
            // v in bits 8 to 13, so one indexed byte read reaches the tile.
            d4 = M68k.swap(d4);
            d5 = M68k.asrL(d5, 8);
            d4 = M68k.setW(d4, M68k.w(d4) & 63);
            d5 = M68k.setW(d5, M68k.w(d5) & (63 * 256));
            d5 = (d5 & ~0xff) | (d4 & 0xff);               // move.b d4,d5

            // The per-column step, packed the same way
            d1 = M68k.asrL(d1, 6);
            d2 = M68k.asrL(d2, 6);
            int a4 = M68k.w(d1);                           // x step
            int a5 = M68k.w(d2);                           // z step
            d2 = M68k.asrL(d2, 8);
            d2 = M68k.setW(d2, M68k.w(d2) & 0x3f00);
            d1 = M68k.swap(d1);
            d2 = M68k.setW(d2, M68k.w(d2) + M68k.w(d1));

            d5 = M68k.setW(d5, M68k.w(d5) & MASK);
            d5 = M68k.swap(M68k.setW(M68k.swap(d5), startSmoothZ));
            d2 = M68k.swap(M68k.setW(M68k.swap(d2), a5));


        RowWalk w = new RowWalk();
        w.acc = d5;
        w.step = d2;
        w.stepPlus = M68k.setW(d2, M68k.w(d2) + 256);
        w.frac = startSmoothX;
        w.fracStep = a4;
        return w;
    }

    /**
     * Pass one: walks each span writing the floor sheet's index for every column.
     *
     * The engine leaves these in the screen slots themselves -- each slot is four
     * bytes, so the index and the colour word that replaces it share the space --
     * and a second pass converts them. Keeping the indices in their own buffer
     * here does the same work without overlaying two meanings on one array.
     */
    public void writeIndices(FloorTexture sheet, Setup setup, int scaleVal, int tile) {
        writeIndices(sheet, setup, scaleVal, tile, null, 0);
    }

    /**
     * The same walk, but storing the water pattern instead of the floor index.
     *
     * {@code texturedwater} adds {@code wateroff} to the packed coordinate before
     * reading, which is what makes the pattern drift across the surface, and
     * reads a word rather than a byte. Only the high byte is kept: it is a level
     * from zero to fifteen.
     */
    public void writeIndices(FloorTexture sheet, Setup setup, int scaleVal, int tile,
                             ab3d.data.WaterTexture water, int waterOff) {
        indicesWritten = 0;
        indexZero = 0;
        java.util.Arrays.fill(written, false);

        for (Row r : rows) {
            RowWalk w = rowWalk(r.depth, scaleVal, r.left);
            int d5 = w.acc;
            int d2 = w.step;
            int d6plus = w.stepPlus;
            int d3 = w.frac;
            int a4 = w.fracStep;
            boolean carry = false;
            int row = screenRow(r.row, setup);

            for (int x = r.left; x < r.right; x++) {
                d5 = M68k.setW(d5, M68k.w(d5) & MASK);     // and.w d1,d5
                int packed = M68k.uw(d5);
                int idx;
                if (water != null) {
                    // add.w wateroff,d5, then the word read
                    int at = M68k.uw(M68k.w(packed + waterOff)) & MASK;
                    idx = (water.pattern(at, waterOff / 8) >> 8) & 0x0f;
                } else {
                    idx = sheet.index(packed & 63, (packed >> 8) & 63, tile);
                }
                if (idx == 0) {
                    indexZero++;
                }
                int slot = slotOf(x, row);
                if (slot >= 0) {
                    indexBuffer[slot] = (byte) idx;
                    written[slot] = true;
                    indicesWritten++;
                }

                // add.w a4,d3 / addx.l d2,d5
                int sum = M68k.uw(d3) + M68k.uw(a4);
                d3 = M68k.setW(d3, sum);
                int step = carry ? d6plus : d2;
                long total = (d5 & 0xffffffffL) + (step & 0xffffffffL)
                           + (sum >>> 16);
                carry = (total >>> 32) != 0;
                d5 = (int) total;
            }
        }
    }

    /**
     * {@code texturedwater}, as far as this build allows.
     *
     * The original reads a word from {@code waterfile}, keeps its high byte,
     * takes the low byte from the screen a few rows away and looks the pair up
     * in {@code brightentab}. The displacement is a sine of the depth whose
     * phase advances every frame, and that is what makes the surface ripple.
     *
     * The tint is left out, and not because it is hard. Every other palette in
     * this build holds twelve-bit colour -- {@code floorpalscaled} and
     * {@code darkenedcols} both do, with 270 and 2028 distinct values -- while
     * {@code brightentab} holds 224 distinct values, each one a single byte
     * repeated into a word. It is an eight-bit index table, so writing it into
     * the view buffer would put chunky indices where everything else puts RGB.
     * The water routine was not converted when the renderer was moved to RTG.
     * Rather than invent a tint, this displaces what is already beneath the
     * surface and leaves the colour alone.
     *
     * @param wtan  the ripple phase, advancing by 640 a frame
     */
    public void rippleWater(Setup setup, int wtan) {
        // move.w #39,d7 / sub.w top,d7 / move.w d7,disttobot
        distToBot = M68k.w(39 - top);
        for (Row r : rows) {
            int row = screenRow(r.row, setup);

            // move.w dst,d0 / asl.w #7,d0 / add.w wtan,d0 / and.w #8191,d0
            int phase = M68k.w(M68k.aslW(r.depth, 7) + wtan) & 8191;
            int d0 = M68k.extL(sineFor(phase));

            // move.w dst,d3 / add.w #300,d3 / divs d3,d0 / asr.w #6,d0 / addq #2,d0
            int d3 = M68k.w(r.depth + 300);
            d0 = M68k.w(M68k.divsInto(d0, d3));
            d0 = M68k.w(M68k.asrW(d0, 6) + 2);

            // cmp.w disttobot,d0 / blt oknotoffbototot / move.w disttobot,d0
            // / subq #1,d0
            if (d0 >= distToBot) {
                d0 = M68k.w(distToBot - 1);
            }
            distToBot = M68k.w(distToBot - 1);   // sub.w #1,disttobot

            // tst.w above / beq nonnnnneg / neg.l d0
            int step = setup.above ? -d0 : d0;
            int from = row + step;
            if (from < 0 || from >= EngineState.VIEW_ROWS) {
                continue;
            }

            int at = s.rowStart(row);
            int src = s.rowStart(from);
            for (int x = r.left; x < r.right; x++) {
                int here = at + EngineState.columnWord(x);
                int there = src + EngineState.columnWord(x);
                if (here < 0 || here >= s.screen.length
                        || there < 0 || there >= s.screen.length) {
                    continue;
                }
                int slot = slotOf(x, row);
                int level = slot >= 0 && written[slot] ? indexBuffer[slot] & 0x0f : 0;
                s.screen[here] = (short) darken(s.screen[there], level);
                coloursWritten++;
            }
        }
    }

    /**
     * Dims a refracted colour by the water pattern's level.
     *
     * This is the one step that is not the original's. Its {@code brightentab}
     * turns the level and the pixel beneath into a palette index, and this build
     * has no palette to put that index through, so the level is applied to the
     * colour directly instead: each nibble is scaled down by up to a half. The
     * pattern and the level are the engine's; only this arithmetic is a stand-in,
     * and {@link #WATER_DEPTH} is the knob for how heavy it looks.
     */
    private static int darken(int colour, int level) {
        int scale = 16 - ((level * WATER_DEPTH) / 16);
        int r = (((colour >> 8) & 0xf) * scale) >> 4;
        int g = (((colour >> 4) & 0xf) * scale) >> 4;
        int b = ((colour & 0xf) * scale) >> 4;
        return (r << 8) | (g << 4) | b;
    }

    /** How far the water may dim what is under it, out of sixteen. */
    public static int WATER_DEPTH = 6;

    /** The engine's own sine table, set by the caller once. */
    private ab3d.data.SineTable sine;

    public void setSineTable(ab3d.data.SineTable table) {
        this.sine = table;
    }

    /**
     * {@code move.l #SineTable,a0 / move.w (a0,d0.w),d0}: the index is a byte
     * offset, so the masked phase is halved to reach an entry.
     */
    private int sineFor(int phase) {
        return sine == null ? 0 : sine.sin(phase >> 1);
    }

    /** {@code move.w #%11111100111111,d1}: six bits of u and six of v. */
    private static final int MASK = 0x3f3f;


    /**
     * Pass two in Gouraud mode: {@code dofloorGOUR} and {@code acrossscrngour}.
     *
     * The flat path picks one palette row for the row; this one picks a row for
     * each end and slides between them. The slide is done in a single register:
     * its low byte holds the texel, bits 8 upwards the palette row, and the rest
     * a fraction, so {@code move.b} can drop a new texel in without disturbing
     * the row and one {@code add.l} advances both the fraction and, on carry,
     * the row. That is why the row is stored shifted left by eight.
     *
     * {@code lighttype} is not added here -- the line is commented out in the
     * original -- so zone brightness reaches a Gouraud floor only through the
     * corner values, and the distance term is {@code asr.w #7} rather than the
     * flat path's {@code #8}.
     */
    public void convertIndicesGouraud(FloorTexture sheet, Setup setup) {
        coloursWritten = 0;
        coloursBlack = 0;

        for (Row r : rows) {
            int row = screenRow(r.row, setup);
            int tabRow = r.row;
            if (tabRow < 0 || tabRow >= leftBrightTab.length) {
                continue;
            }
            // move.w d2,d5 / sub.w (a4),d5 / addq #1,d5: the unclipped width
            int span = M68k.w(M68k.w(rightSide[tabRow] - leftSide[tabRow]) + 1);
            if (span <= 0) {
                continue;
            }
            // d6: how many columns the left clip cut away
            int cut = 0;
            if (leftSide[tabRow] < s.leftClip) {
                cut = M68k.w(M68k.w(s.leftClip - 1) - leftSide[tabRow]);
            }

            int d2 = M68k.w(M68k.asrW(r.depth, 7));       // asr.w #7,d2

            int left = clampRow(M68k.w(leftBrightTab[tabRow] + d2));
            int right = clampRow(M68k.w(rightBrightTab[tabRow] + d2));

            int delta = M68k.w(right - left);
            int bright = M68k.aslW(left, 8);              // asl.w #8,d1
            int d3 = M68k.swap(M68k.setW(0, delta));      // swap d3
            int stepWord;
            if (d3 > 0) {
                stepWord = M68k.w(M68k.divsInto(M68k.asrL(d3, 5), span));
            } else {
                int t = M68k.asrL(-d3, 5);
                stepWord = M68k.w(-M68k.w(M68k.divsInto(t, span)));
            }
            // muls d3,d6 / add.w #256*8,d6 / asr.w #3,d6 / clr.b d6
            int skew = M68k.w(M68k.muls(stepWord, cut) + 256 * 8);
            skew = M68k.asrW(skew, 3) & 0xff00;
            bright = M68k.w(bright + skew);

            // ext.l d3 / asl.l #5 / swap / asl.w #8
            int step = M68k.aslL(M68k.extL(stepWord), 5);
            step = M68k.swap(step);
            step = M68k.aslW(step, 8);

            int acc = bright;                             // move.l leftbright,d0
            int rowAt = s.rowStart(row);
            for (int x = r.left; x < r.right; x++) {
                int screen = rowAt + EngineState.columnWord(x);
                int slot = slotOf(x, row);
                if (slot < 0 || !written[slot]) {
                    acc = advanceBright(acc, step);
                    continue;
                }
                // move.b (a0,d5.w*4),d0: only the low byte changes
                acc = (acc & ~0xff) | (indexBuffer[slot] & 0xff);
                acc = advanceBright(acc, step);
                if (screen >= 0 && screen < s.screen.length) {
                    int at = M68k.uw(acc);
                    int c = sheet.colour(at >> 8, at & 0xff);
                    s.screen[screen] = (short) c;
                    coloursWritten++;
                    if (c == 0) {
                        coloursBlack++;
                    }
                }
            }
        }
    }

    /** {@code add.l d1,d0 / bcc.s .nomoreb / add.w #256,d0}. */
    private static int advanceBright(int acc, int step) {
        long sum = (acc & 0xffffffffL) + (step & 0xffffffffL);
        int out = (int) sum;
        if ((sum >>> 32) != 0) {
            out = M68k.setW(out, M68k.w(out) + 256);
        }
        return out;
    }

    /** {@code bge / moveq #0 / asr.w #1 / cmp.w #14 / move.w #14}. */
    private static int clampRow(int v) {
        if (v < 0) {
            v = 0;
        }
        v = M68k.asrW(v, 1);
        return Math.min(v, FLOOR_SHADES - 1);
    }

    /**
     * Pass two: the palette lookup of {@code acrossscrn}, {@code move.w
     * (a1,d0.w*2),(a3)}.
     *
     * One palette row for the whole span, chosen by {@link #floorShadeRow}, and
     * one word written every four bytes -- the column slot the 416-byte stride
     * gives. There is no dithering here. The dithered variant does exist in
     * master.s, at {@code pcli}, where three offsets into {@code brightentab} are
     * handed to the unrolled {@code doacrossline} blob, but nothing reaches it:
     * its only caller is {@code WaterFloorLine}, and {@code LineToUse} is never
     * set to that -- {@code itswater} installs {@code FloorLine} like the others
     * and merely sets the {@code usewater} flag, which the inner loop tests.
     */
    public void convertIndices(FloorTexture sheet, Setup setup, int lightType) {
        coloursWritten = 0;
        coloursBlack = 0;
        for (Row r : rows) {
            // FloorLine is entered once per row with that row's depth, so the
            // palette row is chosen per row and not once for the surface. Using
            // the surface's nearest depth throughout saturates the shade at the
            // darkest row and the whole floor comes out black.
            int shadeRow = floorShadeRow(lightType, r.depth);
            int row = screenRow(r.row, setup);
            int rowAt = s.rowStart(row);
            for (int x = r.left; x < r.right; x++) {
                int slot = slotOf(x, row);
                if (slot < 0 || !written[slot]) {
                    continue;
                }
                int screen = rowAt + EngineState.columnWord(x);
                if (screen >= 0 && screen < s.screen.length) {
                    int c = sheet.colour(shadeRow, indexBuffer[slot] & 0xff);
                    s.screen[screen] = (short) c;
                    coloursWritten++;
                    if (c == 0) {
                        coloursBlack++;
                    }
                }
            }
        }
    }

    /** A span row is measured from the horizon; the two surfaces walk opposite ways. */
    private static int screenRow(int row, Setup setup) {
        return setup.above ? EngineState.CENTRE_Y - row : EngineState.CENTRE_Y + 1 + row;
    }

    private static int slotOf(int column, int row) {
        if (column < 0 || column >= EngineState.VIEW_COLUMNS
                || row < 0 || row >= EngineState.VIEW_ROWS) {
            return -1;
        }
        return row * EngineState.VIEW_COLUMNS + column;
    }

}
