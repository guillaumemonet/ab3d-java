package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;

/**
 * Checks the one assumption {@code RotateObjectPts} rests on: that object record
 * {@code i} owns object point {@code i}.
 *
 * The routine walks {@code a0} through the point table eight bytes at a time and
 * {@code a4} through the object records sixty-four bytes at a time, in the same
 * loop, so the pairing is positional. If the two lists were different lengths,
 * or if records did not name their own index as their point, the retirement test
 * would be reading another object's field.
 */
public final class ObjPairCheck {

    /** Only the crowded entries, so a lopsided distribution is obvious. */
    private static String trim(java.util.Map<Integer, Integer> m) {
        StringBuilder b = new StringBuilder();
        for (var e : m.entrySet()) {
            if (e.getValue() >= 5 || e.getKey() < 0) {
                b.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return b + "  (" + m.size() + " distinct)";
    }

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args : new String[]{"level_b", "level_d"}) {
            Level lv = Level.load(game, name);
            int n = lv.objectPointX.length;
            int records = lv.objects.size();
            int selfNamed = 0, retired = 0, negative = 0;
            int retiredAndNoZone = 0, liveAndNoZone = 0;
            for (int i = 0; i < records; i++) {
                GameObject o = lv.objects.get(i);
                if (o.pointIndex == i) {
                    selfNamed++;
                }
                if (o.pointIndex < 0) {
                    negative++;
                }
                if (o.notInPlay) {
                    retired++;
                    if (o.rawZone < 0) {
                        retiredAndNoZone++;
                    }
                } else if (o.rawZone < 0) {
                    liveAndNoZone++;
                }
            }
            System.out.printf("%s: %d object points, %d records, "
                            + "%d name their own index, %d retired, %d negative%n",
                            name, n, records, selfNamed, retired, negative);
            System.out.printf("    of the %d skipped, %d also had no zone; "
                            + "%d not skipped had no zone%n",
                            retired, retiredAndNoZone, liveAndNoZone);
            StringBuilder runs = new StringBuilder();
            int from = -1;
            for (int i = 0; i <= records; i++) {
                boolean r = i < records && lv.objects.get(i).notInPlay;
                if (r && from < 0) {
                    from = i;
                } else if (!r && from >= 0) {
                    runs.append(' ').append(from).append("..").append(i - 1);
                    from = -1;
                }
            }
            System.out.println("    skipped runs:" + runs);
            StringBuilder sl = new StringBuilder();
            for (int i = 0; i < records; i++) {
                if (lv.objects.get(i).notInPlay) {
                    sl.append(' ').append(lv.objects.get(i).slot);
                }
            }
            System.out.println("    their graphic slots:" + sl);
            int agree = 0, differ = 0, noneGiven = 0;
            for (GameObject o : lv.objects) {
                if (o.notInPlay) {
                    continue;
                }
                if (o.rawZone < 0) {
                    noneGiven++;
                    if (o.zone == o.inPlayZone) {
                        agree++;
                    } else {
                        differ++;
                    }
                }
            }
            java.util.Map<Integer,Integer> raw = new java.util.TreeMap<>();
            java.util.Map<Integer,Integer> inp = new java.util.TreeMap<>();
            java.util.Map<Integer,Integer> fin = new java.util.TreeMap<>();
            for (GameObject o : lv.objects) {
                if (o.notInPlay || !o.isSprite()) {
                    continue;
                }
                raw.merge(o.rawZone, 1, Integer::sum);
                inp.merge(o.inPlayZone, 1, Integer::sum);
                fin.merge(o.zone, 1, Integer::sum);
            }
            System.out.println("    rawZone(26) counts: " + trim(raw));
            System.out.println("    inPlay(12) counts : " + trim(inp));
            System.out.println("    final zone counts : " + trim(fin));
            System.out.printf("    of %d objects the file leaves without a zone, "
                            + "the guess matched offset 12 %d times, differed %d%n",
                            noneGiven, agree, differ);
        }
    }
}
