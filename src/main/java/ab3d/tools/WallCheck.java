package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.Zone;
import ab3d.engine.EngineState;
import ab3d.engine.PolyLoop;
import ab3d.engine.Renderer68k;
import ab3d.engine.WallDraw;

import java.util.TreeMap;

/**
 * Runs the transcribed itsawalldraw over every zone of a level, from a camera
 * standing in each zone in turn, and reports which of its tests fired.
 *
 * A healthy spread means the tests are doing their separate jobs; one reason
 * swallowing nearly everything would mean a comparison was transcribed the wrong
 * way round.
 */
public final class WallCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");
        SineTable sine = SineTable.load(game);

        EngineState st = new EngineState(lv);
        Renderer68k rot = new Renderer68k(st);
        WallDraw wd = new WallDraw(st);
        BinReader g = lv.graphics.data;

        TreeMap<String, Integer> reasons = new TreeMap<>();
        int[] visible = { 0 };
        int[] total = { 0 };

        for (int zi = 0; zi < lv.zones.length; zi++) {
            Zone zone = lv.zone(zi);
            if (zone.exitLines.length == 0) {
                continue;
            }
            long sx = 0, sz = 0;
            for (int e : zone.exitLines) {
                sx += lv.floorLine(e).x1;
                sz += lv.floorLine(e).z1;
            }
            st.xoff = (int) (sx / zone.exitLines.length);
            st.zoff = (int) (sz / zone.exitLines.length);
            st.yoff = zone.floorHeight - 12 * 1024;
            st.sinval = sine.sin(0);
            st.cosval = sine.cos(0);
            st.xwobble = 0;
            rot.rotateLevelPts(zone.points);

            int offset = g.s32(lv.graphics.zoneGraphOffsetsOffset + zi * 8);
            if (offset <= 0 || !g.inRange(offset, 4)) {
                continue;
            }
            final int[] count = { 0 };
            PolyLoop.run(g, offset, g.size(), new PolyLoop.Sink() {
                @Override
                public void wall(BinReader gg, int at, boolean seeThrough) {
                    WallDraw.Wall w = wd.read(gg, at);
                    count[0]++;
                    if (w.visible) {
                        visible[0]++;
                    } else {
                        reasons.merge(w.rejectedBy, 1, Integer::sum);
                    }
                }
            });
            total[0] += count[0];
        }

        System.out.printf("%s: %d wall records tested from %d viewpoints%n",
                lv.name, total[0], lv.zones.length);
        System.out.printf("  visible: %d (%.1f%%)%n", visible[0],
                total[0] == 0 ? 0.0 : 100.0 * visible[0] / total[0]);
        System.out.printf("  paths taken: both in front %d, first behind %d, "
                + "second behind %d, both behind %d (divide unusable %d)%n",
                wd.pathBothInFront, wd.pathFirstBehind, wd.pathSecondBehind,
                wd.pathBothBehind, wd.divideUnusable);
        int fPos = 0, fNeg = 0, sPos = 0, sNeg = 0;
        long absMin = Long.MAX_VALUE, absMax = 0;
        for (long[] c : wd.crossingSamples) {
            long diff = c[1] - c[2];
            absMin = Math.min(absMin, Math.abs(c[1]));
            absMax = Math.max(absMax, Math.abs(c[1]));
            if (c[0] == 1) {
                if (diff >= 0) { fPos++; } else { fNeg++; }
            } else {
                if (diff > 0) { sPos++; } else { sNeg++; }
            }
        }
        System.out.printf("  crossing test: firstBehind reject %d / keep %d, "
                + "secondBehind keep %d / reject %d%n", fPos, fNeg, sPos, sNeg);
        System.out.printf("  |d3| ranges %d..%d, edge is a centred column (-47..49)%n",
                absMin == Long.MAX_VALUE ? 0 : absMin, absMax);
        System.out.println("  rejected by:");
        reasons.forEach((k, v) -> System.out.printf("    %-34s %5d (%.1f%%)%n",
                k, v, 100.0 * v / total[0]));
    }
}
