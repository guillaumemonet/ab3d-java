package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;
import ab3d.engine.Enemy;
import ab3d.engine.Obj;

/** The heights a bullet is launched between, against its zone's own. */
public final class BulletProbe {

    public static void main(String[] args) throws Exception {
        Level lv = Level.load(GameData.fromSystemProperty(), "level_b");
        for (int i = 0; i < lv.objects.size(); i++) {
            int base = lv.ptrObjects + i * GameObject.SIZE;
            if (Enemy.kindOf(lv.data.u8(base + Obj.TYPE)) == null
                    || lv.data.s16(base + Obj.ZONE) < 0) {
                continue;
            }
            int zone = lv.data.s16(base + Obj.ZONE);
            int h = lv.data.s16(base + Obj.HEIGHT);
            System.out.printf("enemy in zone %d: height %d (<<7 = %d), "
                              + "zone floor %d roof %d, muzzle %d%n",
                              zone, h, h << 7,
                              lv.zone(zone).floorHeight, lv.zone(zone).roofHeight,
                              (h << 7) + 20 * 128);
            System.out.printf("  floor - acc = %d (hits at <= %d), "
                              + "roof - acc = %d (hits at >= %d)%n",
                              lv.zone(zone).floorHeight - ((h << 7) + 20 * 128),
                              10 * 128,
                              lv.zone(zone).roofHeight - ((h << 7) + 20 * 128),
                              10 * 128);
            return;
        }
    }
}
