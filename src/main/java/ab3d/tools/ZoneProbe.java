package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.engine.PolyLoop;

import java.util.TreeMap;
import java.util.Map;

/** What one zone's polygon stream contains, by tag. */
public final class ZoneProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args[0]);
        int zi = Integer.parseInt(args[1]);
        Zone z = lv.zone(zi);
        BinReader g = lv.graphics.data;

        System.out.printf("%s zone %d: floor %d roof %d water %d%n",
                          lv.name, zi, z.floorHeight, z.roofHeight, z.waterHeight);
        System.out.printf("  water sits %s the floor, %s the eye at start%n",
                          z.waterHeight < z.floorHeight ? "above" : "at or below",
                          z.waterHeight < z.floorHeight - 12 * 1024 ? "above" : "below");

        int at = lv.graphics.zoneGraphOffsetsOffset + zi * 8;
        Map<Integer, Integer> tags = new TreeMap<>();
        Map<Integer, Integer> waterY = new TreeMap<>();
        for (int off : new int[]{g.s32(at), g.s32(at + 4)}) {
            if (off <= 0 || !g.inRange(off, 2)) {
                continue;
            }
            PolyLoop.run(g, off, g.size(), new PolyLoop.Sink() {
                @Override
                public void wall(BinReader gg, int a, boolean t) {
                    tags.merge(t ? PolyLoop.T_SEE_WALL : PolyLoop.T_WALL, 1, Integer::sum);
                }

                @Override
                public void surface(BinReader gg, int a, int type) {
                    tags.merge(type, 1, Integer::sum);
                    if (type == PolyLoop.T_WATER) {
                        waterY.merge(gg.s16(a), 1, Integer::sum);
                    }
                }

                @Override
                public void object(BinReader gg, int a) {
                    tags.merge(PolyLoop.T_OBJECT, 1, Integer::sum);
                }
            });
        }
        System.out.println("  tags in the stream: " + tags
                + "   (7 = water, 1 = floor, 2 = roof, 0 = wall)");
        if (!waterY.isEmpty()) {
            System.out.println("  water surface heights: " + waterY);
        }
    }
}
