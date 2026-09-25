package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;

/**
 * Checks the load-time clip fixup.
 *
 * The criterion is set by how the routines read it back: {@code NEWsetlclip}
 * indexes the connect table with {@code (a3,d2.w*4)} where d2 is a point number,
 * so whatever the clip walk leaves behind must be four bytes per point. If the
 * walk consumed too little or too much, the tail will not come out at that size.
 */
public final class ClipCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            int words = lv.clips.size() / 2;
            int tail = words - lv.connectTableAt;
            int lists = 0, nulls = 0;
            for (Zone z : lv.zones) {
                for (int i = 0; i < z.graphics.size(); i++) {
                    if (z.clipAt[i] < 0) {
                        nulls++;
                    } else {
                        lists++;
                    }
                }
            }
            System.out.printf("%-8s %6d words, %4d lists, %4d null, "
                            + "tail %5d words = %5d bytes, points %4d, 4*points %5d %s%n",
                            name, words, lists, nulls, tail, tail * 2,
                            lv.numPoints, lv.numPoints * 4,
                            tail * 2 == lv.numPoints * 4 ? "OK" : "short");
            System.out.printf("   tail/4 = %d   points %d  floorLines %d  "
                            + "zones %d  objectPoints %d%n",
                            tail / 2, lv.numPoints, lv.numFloorLines,
                            lv.zones.length, lv.numObjectPoints);
        }
    }
}
