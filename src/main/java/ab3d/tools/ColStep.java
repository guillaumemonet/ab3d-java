package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.engine.EngineState;
import ab3d.engine.FloorDraw;
import ab3d.m68k.M68k;

/** Prints v column by column, engine against geometry, for one row. */
public final class ColStep {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, "level_c");
        SineTable sine = SineTable.load(game);
        int a = Integer.getInteger("angle", 1792), row = 10, ypos = 12288;
        int xoff = 0, zoff = 0;

        EngineState es = new EngineState(lv);
        es.sinval = sine.sin(a);
        es.cosval = sine.cos(a);
        es.sxoff = xoff << 16;
        es.szoff = zoff << 16;
        FloorDraw fd = new FloorDraw(es);

        int d0 = ypos / row;
        FloorDraw.RowWalk w = fd.rowWalk(d0, 0, 0);
        double z = d0 / 2.0;
        double s = es.sinval / 32768.0, c = es.cosval / 32768.0;
        double wz = zoff + z * c + 0.75 * z * s;
        double vstep = -(z / 64.0) * s;

        System.out.printf("depth %d, v step %.4f, start acc %08x step %08x%n",
                          d0, vstep, w.acc, w.step);
        int acc = w.acc, frac = w.frac;
        boolean cy = false;
        int carries = 0;
        for (int k = 0; k <= 80; k++) {
            acc = M68k.setW(acc, M68k.w(acc) & 0x3f3f);
            int ev = (M68k.uw(acc) >> 8) & 63;
            int fv = ((int) Math.floor(wz + k * vstep)) & 63;
            if (k % 20 == 0) {
                System.out.printf("  col %2d  engine v %2d  float v %2d  %s%n",
                                  k, ev, fv, ev == fv ? "" : "<<<");
            }
            int sum = M68k.uw(frac) + M68k.uw(w.fracStep);
            frac = M68k.setW(frac, sum);
            long tot = (acc & 0xffffffffL)
                     + ((cy ? w.stepPlus : w.step) & 0xffffffffL) + (sum >>> 16);
            cy = (tot >>> 32) != 0;
            if (cy) {
                carries++;
            }
            acc = (int) tot;
        }
        int a5 = M68k.uw(M68k.swap(w.step));
        System.out.printf("  carries %d over 80 columns; a5 = %d means %.1f expected%n",
                          carries, a5, 80.0 * a5 / 65536.0);
        System.out.printf("  integer v field of the step = %d%n",
                          (M68k.uw(w.step) >> 8) & 63);
    }
}
