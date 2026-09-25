package ab3d.tools;

import ab3d.data.EndZones;
import ab3d.data.GameData;
import ab3d.data.Level;

/**
 * Do the rooms that end a level exist in the levels they end?
 *
 * {@code ENDZONES} is a bare list of sixteen numbers with nothing tying them to
 * the files, so the only check available is that each one names a room the level
 * actually has -- and, more tellingly, one the player could stand in.
 */
public final class EndZoneCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        EndZones ends = EndZones.load(game);
        System.out.printf("ENDZONES holds %d entries%n%n", ends.count());
        String[] names = {"level_a", "level_b", "level_c", "level_d"};
        for (int i = 0; i < names.length; i++) {
            Level lv = Level.load(game, names[i]);
            int z = ends.of(i);
            boolean real = z >= 0 && z < lv.zones.length;
            System.out.printf("%-8s ends in zone %3d of %3d  %s%s%n", names[i], z,
                              lv.zones.length, real ? "exists" : "<-- out of range",
                              real && lv.zone(z).floorHeight
                                      - lv.zone(z).roofHeight > 12 * 1024
                                      ? ", tall enough to stand in" : "");
        }
        System.out.println("\nthe rest, which the port has no level for:");
        for (int i = names.length; i < ends.count(); i++) {
            System.out.printf("  level %2d: zone %d%n", i + 1, ends.of(i));
        }
    }
}
