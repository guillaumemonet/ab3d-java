package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.Sound;
import ab3d.game.Player;

/**
 * How often a footstep lands, and which one.
 *
 * The counter takes sixteen times the forward speed a frame and a step lands
 * every four thousand and ninety-six, so walking, running and ducking should
 * each give a different rate, and the rate should be exactly what that
 * arithmetic says rather than approximately.
 *
 * Which sample plays comes from the room the player stands in, and the levels
 * use all three: {@code ToFloorNoise} is nought, one or two across the zones,
 * which is the muffled step, the clop and the clank.
 */
public final class ClumpCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_a");

        // which floor noises the level actually uses
        int[] noises = new int[8];
        for (int i = 0; i < lv.zones.length; i++) {
            int n = lv.zone(i).floorNoise;
            if (n >= 0 && n < noises.length) {
                noises[n]++;
            }
        }
        System.out.print("floor noises across the zones:");
        for (int i = 0; i < noises.length; i++) {
            if (noises[i] > 0) {
                System.out.printf("  %d in %d zones", i, noises[i]);
            }
        }
        System.out.println();

        for (int[] how : new int[][]{{25, 0}, {50, 0}, {25, 1}, {50, 1}}) {
            Player p = new Player(lv);
            if (how[1] == 1) {
                p.toggleDuck();
            }
            int[] heard = new int[1];
            int[] which = new int[64];
            Sound sink = (sample, x, z, volume, id, notIfPlaying) -> {
                heard[0]++;
                if (sample >= 0 && sample < which.length) {
                    which[sample]++;
                }
            };
            for (int t = 0; t < 300; t++) {
                p.clump(how[0], sink);
            }
            int want = (how[1] == 1 ? how[0] / 2 : how[0]) * 16 * 300 / 4096;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < which.length; i++) {
                if (which[i] > 0) {
                    b.append(' ').append(i).append('x').append(which[i]);
                }
            }
            System.out.printf("%-8s speed %2d: %2d steps in 300 frames "
                              + "(%d expected), samples%s%n",
                              how[1] == 1 ? "ducked" : "upright", how[0],
                              heard[0], want, b);
        }
    }
}
