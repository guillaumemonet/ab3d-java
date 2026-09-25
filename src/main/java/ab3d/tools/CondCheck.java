package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.Doors;
import ab3d.engine.Frame68k;
import ab3d.engine.Lifts;

/**
 * Do any doors or lifts wait on a condition?
 *
 * {@code DoorRoutine} does {@code and.w Conditions,d2} and compares the result
 * with the word it read: every bit the record asks for must be set in the global
 * before the door will listen to its trigger at all. With no switch routine that
 * global stays empty, so a door asking for anything can never open.
 */
public final class CondCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            Frame68k f = new Frame68k(lv, game);
            int doorsCond = 0, liftsCond = 0;
            StringBuilder which = new StringBuilder();
            for (Doors.Door d : f.doors.doors) {
                if (d.conditions != 0) {
                    doorsCond++;
                    which.append(String.format(" door@zone%d=%d", d.zone, d.conditions));
                }
            }
            for (Lifts.Lift l : f.lifts.lifts) {
                if (l.conditions != 0) {
                    liftsCond++;
                    which.append(String.format(" lift@zone%d=%d", l.zone, l.conditions));
                }
            }
            System.out.printf("%-8s %d doors and %d lifts wait on a condition%s%n",
                              name, doorsCond, liftsCond,
                              which.length() == 0 ? "" : "  ->" + which);
        }
    }
}
