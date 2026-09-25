package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.m68k.M68k;

/**
 * Checks the floor's world walk against the same walk in floating point.
 *
 * The engine's depth for a floor row is {@code ypos / row}, which is twice the
 * true distance, and its sine table scales by 32768, so the product of the two
 * is the world offset in 16.16. That is the relation this compares: if the
 * transcription drifts with the view angle, the error here grows with it rather
 * than staying at rounding.
 */
public final class FloorWalkCheck {

    /** How far along the row to walk before comparing. */
    private static final int COLS = Integer.getInteger("cols", 40);

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_c");
        SineTable sine = SineTable.load(game);

        int xoff = lv.startX, zoff = lv.startZ;
        int ypos = 12288;                      // a floor one eye-height below
        System.out.printf("%s from (%d,%d)%n", lv.name, xoff, zoff);
        System.out.println("  angle   row   engine u,v      float u,v     du   dv");

        double worstU = 0, worstV = 0, walkU = 0, walkV = 0;
        String worstWhere = "";
        double negWorst = 0, posWorst = 0;
        int negN = 0, posN = 0;
        for (int a = 0; a < 4096; a += 256) {
            int sinv = sine.sin(a), cosv = sine.cos(a);
            double s = sinv / 32768.0, c = cosv / 32768.0;
            for (int row = 10; row <= 30; row += 10) {
                int d0 = ypos / row;                       // divs d0 -> depth
                int d1 = M68k.muls(d0, cosv);
                int d2 = -M68k.muls(d0, sinv);

                int d3 = d1;
                int d6 = M68k.asrL(d3, 1);
                int d5 = d1;
                d3 = M68k.asrL(d3 + d6, 1);
                int d4 = d2;
                d6 = M68k.asrL(d4, 1);
                d6 = d4 + d6;
                d4 = -(d4 + d3);
                d6 = M68k.asrL(d6, 1);
                d5 = d5 - d6;
                d4 += xoff << 16;
                d5 += zoff << 16;

                int eu = (M68k.swap(d4) & 0xffff) & 63;
                int ev = (M68k.w(M68k.asrL(d5, 8)) & (63 * 256)) >> 8;

                // The same point in floating point: the engine's depth is twice
                // the true distance, and column 0 sits 48/64 of it to the left.
                double z = d0 / 2.0;
                double wx = xoff + z * s - 0.75 * z * c;
                double wz = zoff + z * c + 0.75 * z * s;
                int fu = ((int) Math.floor(wx)) & 63;
                int fv = ((int) Math.floor(wz)) & 63;

                // Drive the engine's own walk rather than a copy of it
                ab3d.engine.EngineState es = new ab3d.engine.EngineState(lv);
                es.sinval = sinv;
                es.cosval = cosv;
                es.sxoff = xoff << 16;
                es.szoff = zoff << 16;
                ab3d.engine.FloorDraw fd = new ab3d.engine.FloorDraw(es);
                ab3d.engine.FloorDraw.RowWalk w = fd.rowWalk(d0, 0, 0);
                int acc = w.acc;
                int frac = w.frac;
                boolean cy = false;
                int cols = COLS;
                for (int k = 0; k < cols; k++) {
                    acc = M68k.setW(acc, M68k.w(acc) & 0x3f3f);
                    int sum2 = M68k.uw(frac) + M68k.uw(w.fracStep);
                    frac = M68k.setW(frac, sum2);
                    long tot = (acc & 0xffffffffL)
                             + ((cy ? w.stepPlus : w.step) & 0xffffffffL)
                             + (sum2 >>> 16);
                    cy = (tot >>> 32) != 0;
                    acc = (int) tot;
                }
                acc = M68k.setW(acc, M68k.w(acc) & 0x3f3f);
                int wu = M68k.uw(acc) & 63;
                int wv = (M68k.uw(acc) >> 8) & 63;
                double kx = wx + cols * (z / 64.0) * c;
                double kz = wz - cols * (z / 64.0) * s;
                int wfu = ((int) Math.floor(kx)) & 63;
                int wfv = ((int) Math.floor(kz)) & 63;
                int wdu = Math.min(Math.floorMod(wu - wfu, 64), Math.floorMod(wfu - wu, 64));
                int wdv = Math.min(Math.floorMod(wv - wfv, 64), Math.floorMod(wfv - wv, 64));
                walkU = Math.max(walkU, wdu);
                double vstepReal = -(z / 64.0) * s;
                if (vstepReal < 0) {
                    negWorst = Math.max(negWorst, wdv);
                    negN++;
                } else {
                    posWorst = Math.max(posWorst, wdv);
                    posN++;
                }
                if (wdv > walkV) {
                    walkV = wdv;
                    worstWhere = String.format("angle %d row %d, v step %.3f, "
                                             + "engine %d float %d",
                                               a, row, -(z / 64.0) * s, wv, wfv);
                }

                int du = Math.min(Math.floorMod(eu - fu, 64), Math.floorMod(fu - eu, 64));
                int dv = Math.min(Math.floorMod(ev - fv, 64), Math.floorMod(fv - ev, 64));
                worstU = Math.max(worstU, du);
                worstV = Math.max(worstV, dv);
                if (a % 1024 == 0) {
                    System.out.printf("  %5d  %4d   %3d,%3d      %3d,%3d     %3d  %3d"
                                    + "  | after cols engine %2d,%2d float %2d,%2d"
                                    + " (%d,%d)%n",
                                      a, row, eu, ev, fu, fv, du, dv,
                                      wu, wv, wfu, wfv, wdu, wdv);
                }
            }
        }
        System.out.printf("  worst at the row start: u %.0f, v %.0f%n", worstU, worstV);
        System.out.printf("  worst after %d columns: u %.0f, v %.0f  [%s]%n",
                          COLS, walkU, walkV, worstWhere);
        System.out.printf("  by sign of the v step: negative %d cases worst %.0f, "
                        + "positive %d cases worst %.0f%n",
                          negN, negWorst, posN, posWorst);
    }
}
