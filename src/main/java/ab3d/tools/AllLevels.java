package ab3d.tools;

import ab3d.data.EndZones;
import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;
import ab3d.engine.Enemy;
import ab3d.engine.Obj;

/** Every level the floppies carry, and whether the port can read it. */
public final class AllLevels {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        EndZones ends = EndZones.load(game);
        int ok = 0, tried = 0;
        for (String name : game.diskLevels()) {
            tried++;
            try {
                Level lv = Level.load(game, name);
                int enemies = 0, keys = 0;
                for (int i = 0; i < lv.objects.size(); i++) {
                    int b = lv.ptrObjects + i * GameObject.SIZE;
                    if (lv.data.s16(b + Obj.ZONE) < 0) {
                        continue;
                    }
                    int t = lv.data.u8(b + Obj.TYPE);
                    if (Enemy.kindOf(t) != null) {
                        enemies++;
                    }
                    if (t == 4) {
                        keys++;
                    }
                }
                int end = ends.of(name.charAt(name.length() - 1) - 'a');
                System.out.printf("%-8s %3d zones, %3d objects, %3d enemies, "
                                  + "%d keys, ends in zone %3d %s%n", name,
                                  lv.zones.length, lv.objects.size(), enemies,
                                  keys, end,
                                  end >= 0 && end < lv.zones.length ? "" : "<-- no such zone");
                ok++;
            } catch (Exception e) {
                System.out.printf("%-8s failed: %s%n", name, e);
            }
        }
        System.out.printf("%n%d of %d levels load%n", ok, tried);
    }
}
