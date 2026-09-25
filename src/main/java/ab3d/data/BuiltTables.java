package ab3d.data;

/**
 * Two of the original's include files, worked out rather than read.
 *
 * Most of what {@code INCBIN} pulls into the Amiga executable is data -- the
 * backdrop, the palettes, the shading tables, the sine table -- and data has to
 * come from somewhere. These two are not. Each is a short table with a rule
 * behind it, and the rule reproduces the file byte for byte, which
 * {@link ab3d.tools.BuiltTableCheck} is there to keep true. Since it does, there
 * is no reason for this port to carry the bytes.
 *
 * Nothing here is an approximation. A table that only nearly matched would be a
 * change to the game, and would belong in a file instead.
 */
public final class BuiltTables {

    private BuiltTables() {
    }

    /** {@code xtocopx}: ninety-six entries. */
    public static final int COLUMNS = 96;
    /** {@code iterfile}: five hundred and twelve pairs. */
    public static final int ITERATIONS = 512;

    /**
     * {@code xtocopx}: the byte a column's colour register sits at.
     *
     * The copper list is the view buffer, and it is not flat: every thirty-two
     * registers there is a {@code $106} pair between the banks. So a column's
     * offset is its own number plus one for each bank it has passed, times the
     * four bytes a copper move takes. That is why the table steps by four but
     * jumps by eight at thirty-two and again at sixty-four.
     */
    public static int columnByte(int column) {
        return (column + column / 32) * 4;
    }

    /** The whole of {@code xtocopx}, in the order the file has it. */
    public static int[] xToCopX() {
        int[] out = new int[COLUMNS];
        for (int c = 0; c < COLUMNS; c++) {
            out[c] = columnByte(c);
        }
        return out;
    }

    /**
     * {@code iterfile}: how far to shift to divide by {@code n}, rounded up.
     *
     * The floor drawing steps along a span by a divisor it wants as a shift, and
     * a shift can only divide by a power of two, so the table gives the smallest
     * power of two that is at least {@code n} -- the ceiling of its logarithm.
     * One and nought both shift by nothing, because there is nothing to divide
     * by.
     */
    public static int iterShift(int n) {
        if (n <= 1) {
            return 0;
        }
        int shift = 0;
        while ((1 << shift) < n) {
            shift++;
        }
        return shift;
    }

    /** The mask that goes with the shift: every bit the shift throws away. */
    public static int iterMask(int n) {
        return (1 << iterShift(n)) - 1;
    }

    /** The whole of {@code iterfile}, mask and shift a pair as the file has it. */
    public static int[] iterFile() {
        int[] out = new int[ITERATIONS * 2];
        for (int n = 0; n < ITERATIONS; n++) {
            out[n * 2] = iterMask(n);
            out[n * 2 + 1] = iterShift(n);
        }
        return out;
    }
}
