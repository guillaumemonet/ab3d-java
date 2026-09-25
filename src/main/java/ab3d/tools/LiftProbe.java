package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.Frame68k;
import ab3d.engine.Lifts;

/** Runs a lift through a cycle and shows the zone floor following it. */
public final class LiftProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            Frame68k f = new Frame68k(lv, game);
            System.out.printf("%s: %d lifts%n", name, f.lifts.lifts.size());
            if (f.lifts.lifts.isEmpty()) {
                continue;
            }
            Lifts.Lift l = f.lifts.lifts.get(0);
            System.out.printf("  zone %d, travel %d..%d, now %d, "
                            + "raise kind %d lower kind %d, walls %d%n",
                            l.zone, l.bottom, l.top, l.height,
                            l.raiseKind, l.lowerKind, l.walls.size());
            int startFloor = lv.zone(l.zone).floorHeight;
            for (int i = 0; i < 30; i++) {
                boolean aboard = i >= 2 && i < 5;
                // standing on it and pressing the key is the kind-0 trigger
                f.lifts.update(1, aboard ? l.zone : -1, aboard);
                if (i % 6 == 0 || l.atTop() || l.atBottom()) {
                    System.out.printf("   frame %2d %-6s height %5d floor %7d %s%n",
                                      i, aboard ? "aboard" : "away", l.height,
                                      lv.zone(l.zone).floorHeight,
                                      l.atTop() ? "at top"
                                                : l.atBottom() ? "at bottom" : "moving");
                }
                if (l.atTop() && i > 5) {
                    break;
                }
            }
            System.out.printf("   zone floor went from %d to %d%n",
                              startFloor, lv.zone(l.zone).floorHeight);
        }
    }
}
