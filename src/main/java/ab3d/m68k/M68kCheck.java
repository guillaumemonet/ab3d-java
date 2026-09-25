package ab3d.m68k;

/** Checks the 68000 helpers on the cases where naive arithmetic diverges. */
public final class M68kCheck {

    private static int failures;

    public static void main(String[] args) {
        // divs truncates toward zero, unlike Java's floor for negatives in shifts
        eq("divs -7/2", M68k.divsQuotient(-7, 2), -3);
        eq("divs 7/-2", M68k.divsQuotient(7, -2), -3);
        eq("divs remainder", M68k.divs(-7, 2) >>> 16, 0xffff);  // -1

        // a quotient past 16 bits is exactly the case that must not wrap silently
        yes("divs overflow detected", M68k.divsOverflows(0x10000 * 3, 1));
        no("divs in range", M68k.divsOverflows(30000, 1));

        // muls is 16x16 into 32: the operands are words, the result is not
        eq("muls sign", M68k.muls(-32768, 2), -65536);
        eq("muls truncates operands", M68k.muls(0x12345678, 1), 0x5678);

        // swap carries two values in one register, which the rotation relies on
        eq("swap", M68k.swap(0x12345678), 0x56781234);

        // word ops leave the upper half alone
        eq("setW keeps high half", M68k.setW(0xAAAA0000, 0x1234), 0xAAAA1234);
        eq("asr.w keeps high half", M68k.asrW(0xAAAA_8000, 1), 0xAAAAC000);
        eq("asr.l is arithmetic", M68k.asrL(-16, 2), -4);
        eq("lsr.l is logical", M68k.lsrL(-16, 28), 0xF);

        // ext.l is what turns a rotated word back into a usable long
        eq("ext.l negative", M68k.extL(0x0000FFFF), -1);
        eq("ext.w keeps high half", M68k.extW(0x1234_00FF), 0x1234FFFF);

        eq("lsr.b", M68k.lsrB(0xFFFF_FF80, 2), 0xFFFFFF20);

        System.out.println(failures == 0
                ? "M68k: all checks passed"
                : "M68k: " + failures + " FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void eq(String what, int got, int want) {
        boolean ok = got == want;
        if (!ok) {
            failures++;
        }
        System.out.printf("  [%s] %-26s got %08x want %08x%n",
                ok ? " OK " : "FAIL", what, got, want);
    }

    private static void yes(String what, boolean v) {
        eq(what, v ? 1 : 0, 1);
    }

    private static void no(String what, boolean v) {
        eq(what, v ? 1 : 0, 0);
    }
}
