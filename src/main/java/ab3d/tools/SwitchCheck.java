package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.Doors;
import ab3d.engine.Frame68k;
import ab3d.engine.Switches;

/**
 * The switches each level really has, and whether they unlock its doors.
 *
 * Two questions worth separating: do the records describe real switches, and do
 * the bits they own line up with the bits the doors are waiting for? A switch
 * that arms a bit nothing asks for would open nothing.
 */
public final class SwitchCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            Frame68k f = new Frame68k(lv, game);

            int real = 0, auto = 0, armed = 0;
            for (Switches.Switch sw : f.switches.switches) {
                if (sw.present < 0) {
                    continue;
                }
                real++;
                if (sw.auto) {
                    auto++;
                }
                armed |= 1 << sw.bit;
            }

            int wanted = 0;
            for (Doors.Door d : f.doors.doors) {
                wanted |= d.conditions;
            }
            int covered = wanted & armed;
            int uncovered = wanted & ~armed;

            System.out.printf("%-8s %d switches (%d release on their own) arming bits "
                            + "%s%n", name, real, auto, bits(armed));
            System.out.printf("          doors want %s | switches cover %s | "
                            + "left over %s%n",
                            bits(wanted), bits(covered), bits(uncovered));
        }
    }

    private static String bits(int v) {
        if (v == 0) {
            return "none";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if ((v & (1 << i)) != 0) {
                b.append(b.length() == 0 ? "" : ",").append(i);
            }
        }
        return b.toString();
    }
}
