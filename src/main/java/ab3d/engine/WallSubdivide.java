package ab3d.engine;

import ab3d.m68k.M68k;

/**
 * {@code walldraw}, transcribed from source/wallroutine3.chipmem.
 *
 * Perspective across a wall is approximated by subdividing it and interpolating
 * linearly between the pieces. How many times to halve it is chosen from the
 * wall's own geometry: the bigger the depth difference between its ends, and the
 * nearer the closer end, the more subdivisions it gets.
 *
 * The subdivision itself ping-pongs between two buffers, each pass turning n
 * points into 2n-1 by inserting a midpoint in every interval.
 *
 * One detail worth keeping: an entry is ten bytes -- a longword of rotated X, a
 * word of depth, then the texture column and the brightness as two words. The
 * seeding writes those last two separately, but the subdivision loop reads them
 * back as a <em>single longword</em> and averages both at once. Carries and the
 * shift therefore cross from the brightness into the column. That is what the
 * original does, so it is what this does.
 */
public final class WallSubdivide {

    /** Entries the buffers must hold: the count doubles each pass. */
    private static final int MAX_POINTS = 1 + (1 << (WallDraw.MAX_3D_DIV + 2));

    /** Rotated X of each point, the longword at offset 0. */
    public int[] x = new int[MAX_POINTS];
    /** Depth, the word at offset 4. */
    public int[] depth = new int[MAX_POINTS];
    /** Texture column and brightness packed as one longword, offsets 6 and 8. */
    public int[] uBright = new int[MAX_POINTS];
    /** How many points the last run produced. */
    public int count;

    /** {@code iters} and {@code multcount}, the values the selector picked. */
    public int iters, multCount;

    private int[] x2 = new int[MAX_POINTS];
    private int[] depth2 = new int[MAX_POINTS];
    private int[] uBright2 = new int[MAX_POINTS];

    /**
     * Runs the subdivision for one wall.
     *
     * @param leftX      rotated X of the left end, the longword {@code a0}
     * @param leftDepth  its depth, {@code d1}
     * @param rightX     rotated X of the right end, {@code a2}
     * @param rightDepth its depth, {@code d3}
     * @param leftEnd    texture column at the left end, {@code d4}
     * @param rightEnd   texture column at the right end, {@code d5}
     * @param leftBright per-point brightness at the left end
     * @param rightBright brightness at the right end
     * @return false when the routine returns immediately, both ends being behind
     */
    public boolean run(int leftX, int leftDepth, int rightX, int rightDepth,
                       int leftEnd, int rightEnd, int leftBright, int rightBright) {
        int d1 = M68k.w(leftDepth);
        int d3 = M68k.w(rightDepth);

        // tst.w d1 / bgt oneinfront1 / tst.w d3 / bgt oneinfront / rts
        if (d1 <= 0 && d3 <= 0) {
            count = 0;
            return false;
        }

        // ---- how far to subdivide -------------------------------------------
        int d7 = 16;                        // move.w #16,d7
        int d6 = 2;                         // move.w #2,d6

        int d0 = M68k.w(d3 - d1);           // move.w d3,d0 / sub.w d1,d0
        if (d0 < 0) {
            d0 = M68k.w(-d0);               // bge notnegzdiff / neg.w d0
        }
        if (d0 >= 1024) {                   // cmp.w #1024,d0 / blt nd01
            d7 += d7;
            d6 += 1;
        }
        if (d0 >= 512) {                    // cmp.w #512,d0 / blt nd0
            d7 += d7;
            d6 += 1;
        } else {
            if (d0 <= 256) {                // nd0: cmp.w #256,d0 / bgt nh1
                d7 = M68k.w(d7) >> 1;
                d6 -= 1;
            }
            if (d0 <= 128) {                // nh1: cmp.w #128,d0 / bgt nh2
                d7 = M68k.w(d7) >> 1;
                d6 -= 1;
            }
        }

        // nha: the nearer end decides too
        int near = d3 < d1 ? d3 : d1;       // move.w d3,d0 / cmp.w d1,d3 / blt / move.w d1,d0
        if (near <= 64) {                   // cmp.w #64,d0 / bgt nd1
            d6 += 1;
            d7 += d7;
        }
        if (near >= 128) {                  // cmp.w #128,d0 / blt nh3
            d7 = M68k.w(d7) >> 1;
            d6 -= 1;
            if (d6 >= 0 && near >= 256) {   // blt nh3 / cmp.w #256,d0 / blt nh3
                d7 = M68k.w(d7) >> 1;
                d6 -= 1;
            }
        }

        iters = d6;                         // move.w d6,iters
        multCount = M68k.w(d7 - 1);         // subq #1,d7 / move.w d7,multcount

        // ---- seed the buffer with the two ends and their midpoint ------------
        int midX = M68k.asrL(leftX + rightX, 1);
        int midDepth = M68k.w(M68k.w(d1 + d3) >> 1);
        int midEnd = M68k.w(M68k.w(leftEnd + rightEnd) >> 1);
        int midBright = M68k.w(M68k.w(leftBright + rightBright) >> 1);

        x[0] = leftX;
        depth[0] = d1;
        uBright[0] = pack(leftEnd, leftBright);
        x[1] = midX;
        depth[1] = midDepth;
        uBright[1] = pack(midEnd, midBright);
        x[2] = rightX;
        depth[2] = d3;
        uBright[2] = pack(rightEnd, rightBright);
        count = 3;

        // ---- iterloop: halve every interval, iters times ---------------------
        if (iters < 0) {                    // move.w iters,d6 / blt noiters
            return true;
        }
        int a2 = 1;                         // move.l #1,a2
        for (int pass = iters; pass >= 0; pass--) {
            subdivide(a2);
            a2 += a2;                       // add.w a2,a2
        }
        return true;
    }

    /**
     * One pass of {@code iterloop}: reads the current points and writes each one
     * followed by the midpoint between it and its successor.
     *
     * @param intervals {@code a2}, how many pairs the inner loop walks
     */
    private void subdivide(int intervals) {
        int src = 0, dst = 0;

        // move.l (a3)+,d0 / move.w (a3)+,d1 / move.l (a3)+,d2
        int d0 = x[src];
        int d1 = depth[src];
        int d2 = uBright[src];
        src++;

        for (int d7 = intervals; d7 > 0; d7--) {   // middleloop
            // first half: write the current point, then its midpoint
            if (dst + 4 >= MAX_POINTS || src + 1 >= count) {
                break;
            }
            int d3 = x[src];
            int d4 = depth[src];
            int d5 = uBright[src];
            src++;

            x2[dst] = d0;
            depth2[dst] = d1;
            uBright2[dst] = d2;
            dst++;
            x2[dst] = M68k.asrL(d0 + d3, 1);
            depth2[dst] = M68k.w(M68k.w(d1 + d4) >> 1);
            // the packed pair is averaged as one longword, carries and all
            uBright2[dst] = M68k.asrL(d2 + d5, 1);
            dst++;

            // second half: the same again for the next interval
            if (src >= count) {
                d0 = d3;
                d1 = d4;
                d2 = d5;
                break;
            }
            int e0 = x[src];
            int e1 = depth[src];
            int e2 = uBright[src];
            src++;

            x2[dst] = d3;
            depth2[dst] = d4;
            uBright2[dst] = d5;
            dst++;
            x2[dst] = M68k.asrL(d3 + e0, 1);
            depth2[dst] = M68k.w(M68k.w(d4 + e1) >> 1);
            uBright2[dst] = M68k.asrL(d5 + e2, 1);
            dst++;

            d0 = e0;
            d1 = e1;
            d2 = e2;
        }

        // the final point, written once the loop is done
        x2[dst] = d0;
        depth2[dst] = d1;
        uBright2[dst] = d2;
        dst++;

        // exg a0,a1: the destination becomes the source for the next pass
        int[] tx = x, td = depth, tu = uBright;
        x = x2;
        depth = depth2;
        uBright = uBright2;
        x2 = tx;
        depth2 = td;
        uBright2 = tu;
        count = dst;
    }

    /** Texture column in the high word, brightness in the low, as the buffer holds them. */
    private static int pack(int column, int bright) {
        return (column << 16) | (bright & 0xffff);
    }

    /** Texture column of a point: the high word of the packed pair. */
    public int column(int i) {
        return uBright[i] >> 16;
    }

    /** Brightness of a point: the low word. */
    public int bright(int i) {
        return M68k.w(uBright[i]);
    }
}
