package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;

import java.util.TreeMap;

/**
 * Lists a level's objects: where they stand, which graphic slot and frame they
 * use, and how big their sprite is. The sprite dimensions are what the bitmap
 * decoder needs, and they live here rather than in the graphics files.
 */
public final class ObjectTool {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String name = args.length > 0 ? args[0] : "level_b";
        Level lv = Level.load(game, name);

        System.out.println(name + ": " + lv.objects.size() + " objects, "
                + lv.objectPointX.length + " object points");

        TreeMap<Integer, Integer> slots = new TreeMap<>();
        TreeMap<String, Integer> dims = new TreeMap<>();
        TreeMap<Integer, Integer> framesPerSlot = new TreeMap<>();
        int polygons = 0, badZone = 0, badPoint = 0;

        for (GameObject o : lv.objects) {
            if (o.zone < 0 || o.zone >= lv.zones.length) {
                badZone++;
            }
            if (o.pointIndex < 0 || o.pointIndex >= lv.objectPointX.length) {
                badPoint++;
            }
            if (!o.isSprite()) {
                polygons++;
                continue;
            }
            slots.merge(o.slot, 1, Integer::sum);
            dims.merge(o.spriteWidth + "x" + o.spriteHeight, 1, Integer::sum);
            framesPerSlot.merge(o.slot, o.frame, Math::max);
        }

        System.out.println("  polygon objects : " + polygons);
        System.out.println("  slots in use    : " + slots);
        System.out.println("  highest frame   : " + framesPerSlot);
        System.out.println("  sprite sizes    : " + dims);
        System.out.printf("  sanity: %d with a bad zone, %d with a bad point index%n",
                badZone, badPoint);

        System.out.println("\n  first objects:");
        for (int i = 0; i < Math.min(12, lv.objects.size()); i++) {
            GameObject o = lv.objects.get(i);
            String where = o.pointIndex < lv.objectPointX.length
                    ? String.format("(%d, %d)", lv.objectPointX[o.pointIndex],
                            lv.objectPointZ[o.pointIndex])
                    : "?";
            System.out.println("    " + o + " at " + where);
        }
    }
}
