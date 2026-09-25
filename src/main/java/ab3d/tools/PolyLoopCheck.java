package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.PolyLoop;

import java.util.TreeMap;

/**
 * Walks every zone's drawing stream with the transcribed dispatch, using only
 * the record sizes the routines themselves imply. A stream that ends on its
 * terminator confirms those sizes; one that stops on a byte that is not a tag
 * means a size is wrong.
 */
public final class PolyLoopCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String[] levels = args.length > 0 ? args
                : new String[] { "level_a", "level_b", "level_c", "level_d", "lev1", "lev2" };

        for (String name : levels) {
            Level lv;
            try {
                lv = Level.load(game, name);
            } catch (RuntimeException | java.io.IOException e) {
                System.out.printf("%-9s skipped (%s)%n", name, e.getMessage());
                continue;
            }
            BinReader g = lv.graphics.data;

            // Each stream ends where the next begins
            java.util.TreeSet<Integer> starts = new java.util.TreeSet<>();
            for (int i = 0; i < lv.zones.length; i++) {
                for (int half = 0; half < 2; half++) {
                    int o = g.s32(lv.graphics.zoneGraphOffsetsOffset + i * 8 + half * 4);
                    if (o > 0 && g.inRange(o, 4)) {
                        starts.add(o);
                    }
                }
            }
            int areaEnd = Math.min(lv.graphics.doorDataOffset > 0
                    ? lv.graphics.doorDataOffset : g.size(), g.size());

            int terminated = 0, stopped = 0, walls = 0, surfaces = 0, objects = 0;
            int slackTotal = 0, worstSlack = 0;
            TreeMap<Integer, Integer> badTags = new TreeMap<>();

            for (int i = 0; i < lv.zones.length; i++) {
                int o = g.s32(lv.graphics.zoneGraphOffsetsOffset + i * 8);
                if (o <= 0 || !g.inRange(o, 4)) {
                    continue;
                }
                Integer nxt = starts.higher(o);
                int limit = nxt != null ? Math.min(nxt, areaEnd) : areaEnd;

                PolyLoop.Result r = PolyLoop.run(g, o, limit, new PolyLoop.Sink() { });
                walls += r.walls;
                surfaces += r.surfaces;
                objects += r.objects;
                if (r.terminated) {
                    terminated++;
                    int slack = limit - r.end;
                    slackTotal += slack;
                    worstSlack = Math.max(worstSlack, slack);
                } else {
                    stopped++;
                    if (r.badTag != Integer.MIN_VALUE) {
                        badTags.merge(r.badTag, 1, Integer::sum);
                    }
                }
            }

            System.out.printf("%-9s %3d terminated, %3d stopped | walls %4d surfaces %4d "
                    + "objects %3d | slack avg %.1f worst %d%n",
                    name, terminated, stopped, walls, surfaces, objects,
                    terminated == 0 ? 0.0 : (double) slackTotal / terminated, worstSlack);
            if (!badTags.isEmpty()) {
                System.out.println("            tags that stopped a walk: " + badTags);
            }
        }
    }
}
