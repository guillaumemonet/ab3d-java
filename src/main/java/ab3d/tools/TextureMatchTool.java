package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.WadTexture;
import ab3d.data.ZoneGraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Narrows down which {@code .wad} each wall texture index refers to.
 *
 * The shipped build's {@code walltiles} table is not in the sources, but the
 * level data constrains it: every wall names a texture height and a strip, and a
 * strip must exist inside the file. Collecting the deepest strip each index ever
 * asks for rules out any wad too small to hold it.
 */
public final class TextureMatchTool {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();

        Map<Integer, Integer> maxStrip = new TreeMap<>();
        Map<Integer, TreeSet<Integer>> heights = new TreeMap<>();
        Map<Integer, Integer> uses = new TreeMap<>();

        for (String name : game.diskLevels()) {
            Level lv;
            try {
                lv = Level.load(game, name);
            } catch (RuntimeException e) {
                continue;
            }
            for (int z = 0; z < lv.zones.length; z++) {
                ZoneGraph g = lv.graphics.lowerGraph(z);
                if (g == null) {
                    continue;
                }
                for (ZoneGraph.Wall w : g.walls) {
                    int t = w.texture();
                    int strip = WadTexture.stripFor(Math.max(w.uLeft(), w.uRight()),
                            w.uMask(), w.tile());
                    maxStrip.merge(t, strip, Math::max);
                    heights.computeIfAbsent(t, k -> new TreeSet<>()).add(w.textureHeight());
                    uses.merge(t, 1, Integer::sum);
                }
            }
        }

        // Every wall texture, with how many strips it holds at each used height
        List<Path> wads = new ArrayList<>();
        try (var s = Files.list(game.root().resolve("includes/walls"))) {
            s.filter(p -> p.toString().endsWith(".wad")).sorted().forEach(wads::add);
        }
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (Path p : wads) {
            sizes.put(p.getFileName().toString(), (int) Files.size(p));
        }

        System.out.printf("%-6s %6s %8s %-12s %s%n",
                "index", "walls", "maxStrip", "heights", "wads that could hold it");
        for (int t : maxStrip.keySet()) {
            TreeSet<Integer> hs = heights.get(t);
            int needStrips = maxStrip.get(t) + 1;
            List<String> fits = new ArrayList<>();
            for (var e : sizes.entrySet()) {
                boolean all = true;
                for (int h : hs) {
                    if (WadTexture.PALETTE_BYTES + needStrips * h * 2 > e.getValue()) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    fits.add(e.getKey().replace(".wad", ""));
                }
            }
            System.out.printf("%-6d %6d %8d %-12s %s%n",
                    t, uses.get(t), maxStrip.get(t), hs, String.join(" ", fits));
        }
    }
}
