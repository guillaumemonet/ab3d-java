package ab3d.m68k;

/**
 * 68000 arithmetic, so the engine can be transcribed instruction by instruction
 * rather than re-derived.
 *
 * The renderer's behaviour depends on details that floating point quietly
 * changes: {@code divs} truncates toward zero and yields a 16-bit quotient,
 * {@code muls} is 16x16 into 32, word operations wrap at 16 bits and leave the
 * upper half of the register alone, and {@code swap} exchanges halves so that a
 * long can carry two independent values. Approximating any of these moves the
 * result by a pixel here and a row there, which is what accumulated into
 * mismatched geometry.
 *
 * Every method mirrors one instruction and is named after it. Registers are
 * plain {@code int}s holding the full 32 bits; the word forms take and return
 * the same int so a caller can keep the upper half the way the original does.
 */
public final class M68k {

    private M68k() {
    }

    // ---- size coercion -------------------------------------------------------

    /** Sign-extends the low byte, as {@code move.b} into a data register reads. */
    public static int b(int v) {
        return (byte) v;
    }

    /** Sign-extends the low word: the value {@code move.w} sees. */
    public static int w(int v) {
        return (short) v;
    }

    /** Unsigned low word. */
    public static int uw(int v) {
        return v & 0xffff;
    }

    /** Unsigned low byte. */
    public static int ub(int v) {
        return v & 0xff;
    }

    /** {@code ext.w}: sign-extends byte to word, keeping the upper half. */
    public static int extW(int reg) {
        return (reg & 0xffff0000) | (((byte) reg) & 0xffff);
    }

    /** {@code ext.l}: sign-extends word to long. */
    public static int extL(int reg) {
        return (short) reg;
    }

    /** {@code swap}: exchanges the two halves of a register. */
    public static int swap(int reg) {
        return (reg >>> 16) | (reg << 16);
    }

    /**
     * Writes a word into a register's low half, leaving the high half intact --
     * what {@code move.w src,dN} does.
     */
    public static int setW(int reg, int value) {
        return (reg & 0xffff0000) | (value & 0xffff);
    }

    // ---- arithmetic ----------------------------------------------------------

    /** {@code muls}: signed 16 x 16 into a 32-bit result. */
    public static int muls(int a, int b) {
        return (short) a * (short) b;
    }

    /** {@code mulu}: unsigned 16 x 16 into 32 bits. */
    public static int mulu(int a, int b) {
        return (a & 0xffff) * (b & 0xffff);
    }

    /**
     * {@code divs}: signed 32 / 16, truncating toward zero, quotient in the low
     * word and remainder in the high word.
     *
     * The quotient is only defined when it fits in 16 bits; the 68000 sets the
     * overflow flag and leaves the destination untouched otherwise. Callers that
     * can divide by a small number must check {@link #divsOverflows} first, since
     * silently returning a wrapped value is how a wall ends up spanning the whole
     * screen instead of being clipped.
     */
    public static int divs(int dividend, int divisor) {
        int d = (short) divisor;
        if (d == 0) {
            throw new ArithmeticException("divs by zero");
        }
        int quotient = dividend / d;
        int remainder = dividend % d;
        return ((remainder & 0xffff) << 16) | (quotient & 0xffff);
    }

    /** Quotient alone, as a sign-extended word. */
    public static int divsQuotient(int dividend, int divisor) {
        return (short) divs(dividend, divisor);
    }

    /** True when a {@code divs} would overflow its 16-bit quotient. */
    public static boolean divsOverflows(int dividend, int divisor) {
        int d = (short) divisor;
        if (d == 0) {
            return true;
        }
        int q = dividend / d;
        return q > Short.MAX_VALUE || q < Short.MIN_VALUE;
    }

    /**
     * {@code divs} as it lands on a data register.
     *
     * When the quotient will not fit a word the 68000 sets the overflow flag and
     * <em>leaves the destination alone</em>, so the register still holds the
     * dividend and whatever reads its low word afterwards gets the bottom
     * sixteen bits of that. The routines here never test the flag, so they carry
     * straight on with that value; treating an overflow as a reason to stop
     * makes geometry vanish exactly where the original still draws it.
     */
    public static int divsInto(int dest, int divisor) {
        if (divisor == 0 || divsOverflows(dest, divisor)) {
            return dest;
        }
        return divs(dest, divisor);
    }

    /** {@code divu}: unsigned 32 / 16, quotient low, remainder high. */
    public static int divu(int dividend, int divisor) {
        long n = dividend & 0xffffffffL;
        int d = divisor & 0xffff;
        if (d == 0) {
            throw new ArithmeticException("divu by zero");
        }
        long quotient = n / d;
        long remainder = n % d;
        return (int) ((remainder << 16) | (quotient & 0xffff));
    }

    // ---- shifts --------------------------------------------------------------

    /** {@code asl.l}. */
    public static int aslL(int v, int n) {
        return v << n;
    }

    /** {@code asr.l}: arithmetic, so the sign is preserved. */
    public static int asrL(int v, int n) {
        return v >> n;
    }

    /** {@code lsr.l}: logical. */
    public static int lsrL(int v, int n) {
        return v >>> n;
    }

    /** {@code asl.w}, keeping the register's upper half. */
    public static int aslW(int reg, int n) {
        return setW(reg, reg << n);
    }

    /** {@code asr.w}, keeping the register's upper half. */
    public static int asrW(int reg, int n) {
        return setW(reg, ((short) reg) >> n);
    }

    /** {@code lsr.w}, keeping the register's upper half. */
    public static int lsrW(int reg, int n) {
        return setW(reg, (reg & 0xffff) >>> n);
    }

    /** {@code lsr.b}, keeping everything above the low byte. */
    public static int lsrB(int reg, int n) {
        return (reg & 0xffffff00) | (((reg & 0xff) >>> n) & 0xff);
    }
}
