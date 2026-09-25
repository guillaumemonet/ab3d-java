package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.engine.PolyLoop;

/** Do the levels use the backdrop tag, and does any zone ask for one? */
public final class BackdropCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            BinReader g = lv.graphics.data;
            int tags = 0, setClips = 0, others = 0, zonesAsking = 0;
            for (Zone z : lv.zones) {
                if (z.drawBackdrop != 0) {
                    zonesAsking++;
                }
            }
            for (int zi = 0; zi < lv.zones.length; zi++) {
                int at = lv.graphics.zoneGraphOffsetsOffset + zi * 8;
                if (!g.inRange(at, 8)) {
                    continue;
                }
                for (int off : new int[]{g.s32(at), g.s32(at + 4)}) {
                    if (off <= 0 || !g.inRange(off, 2)) {
                        continue;
                    }
                    PolyLoop.Result r = PolyLoop.run(g, off, g.size(),
                                                     new PolyLoop.Sink() { });
                    tags += r.backdrops;
                    setClips += r.setClips;
                    others += r.others;
                }
            }
            System.out.printf("%-8s backdrop tags in the streams: %d | "
                            + "set-clip tags: %d | other tags: %d | "
                            + "zones with ToBack set: %d of %d%n",
                            name, tags, setClips, others, zonesAsking,
                            lv.zones.length);
        }
    }
}
