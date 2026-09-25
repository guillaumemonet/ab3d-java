package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.ZoneGraph;

import java.util.TreeMap;

/** Summarises the surface records of a level: which tiles and scales it uses. */
public final class SurfaceTool {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");

        TreeMap<Integer, Integer> tiles = new TreeMap<>();
        TreeMap<Integer, Integer> scales = new TreeMap<>();
        TreeMap<Integer, Integer> types = new TreeMap<>();
        int n = 0;
        for (int i = 0; i < lv.zones.length; i++) {
            ZoneGraph g = lv.graphics.lowerGraph(i);
            if (g == null) {
                continue;
            }
            for (ZoneGraph.Surface s : g.surfaces) {
                n++;
                tiles.merge(s.tile(), 1, Integer::sum);
                scales.merge(s.scale(), 1, Integer::sum);
                types.merge(s.type(), 1, Integer::sum);
            }
        }
        if (args.length > 1) {
            System.out.println("per-zone detail:");
            for (int k = 1; k < args.length; k++) {
                int zi = Integer.parseInt(args[k]);
                ab3d.data.Zone z = lv.zone(zi);
                System.out.printf("  zone %3d  floor=%8d roof=%8d%n",
                        zi, z.floorHeight, z.roofHeight);
                ZoneGraph g = lv.graphics.lowerGraph(zi);
                if (g == null) {
                    System.out.println("    (no graph)");
                    continue;
                }
                for (ZoneGraph.Surface sf : g.surfaces) {
                    System.out.printf("    surface type=%2d y=%6d tile=%4d scale=%3d "
                            + "bright=%4d pts=%d%n",
                            sf.type(), sf.y(), sf.tile(), sf.scale(),
                            sf.brightness(), sf.points().length);
                }
            }
            return;
        }

        System.out.println(lv.name + ": " + n + " surfaces");
        System.out.println("  types  : " + types);
        System.out.println("  scales : " + scales);
        System.out.println("  tiles  : " + tiles);
        System.out.println("  tile gcd: " + gcd(tiles.keySet()));
    }

    private static int gcd(Iterable<Integer> values) {
        int g = 0;
        for (int v : values) {
            g = gcd(g, Math.abs(v));
        }
        return g;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
