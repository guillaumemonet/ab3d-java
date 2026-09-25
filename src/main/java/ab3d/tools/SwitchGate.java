package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.Doors;
import ab3d.engine.Frame68k;
import ab3d.engine.Switches;

/**
 * End to end: stand at a switch, press, and see a door that wanted its bit open.
 *
 * The chain has three links and each could be right alone while the whole fails
 * -- the press must reach the switch, the switch must arm the bit the door names,
 * and the door must then accept its trigger.
 */
public final class SwitchGate {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : new String[]{"level_a", "level_b", "level_c"}) {
            Level lv = Level.load(game, name);
            Frame68k f = new Frame68k(lv, game);

            for (Switches.Switch sw : f.switches.switches) {
                if (sw.present < 0 || sw.point < 0 || sw.point + 1 >= lv.numPoints) {
                    continue;
                }
                int mx = (lv.pointX[sw.point] + lv.pointX[sw.point + 1]) / 2;
                int mz = (lv.pointZ[sw.point] + lv.pointZ[sw.point + 1]) / 2;

                int before = f.conditions;
                f.updateSwitches(1, mx, mz, true);
                int after = f.conditions;
                boolean armed = before != after;

                // which doors were waiting on exactly this bit
                int bit = 1 << sw.bit;
                int waiting = 0;
                for (Doors.Door d : f.doors.doors) {
                    if ((d.conditions & bit) != 0) {
                        waiting++;
                    }
                }
                System.out.printf("%-8s switch bit %2d at (%6d,%6d): %s"
                                + " | %d doors wanted it%n",
                                name, sw.bit, mx, mz,
                                armed ? "armed on the press" : "DID NOT ARM", waiting);

                // and it must not arm from far away
                f.updateSwitches(1, mx + 400, mz + 400, true);
                if (f.conditions != after) {
                    System.out.printf("          <<< it also reacted from 400 units away%n");
                }
                f.conditions = 0;
            }
        }
    }
}
