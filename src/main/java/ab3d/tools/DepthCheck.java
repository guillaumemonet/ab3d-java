package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.Zone;
import ab3d.engine.EngineState;
import ab3d.engine.Renderer68k;

/**
 * Checks the rotated depth against the same rotation in floating point.
 *
 * The engine's depth is {@code ((x*sin + z*cos) << 2) >> 16}, so the float
 * comparison uses that same scale; a systematic factor or a sign error shows up
 * as every point disagreeing rather than a handful rounding differently.
 */
public final class DepthCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_c");
        SineTable sine = SineTable.load(game);
        EngineState st = new EngineState(lv);
        Renderer68k rot = new Renderer68k(st);

        int zi = lv.startZone;
        Zone zone = lv.zone(zi);
        st.xoff = lv.startX;
        st.zoff = lv.startZ;

        int checked = 0, wrongSign = 0, worst = 0;
        for (int a = 0; a < 4096; a += 256) {
            st.sinval = sine.sin(a);
            st.cosval = sine.cos(a);
            rot.rotateLevelPts(zone.points);
            double s = st.sinval / 32768.0, c = st.cosval / 32768.0;
            for (int pt : zone.points) {
                if (pt < 0 || pt >= lv.numPoints) {
                    continue;
                }
                int x = lv.pointX[pt] - st.xoff;
                int z = lv.pointZ[pt] - st.zoff;
                double want = (x * s + z * c) * 32768.0 * 4 / 65536.0;
                int got = st.depth(pt);
                checked++;
                int diff = (int) Math.abs(want - got);
                worst = Math.max(worst, Math.min(diff, 99999));
                if (Math.signum(want) != Math.signum(got) && Math.abs(want) > 4) {
                    wrongSign++;
                }
            }
        }
        System.out.printf("%s zone %d: %d point rotations, %d with the wrong sign, "
                        + "worst absolute difference %d%n",
                        lv.name, zi, checked, wrongSign, worst);
    }
}
