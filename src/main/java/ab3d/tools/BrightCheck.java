package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;

import java.util.TreeMap;
import java.util.Map;

/** What the zone brightness words actually hold, and whether a high byte is used. */
public final class BrightCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            Map<Integer, Integer> low = new TreeMap<>(), high = new TreeMap<>();
            int negative = 0;
            for (Zone z : lv.zones) {
                for (int v : new int[]{z.brightness, z.upperBrightness}) {
                    if (v < 0) {
                        negative++;
                        continue;
                    }
                    low.merge(v & 0xff, 1, Integer::sum);
                    high.merge((v >> 8) & 0xff, 1, Integer::sum);
                }
            }
            System.out.printf("%-8s %d negative | high bytes %s%n", name, negative, high);
            System.out.printf("          low bytes %s%n", low);
        }
    }
}
