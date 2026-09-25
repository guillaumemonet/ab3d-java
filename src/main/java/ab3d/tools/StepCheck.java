package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.SineTable;
import ab3d.m68k.M68k;

/**
 * Compares the floor's per-column step with the step the geometry asks for.
 *
 * The engine carries the step in two pieces: a whole number of texels in bits 8
 * to 13 of the packed word, and a fraction that accumulates in the high half and
 * adds one more texel whenever it carries. The effective step is therefore the
 * integer plus the carry rate, and that is what this compares -- a drift along
 * the row means these two disagree, while a mere phase difference does not.
 */
public final class StepCheck {

    public static void main(String[] args) throws Exception {
        SineTable sine = SineTable.load(GameData.fromSystemProperty());
        int ypos = 12288;
        System.out.println("  angle  row    v step (engine)   v step (geometry)   error");
        double worst = 0;
        for (int a = 0; a < 4096; a += 128) {
            int sinv = sine.sin(a), cosv = sine.cos(a);
            for (int row = 8; row <= 32; row += 8) {
                int d0 = ypos / row;
                int d2 = -M68k.muls(d0, sinv);

                int scaled = M68k.asrL(d2, 6);
                int a5 = M68k.w(scaled);                 // the fraction
                int whole = (M68k.w(M68k.asrL(scaled, 8)) & 0x3f00) >> 8;
                if (whole > 31) {
                    whole -= 64;                         // the field is six bits
                }
                // carries per column, as a signed rate
                // The accumulator is added with addx.l, so the fraction is
                // unsigned: 0xffe8 is 0.9996 of a texel, not minus a thousandth.
                double carry = (a5 & 0xffff) / 65536.0;
                double engine = whole + carry;

                double z = d0 / 2.0;
                double geometry = -(z / 64.0) * (sinv / 32768.0);

                double err = Math.abs(engine - geometry);
                worst = Math.max(worst, err);
                if (a == 3072 && row == 8) {
                    System.out.printf("    d0=%d sinv=%d d2=%d scaled=%d "
                                    + "asr8=%d masked=%04x whole=%d a5=%d%n",
                                      d0, sinv, d2, scaled,
                                      M68k.asrL(scaled, 8),
                                      M68k.w(M68k.asrL(scaled, 8)) & 0x3f00,
                                      whole, a5);
                }
                if (a % 1024 == 0) {
                    System.out.printf("  %5d %4d   %14.4f   %17.4f   %6.4f%n",
                                      a, row, engine, geometry, err);
                }
            }
        }
        System.out.printf("  worst disagreement over the sweep: %.4f texels a column%n",
                          worst);
    }
}
