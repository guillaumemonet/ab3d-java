package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.game.Player;

/**
 * Checks the crouch: does it reach its target, and does a low room force it?
 *
 * The height eases by 1024 a frame between 12*1024 standing and 8*1024 crouched,
 * so four frames each way. A room with three units or less of clearance over
 * standing height forces the crouch whatever the key says.
 */
public final class CrouchCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);

            int low = 0;
            for (Zone z : lv.zones) {
                if (z.floorHeight - z.roofHeight <= Player.EYE_HEIGHT + 3 * 1024) {
                    low++;
                }
            }

            Player p = new Player(lv);
            p.tick();
            int stand = p.height;
            p.toggleDuck();
            int frames = 0;
            while (p.height != Player.CROUCH_HEIGHT && frames < 40) {
                p.tick();
                frames++;
            }
            int down = frames;
            p.toggleDuck();
            frames = 0;
            while (p.height != Player.EYE_HEIGHT && frames < 40) {
                p.tick();
                frames++;
            }
            System.out.printf("%-8s standing %d, crouch reached in %d frames, "
                            + "stand again in %d | %d of %d zones force a crouch%n",
                            name, stand, down, frames, low, lv.zones.length);
        }
    }
}
