package ab3d.engine;

import ab3d.m68k.M68k;

/**
 * The renderer, transcribed from source/master.s rather than re-derived.
 *
 * Each routine keeps the original's name, and the body follows it instruction by
 * instruction: register names are preserved in comments so the two can be read
 * side by side, and every arithmetic step goes through {@link M68k} so word
 * wrapping, {@code divs} truncation and {@code swap} behave the way the 68000
 * does rather than the way floating point would.
 */
public final class Renderer68k {

    private final EngineState s;

    public Renderer68k(EngineState state) {
        this.s = state;
    }

    /**
     * {@code RotateLevelPts}: rotates the points a zone needs into camera space,
     * fills {@code Rotated} and projects each into {@code OnScreen}.
     *
     * The routine carries sine and cosine together in d6 and flips between them
     * with {@code swap}, which is why the swaps appear in the middle of the
     * multiplications rather than being hoisted out.
     *
     * @param pointsToRotate the zone's point list, ended by a negative index
     */
    public void rotateLevelPts(int[] pointsToRotate) {
        // move.w sinval,d6 / swap d6 / move.w cosval,d6
        int d6 = ((s.sinval & 0xffff) << 16) | (s.cosval & 0xffff);

        // move.w xoff,d4 / move.w zoff,d5
        int d4 = M68k.w(s.xoff);
        int d5 = M68k.w(s.zoff);

        for (int index : pointsToRotate) {
            // move.w (a0)+,d7 / blt.s outofpointrot
            int d7 = index;
            if (d7 < 0) {
                break;
            }
            if (d7 >= s.rotatedX.length) {
                continue;
            }

            // move.w (a3,d7*4),d0 / sub.w d4,d0
            int d0 = M68k.w(M68k.w(s.level.pointX[d7]) - d4);
            // move.w d0,d2
            int d2 = d0;
            // move.w 2(a3,d7*4),d1 / sub.w d5,d1
            int d1 = M68k.w(M68k.w(s.level.pointZ[d7]) - d5);

            // muls d6,d2            d6 low word is cosval here
            d2 = M68k.muls(d2, d6);
            // swap d6               now d6 low word is sinval
            d6 = M68k.swap(d6);
            // move.w d1,d3 / muls d6,d3
            int d3 = M68k.muls(d1, d6);
            // sub.l d3,d2 / add.l d2,d2
            d2 = d2 - d3;
            d2 = d2 + d2;
            // swap d2 / ext.l d2 / asl.l #7,d2 / add.l xwobble,d2
            d2 = M68k.extL(M68k.swap(d2));
            d2 = M68k.aslL(d2, 7);
            d2 = d2 + s.xwobble;
            // move.l d2,(a1,d7*8)
            s.rotatedX[d7] = d2;

            // muls d6,d0            d6 low word is sinval
            d0 = M68k.muls(d0, d6);
            // swap d6               back to cosval
            d6 = M68k.swap(d6);
            // muls d6,d1
            d1 = M68k.muls(d1, d6);
            // add.l d0,d1 / asl.l #2,d1 / swap d1
            d1 = M68k.aslL(d0 + d1, 2);
            d1 = M68k.swap(d1);
            // move.l d1,4(a1,d7*8)
            s.rotatedZ[d7] = d1;

            // tst.w d1 / bgt.s ptnotbehind
            if (M68k.w(d1) > 0) {
                // divs d1,d2
                // A quotient past 16 bits leaves the destination untouched on a
                // 68000, so the add below then works on the value already there.
                if (!M68k.divsOverflows(d2, d1)) {
                    d2 = M68k.setW(d2, M68k.divsQuotient(d2, d1));
                }
                // add.w #47,d2
                d2 = M68k.setW(d2, M68k.w(d2) + EngineState.CENTRE_X);
            } else if (M68k.w(d2) > 0) {
                // onrightsomewhere: move.w #96,d2
                d2 = M68k.setW(d2, EngineState.VIEW_COLUMNS);
            } else {
                // move.w #0,d2
                d2 = M68k.setW(d2, 0);
            }
            // putin: move.w d2,(a2,d7*2)
            s.onScreen[d7] = (short) d2;
        }
    }

    /**
     * {@code RotateObjectPts}: the same rotation for the object point table.
     *
     * It is a separate routine because the output has a different shape and
     * because of the guard at the top: an object not currently in play has its
     * point zeroed rather than rotated. In a shipped level that is the whole
     * dynamic tail -- the shot pool and the weapon in hand, sixty-three records
     * in both level_b and level_d -- so nearly half the list costs nothing here
     * and is then rejected downstream by the depth test.
     *
     * master.s ends this loop by filling {@code ObsInLine}, the aiming test.
     * The RTG build does not: it calls {@code CalcPLR1InLine} separately right
     * after, so the rotation writes nothing but the three point values.
     */
    public void rotateObjectPts() {
        // move.w sinval,d5 / move.w cosval,d6
        int d5sin = M68k.w(s.sinval);
        int d6cos = M68k.w(s.cosval);

        int n = s.objRotX.length;
        for (int i = 0; i < n; i++) {
            // move.w (a0),d0 / sub.w xoff,d0 / move.w 4(a0),d1
            int d0 = M68k.w(M68k.w(s.level.objectPointX[i]) - M68k.w(s.xoff));
            int d1 = M68k.w(s.level.objectPointZ[i]);

            // tst.w 12(a4) / blt noworkout
            if (notInPlay(i)) {
                s.objRotX[i] = 0;
                s.objRotZ[i] = 0;
                s.objScaledX[i] = 0;
                continue;
            }

            // sub.w zoff,d1
            d1 = M68k.w(d1 - M68k.w(s.zoff));

            // move.w d0,d2 / muls d6,d2 / move.w d1,d3 / muls d5,d3
            int d2 = M68k.muls(d0, d6cos);
            int d3 = M68k.muls(d1, d5sin);
            // sub.l d3,d2 / add.l d2,d2 / swap d2
            d2 = d2 - d3;
            d2 = d2 + d2;
            d2 = M68k.swap(d2);
            // move.w d2,(a1)+
            s.objRotX[i] = (short) M68k.w(d2);

            // muls d5,d0 / muls d6,d1 / add.l d0,d1 / asl.l #2,d1 / swap d1
            d0 = M68k.muls(d0, d5sin);
            d1 = M68k.muls(d1, d6cos);
            d1 = M68k.aslL(d0 + d1, 2);
            d1 = M68k.swap(d1);
            // move.w d1,(a1)+
            s.objRotZ[i] = (short) M68k.w(d1);

            // ext.l d2 / asl.l #7,d2 / add.l xwobble,d2 / move.l d2,(a1)+
            s.objScaledX[i] = M68k.aslL(M68k.extL(d2), 7) + s.xwobble;
        }
    }

    /**
     * {@code tst.w 12(a4)}: the word at offset 12 of the object record sharing
     * this point's index.
     *
     * The rotation walks the object list in step with the point list, one
     * 64-byte record per eight-byte point, so the pairing is positional. Both
     * lists are the same length in the shipped levels and every record names its
     * own index as its point, which is what makes reading the record by point
     * index correct rather than merely convenient.
     */
    private boolean notInPlay(int point) {
        return point < s.level.objects.size() && s.level.objects.get(point).notInPlay;
    }
}
