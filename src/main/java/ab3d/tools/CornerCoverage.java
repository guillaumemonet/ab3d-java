package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.engine.PolyLoop;

/** Do a surface's corners appear in the point list the zone asks to be rotated? */
public final class CornerCoverage {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");
        BinReader g = lv.graphics.data;
        int[] n = { 0, 0 };

        for (int zi = 0; zi < lv.zones.length; zi++) {
            Zone zone = lv.zone(zi);
            java.util.Set<Integer> rotated = new java.util.HashSet<>();
            for (int p : zone.points) {
                rotated.add(p);
            }
            int off = g.s32(lv.graphics.zoneGraphOffsetsOffset + zi * 8);
            if (off <= 0 || !g.inRange(off, 4)) {
                continue;
            }
            PolyLoop.run(g, off, g.size(), new PolyLoop.Sink() {
                @Override
                public void surface(BinReader gg, int at, int type) {
                    int sides = gg.s16(at + 2);
                    for (int i = 0; i <= sides + 1; i++) {
                        int c = gg.s16(at + 4 + i * 2);
                        if (c < 0 || c >= lv.numPoints) {
                            continue;
                        }
                        n[0]++;
                        if (!rotated.contains(c)) {
                            n[1]++;
                        }
                    }
                }
                @Override
                public void wall(BinReader gg, int at, boolean see) {
                    for (int o : new int[] { 0, 2 }) {
                        int c = gg.s16(at + o);
                        if (c >= 0 && c < lv.numPoints) {
                            n[0]++;
                            if (!rotated.contains(c)) {
                                n[1]++;
                            }
                        }
                    }
                }
            });
        }
        System.out.printf("%s: %d corner references, %d not in the zone's rotate list (%.1f%%)%n",
                lv.name, n[0], n[1], n[0] == 0 ? 0.0 : 100.0 * n[1] / n[0]);
    }
}
