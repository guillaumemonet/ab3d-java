package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.game.Player;

/**
 * Can the player reach an upper storey, and does the flag come back down?
 *
 * {@code StoodInTop} is set at a floor-line crossing from the height there
 * against the roof of the room being entered, so it can only change by walking
 * through a doorway -- and it has to change both ways, or a player who once got
 * upstairs would be stuck there for the rest of the level.
 *
 * The test walks out of every room that has a second storey, in every direction,
 * and watches the flag. A room with an upper storey nobody can walk into would
 * report nothing, which is itself worth knowing.
 */
public final class StoreyCheck {

    private static final int STEPS = 60;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : new String[]{"level_a", "level_b", "level_d"}) {
            Level lv = Level.load(game, name);
            int rooms = 0, reached = 0, cameBack = 0;

            for (int zi = 0; zi < lv.zones.length; zi++) {
                Zone z = lv.zone(zi);
                if (z.upperFloorHeight == z.floorHeight
                        || z.upperFloorHeight - z.upperRoofHeight <= 0) {
                    continue;
                }
                rooms++;

                // start in the middle of that room's own lines
                long mx = 0, mz = 0;
                int n = 0;
                for (int e : z.exitLines) {
                    if (e >= 0 && e < lv.floorLines.length) {
                        mx += lv.floorLine(e).x1;
                        mz += lv.floorLine(e).z1;
                        n++;
                    }
                }
                if (n == 0) {
                    continue;
                }
                // Start upstairs, as a lift would leave the player. Walking out
                // from the floor can never set the flag: the crossing test
                // compares the eye against the room being entered, and an eye at
                // floor level is below every roof there is.
                boolean wentUp = false, wentDown = false;
                for (int dir = 0; dir < 8; dir++) {
                    Player p = new Player(lv);
                    p.camera.zone = zi;
                    p.stoodInTop = true;
                    p.camera.x = (int) (mx / n);
                    p.camera.z = (int) (mz / n);
                    double a = dir / 8.0 * 2 * Math.PI;
                    int dx = (int) Math.round(Math.sin(a) * 24);
                    int dz = (int) Math.round(Math.cos(a) * 24);
                    boolean was = p.stoodInTop;
                    int startY = p.camera.yoff;
                    for (int t = 0; t < STEPS; t++) {
                        p.move(dx, dz);
                        if (p.stoodInTop && !was) {
                            wentUp = true;
                        }
                        if (!p.stoodInTop && was) {
                            wentDown = true;
                        }
                        was = p.stoodInTop;
                    }
                }
                if (wentUp) {
                    reached++;
                }
                if (wentDown) {
                    cameBack++;
                }
                Player at = new Player(lv);
                at.camera.zone = zi;
                at.stoodInTop = true;
                at.move(0, 0);
                int upEye = at.camera.yoff;
                at.stoodInTop = false;
                at.move(0, 0);
                int downEye = at.camera.yoff;
                System.out.printf("  %s zone %3d: eye upstairs %6d, downstairs "
                                  + "%6d (%s)  %s%s%n", name, zi, upEye, downEye,
                                  upEye < downEye ? "higher upstairs"
                                          : "<-- not higher upstairs",
                                  wentUp ? "went up" : "stayed up or left",
                                  wentDown ? ", came back down" : "");
            }
            System.out.printf("%-8s %d rooms with a second storey, %d reachable, "
                              + "%d return%n%n", name, rooms, reached, cameBack);
        }
    }
}
