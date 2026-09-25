package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.Doors;
import ab3d.engine.Frame68k;

/**
 * A conditioned door must stay shut until its bit is set, and open once it is.
 *
 * Both halves matter. Without the test a locked door opens on a touch, which is
 * what the port did; with it applied too widely every door would jam.
 */
public final class CondGateCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            int lockedHeld = 0, lockedOpened = 0, freeOpened = 0, freeStuck = 0;

            for (int which = 0; which < new Frame68k(lv, game).doors.doors.size(); which++) {
                // a fresh level each time, since the routines patch it in place
                Level fresh = Level.load(game, name);
                Frame68k f = new Frame68k(fresh, game);
                Doors.Door d = f.doors.doors.get(which);
                if (d.zone < 0 || d.zone >= fresh.zones.length) {
                    continue;
                }
                int start = d.height;

                // touch it for thirty frames with nothing unlocked
                boolean moved = press(fresh, f, d, 0);
                if (d.conditions != 0) {
                    if (moved) {
                        lockedOpened++;
                    } else {
                        lockedHeld++;
                    }
                } else if (moved) {
                    freeOpened++;
                } else {
                    freeStuck++;
                }

                if (d.conditions != 0) {
                    // now with its own bits set
                    Level again = Level.load(game, name);
                    Frame68k f2 = new Frame68k(again, game);
                    Doors.Door d2 = f2.doors.doors.get(which);
                    if (press(again, f2, d2, d2.conditions)) {
                        lockedOpened += 0;          // expected, counted below
                    } else {
                        System.out.printf("  %s door at zone %d stays shut even with "
                                        + "its condition %d set%n",
                                        name, d2.zone, d2.conditions);
                    }
                }
                if (start == d.height && d.conditions == 0) {
                    // nothing; already counted
                    continue;
                }
            }
            System.out.printf("%-8s locked doors held shut %d, wrongly opened %d | "
                            + "free doors opened %d, stuck %d%n",
                            name, lockedHeld, lockedOpened, freeOpened, freeStuck);
        }
    }

    /** Touches every one of a door's lines for thirty frames. */
    private static boolean press(Level lv, Frame68k f, Doors.Door d, int conditions) {
        int start = d.height;
        f.conditions = conditions;
        for (int i = 0; i < 30; i++) {
            for (int[] w : d.walls) {
                if (w[0] >= 0 && w[0] < lv.floorLines.length) {
                    int at = lv.floorLine(w[0]).offset + 14;
                    lv.data.setS16(at, lv.data.s16(at) | Doors.PLAYER1 | 0x8000);
                }
            }
            f.updateDoors(1, true);
        }
        return d.height != start;
    }
}
