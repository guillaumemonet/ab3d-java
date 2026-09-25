package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.game.Player;

/** Can the player get out of a spot? Tries every direction and reports. */
public final class MoveProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args[0]);
        SineTable sine = SineTable.load(game);
        int zi = args.length > 1 ? Integer.parseInt(args[1]) : lv.startZone;
        int x = args.length > 3 ? Integer.parseInt(args[2]) : lv.startX;
        int z = args.length > 3 ? Integer.parseInt(args[3]) : lv.startZ;

        int blocked = 0, moved = 0;
        StringBuilder stuck = new StringBuilder();
        for (int a = 0; a < 4096; a += 128) {
            Player p = new Player(lv);
            p.camera.zone = zi;
            p.camera.x = x;
            p.camera.z = z;
            p.camera.angle = a;
            double s = sine.sin(a) / 32768.0, c = sine.cos(a) / 32768.0;
            p.move((int) Math.round(s * 60), (int) Math.round(c * 60));
            if (p.camera.x == x && p.camera.z == z) {
                blocked++;
                stuck.append(' ').append(a);
            } else {
                moved++;
            }
        }
        System.out.printf("%s from zone %d (%d,%d): %d directions move, %d blocked%n",
                          lv.name, zi, x, z, moved, blocked);
        if (blocked > 0) {
            System.out.println("  blocked at angles:" + stuck);
        }
    }
}
