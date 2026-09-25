package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;

/**
 * How many rooms actually have a second storey?
 *
 * {@code StoodInTop} only matters where one exists, and a zone carries the upper
 * pair whether or not it uses them -- so the question is how many have an upper
 * floor and roof that enclose a real volume, and how many of those a player
 * could ever be standing in.
 */
public final class UpperCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            int real = 0, tall = 0;
            for (int i = 0; i < lv.zones.length; i++) {
                Zone z = lv.zone(i);
                int lower = z.floorHeight - z.roofHeight;
                int upper = z.upperFloorHeight - z.upperRoofHeight;
                if (upper > 0 && z.upperFloorHeight != z.floorHeight) {
                    real++;
                    // a player is twelve thousand units tall plus headroom
                    if (upper > 15 * 1024) {
                        tall++;
                    }
                }
                if (i < 0) {
                    System.out.println(lower);
                }
            }
            System.out.printf("%-8s %3d zones, %3d with a distinct upper storey, "
                              + "%3d of those tall enough to stand in%n",
                              name, lv.zones.length, real, tall);
        }
    }
}
