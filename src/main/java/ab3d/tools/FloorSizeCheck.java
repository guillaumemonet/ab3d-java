package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.EngineState;
import ab3d.engine.FloorDraw;
import ab3d.engine.PolyLoop;

/**
 * Cross-checks the floor record size two ways.
 *
 * The dispatch derives it from what the routine consumes; the transcribed setup
 * computes it from the skip path. They were arrived at separately, so agreeing
 * on every record of every level is real evidence rather than one assumption
 * confirming itself.
 */
public final class FloorSizeCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String[] levels = args.length > 0 ? args
                : new String[] { "level_a", "level_b", "level_c", "level_d" };

        for (String name : levels) {
            Level lv = Level.load(game, name);
            BinReader g = lv.graphics.data;
            EngineState st = new EngineState(lv);
            FloorDraw fd = new FloorDraw(st);
            int[] seen = { 0, 0 };

            for (int zi = 0; zi < lv.zones.length; zi++) {
                int off = g.s32(lv.graphics.zoneGraphOffsetsOffset + zi * 8);
                if (off <= 0 || !g.inRange(off, 4)) {
                    continue;
                }
                PolyLoop.run(g, off, g.size(), new PolyLoop.Sink() {
                    @Override
                    public void surface(BinReader gg, int at, int type) {
                        seen[0]++;
                        int n = gg.s16(at + 2);
                        int fromDispatch = 2 * n + 14;
                        int fromSetup = fd.setup(gg, at,
                                type == PolyLoop.T_ROOF ? FloorDraw.ROOF
                                                        : FloorDraw.FLOOR).recordBytes;
                        if (fromDispatch != fromSetup) {
                            seen[1]++;
                        }
                    }
                });
            }
            System.out.printf("%-9s %5d surfaces, sizes disagreeing: %d%n",
                    name, seen[0], seen[1]);
        }
    }
}
