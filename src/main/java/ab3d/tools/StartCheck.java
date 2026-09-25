package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;

/** Is the start position where the level says, and in the units the points use? */
public final class StartCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < lv.numPoints; i++) {
                minX = Math.min(minX, lv.pointX[i]);
                maxX = Math.max(maxX, lv.pointX[i]);
                minZ = Math.min(minZ, lv.pointZ[i]);
                maxZ = Math.max(maxZ, lv.pointZ[i]);
            }
            Zone z = lv.zone(lv.startZone);
            boolean inside = z.exitLines.length > 0;
            for (int e : z.exitLines) {
                if (e >= lv.floorLines.length
                        || lv.floorLines[e].side(lv.startX, lv.startZ) < 0) {
                    inside = false;
                    break;
                }
            }
            System.out.printf("%-8s start (%d,%d) zone %d  points x %d..%d z %d..%d%n",
                              name, lv.startX, lv.startZ, lv.startZone,
                              minX, maxX, minZ, maxZ);
            System.out.printf("          start inside its zone: %s | "
                            + "zone floor %d roof %d height %d%n",
                            inside, z.floorHeight, z.roofHeight,
                            z.floorHeight - z.roofHeight);
        }
    }
}
