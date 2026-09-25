package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.engine.EngineState;
import ab3d.engine.FloorDraw;

/**
 * Exercises the transcribed horizontal floor walk.
 *
 * Two things must hold. The starting index has to land inside the 64 by 64 tile,
 * since anything else would sample another tile's texels. And the step has to
 * grow in proportion to depth: a row twice as far away covers twice the world
 * distance per screen column, which is what makes a floor converge.
 */
public final class WalkCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, "level_b");
        SineTable sine = SineTable.load(game);

        EngineState st = new EngineState(lv);
        st.sinval = sine.sin(300);
        st.cosval = sine.cos(300);
        st.sxoff = 0;
        st.szoff = 0;
        FloorDraw fd = new FloorDraw(st);

        System.out.printf("%8s %6s %6s %8s %8s %10s%n",
                "depth", "u", "v", "stepX", "stepZ", "stepX/depth");
        int bad = 0;
        int[] depths = { 64, 128, 256, 512, 1024, 2048 };
        for (int d : depths) {
            FloorDraw.Walk w = fd.walkFor(d, -2, 0);
            int u = w.start & 0xff, v = (w.start >> 8) & 0xff;
            if (u > 63 || v > 63) {
                bad++;
            }
            System.out.printf("%8d %6d %6d %8d %8d %10.2f%n",
                    d, u, v, w.stepX, w.stepZ, (double) w.stepX / d);
        }

        // the step must be linear in depth, so the ratio stays constant
        double first = (double) fd.walkFor(depths[0], -2, 0).stepX / depths[0];
        int nonLinear = 0;
        for (int d : depths) {
            double ratio = (double) fd.walkFor(d, -2, 0).stepX / d;
            if (Math.abs(ratio - first) > 0.02 * Math.abs(first)) {
                nonLinear++;
            }
        }

        // advancing along a span must stay inside the tile
        int outside = 0;
        for (int d : depths) {
            FloorDraw.Walk w = fd.walkFor(d, -2, 0);
            int idx = w.start;
            for (int c = 0; c < 96; c++) {
                idx = FloorDraw.advance(idx, w.stepX, w.stepZ, 1);
                if ((idx & 0xff) > 63 || ((idx >> 8) & 0xff) > 63) {
                    outside++;
                }
            }
        }

        System.out.printf("%nstart outside the tile : %d%n", bad);
        System.out.printf("step not linear in depth: %d of %d%n", nonLinear, depths.length);
        System.out.printf("walk leaving the tile   : %d of %d%n", outside, depths.length * 96);
    }
}
