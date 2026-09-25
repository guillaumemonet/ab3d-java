package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.PolyLoop;

import java.util.TreeMap;
import java.util.Map;

/** Counts every polygon tag in every zone, so effort follows the data. */
public final class TagCensus {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            BinReader g = lv.graphics.data;
            Map<String, Integer> tally = new TreeMap<>();
            for (int zi = 0; zi < lv.zones.length; zi++) {
                int at = lv.graphics.zoneGraphOffsetsOffset + zi * 8;
                if (!g.inRange(at, 8)) {
                    continue;
                }
                for (int off : new int[]{g.s32(at), g.s32(at + 4)}) {
                    if (off <= 0 || !g.inRange(off, 2)) {
                        continue;
                    }
                    PolyLoop.run(g, off, g.size(), new PolyLoop.Sink() {
                        @Override
                        public void wall(BinReader gg, int a, boolean seeThrough) {
                            tally.merge(seeThrough ? "see-through wall" : "wall",
                                        1, Integer::sum);
                        }

                        @Override
                        public void surface(BinReader gg, int a, int type) {
                            tally.merge(type == PolyLoop.T_ROOF ? "roof"
                                      : type == PolyLoop.T_WATER ? "water" : "floor",
                                        1, Integer::sum);
                        }

                        @Override
                        public void object(BinReader gg, int a) {
                            tally.merge("object", 1, Integer::sum);
                        }


                    });
                }
            }
            System.out.printf("%-8s %s%n", name, tally);
        }
    }
}
