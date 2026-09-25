package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.engine.PolyLoop;
import ab3d.data.BinReader;

import java.util.HashSet;
import java.util.Set;

/**
 * Does the point list a zone offers cover every point its visible rooms draw?
 *
 * {@code RotateLevelPts} is called once a frame with the viewer's own zone list,
 * so any point a drawn room references that the list omits keeps whatever the
 * previous frame left in it. That shows up as geometry that appears and vanishes
 * with the viewer's position rather than with its own.
 */
public final class CoverCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            BinReader g = lv.graphics.data;
            int zonesShort = 0, missingTotal = 0, checkedZones = 0;
            String worst = "";
            int worstN = 0;

            for (int zi = 0; zi < lv.zones.length; zi++) {
                Zone here = lv.zone(zi);
                if (here.graphics.isEmpty()) {
                    continue;
                }
                checkedZones++;
                Set<Integer> rotated = new HashSet<>();
                for (int p : here.points) {
                    rotated.add(p);
                }
                Set<Integer> needed = new HashSet<>();
                for (Zone.GraphEntry e : here.graphics) {
                    int zn = e.graphNumber();
                    if (zn < 0 || zn >= lv.zones.length) {
                        continue;
                    }
                    int at = lv.graphics.zoneGraphOffsetsOffset + zn * 8;
                    if (!g.inRange(at, 8)) {
                        continue;
                    }
                    for (int off : new int[]{g.s32(at), g.s32(at + 4)}) {
                        if (off <= 0 || !g.inRange(off, 2)) {
                            continue;
                        }
                        PolyLoop.run(g, off, g.size(), new PolyLoop.Sink() {
                            @Override
                            public void wall(BinReader gg, int a, boolean t) {
                                needed.add(gg.s16(a));
                                needed.add(gg.s16(a + 2));
                            }

                            @Override
                            public void surface(BinReader gg, int a, int type) {
                                int sides = gg.s16(a + 2);
                                for (int i = 0; i <= sides; i++) {
                                    needed.add(gg.s16(a + 4 + i * 2));
                                }
                            }
                        });
                    }
                }
                // The clip points each appearance names must be rotated too:
                // NEWsetlclip reads Rotated and OnScreen for them directly.
                for (int i = 0; i < here.graphics.size(); i++) {
                    int at2 = here.clipAt[i];
                    if (at2 < 0) {
                        continue;
                    }
                    int w = at2;
                    for (int run = 0; run < 2; run++) {
                        while (lv.clips.inRange(w * 2, 2) && lv.clips.s16(w * 2) >= 0) {
                            needed.add(lv.clips.s16(w * 2));
                            w++;
                        }
                        w++;
                    }
                }
                needed.removeAll(rotated);
                needed.removeIf(p -> p < 0);
                if (!needed.isEmpty()) {
                    zonesShort++;
                    missingTotal += needed.size();
                    if (needed.size() > worstN) {
                        worstN = needed.size();
                        worst = "zone " + zi + " misses " + needed.size()
                              + " of " + (rotated.size() + needed.size());
                    }
                }
            }
            System.out.printf("%-8s %3d zones checked, %3d short of points, "
                            + "%5d point references uncovered | %s%n",
                            name, checkedZones, zonesShort, missingTotal, worst);
        }
    }
}
