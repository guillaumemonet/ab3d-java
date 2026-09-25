package ab3d.engine;

/**
 * {@code brightanim} and the lookup that feeds from it, transcribed from
 * source/anims and source/jg.s.
 *
 * A brightness word is not always a number. When its high byte is non-zero that
 * byte names one of three animations, and the value to use comes from
 * {@code BrightAnimTable} instead -- which {@code brightanim} advances by one
 * entry a frame, each list looping on a 999. A negative word is used as it
 * stands, animation byte or not.
 *
 * This matters beyond the flicker: in level_a forty-two zones of a hundred and
 * thirty-eight carry an animation byte, so reading the word as a plain number
 * gives them a brightness in the hundreds and every floor in them turns black.
 */
public final class BrightAnim {

    /** {@code dc.w 999}: the value that sends a list back to its start. */
    private static final int LOOP = 999;

    /** {@code PulseANIM}: a slow swing either side of the zone's own light. */
    private static final int[] PULSE = {
        -10, -10, -9, -9, -8, -7, -6, -5, -4, -3, -2, -1,
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
        10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
        -1, -2, -3, -4, -5, -6, -7, -8, -9,
    };

    /** {@code FlickerANIM}: long steady stretches broken by a single dip. */
    private static final int[] FLICKER = flicker();

    private static int[] flicker() {
        int[] a = new int[20 + 1 + 30 + 1 + 5 + 1];
        int at = 0;
        for (int i = 0; i < 20; i++) {
            a[at++] = 10;                      // dcb.w 20,10
        }
        a[at++] = -10;
        for (int i = 0; i < 30; i++) {
            a[at++] = 10;                      // dcb.w 30,10
        }
        a[at++] = -10;
        for (int i = 0; i < 5; i++) {
            a[at++] = 10;                      // dcb.w 5,10
        }
        a[at++] = -10;
        return a;
    }

    /** {@code FireFlickerANIM}: never bright, always unsettled. */
    private static final int[] FIRE = {
        -10, -9, -6, -10, -6, -5, -5, -7, -5, -10, -9, -8, -7, -5, -5, -5, -5,
        -5, -5, -5, -5, -6, -7, -8, -9, -5, -10, -9, -10, -6, -5, -5, -5, -5,
        -5, -5,
    };

    /** {@code BrightAnimPtrs}, in the order the high byte indexes them. */
    private static final int[][] LISTS = {PULSE, FLICKER, FIRE};

    /** {@code BrightAnimTable}: the value each animation is showing now. */
    private final int[] table = new int[LISTS.length];
    private final int[] at = new int[LISTS.length];

    public BrightAnim() {
        tick();
    }

    /** {@code dobrightanims}: one entry on, each list looping on its 999. */
    public void tick() {
        for (int i = 0; i < LISTS.length; i++) {
            if (at[i] >= LISTS[i].length) {
                at[i] = 0;                     // cmp.w #999,d0 / move.l (a4),a2
            }
            table[i] = LISTS[i][at[i]];
            at[i]++;
        }
    }

    /**
     * {@code doallz}: what a brightness word is actually worth this frame.
     *
     * <pre>
     *   tst.w d2 / blt justbright        a negative word is taken as it is
     *   lsr.w #8,d3 / tst.b d3 / beq     so is one with no animation byte
     *   move.w -2(a4,d3.w*2),d2          otherwise the table decides
     * </pre>
     */
    public int resolve(int word) {
        if (word < 0) {
            return word;
        }
        int kind = (word >> 8) & 0xff;
        if (kind == 0) {
            return word;
        }
        int i = kind - 1;                      // the -2 in the indexed read
        return i >= 0 && i < table.length ? table[i] : word;
    }

    /**
     * {@code doneallz}: a point's brightness, with the animation blended in.
     *
     * Points differ from zones. The animation byte splits in two: the low nibble
     * picks the list, the high one plus one is a weight out of sixteen, and the
     * value moves that far from the point's own brightness toward the
     * animation's. A zone takes the animation whole; a point only leans toward
     * it, which is what lets one lamp flicker harder than its neighbour.
     */
    public int resolvePoint(int word) {
        if ((byte) word < 0) {
            return (byte) word;                // tst.b d2 / blt .justbright
        }
        int d3 = (word >> 8) & 0xff;
        if (d3 == 0) {
            return (byte) word;                // tst.b d3 / beq .justbright
        }
        int weight = ((d3 >> 4) & 0xf) + 1;    // lsr.w #4,d4 / add.w #1,d4
        int which = d3 & 0xf;                  // and.w #$f,d3
        int own = (byte) word;                 // ext.w d2
        int anim = value(which);
        int step = ((anim - own) * weight) >> 4;
        return (byte) (own + step);
    }

    /** What an animation is showing, for checking. */
    public int value(int kind) {
        int i = kind - 1;
        return i >= 0 && i < table.length ? table[i] : 0;
    }

    /** {@code LOOP} is only referenced by the data, and kept for the reader. */
    public static int loopMarker() {
        return LOOP;
    }
}
