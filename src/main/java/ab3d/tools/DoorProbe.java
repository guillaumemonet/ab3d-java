package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.Doors;
import ab3d.engine.Frame68k;

/** Runs the door routine and shows a door opening and closing again. */
public final class DoorProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_c");
        Frame68k f = new Frame68k(lv, game);
        if (f.doors.doors.isEmpty()) {
            System.out.println("no doors");
            return;
        }
        Doors.Door d = f.doors.doors.get(0);
        System.out.printf("%s: %d doors%n", lv.name, f.doors.doors.size());
        for (Doors.Door dd : f.doors.doors) {
            System.out.printf("  zone %3d travel %5d..%5d trigger %d closeMode %d walls %d%n",
                              dd.zone, dd.bottom, dd.top, dd.trigger, dd.closeMode,
                              dd.walls.size());
        }
        for (int i = 0; i < 44; i++) {
            boolean near = i >= 2 && i < 6;
            if (near) {
                // stand on the door's own lines, as the mover would
                for (int[] w : d.walls) {
                    if (w[0] >= 0 && w[0] < lv.floorLines.length) {
                        lv.data.setS16(lv.floorLine(w[0]).offset + 14,
                                       Doors.PLAYER1 | 0x8000);
                    }
                }
            }
            f.doors.update(1, true);
            System.out.printf("  frame %2d %s height %5d roof %7d %s%n",
                              i, near ? "near " : "away ", d.height,
                              lv.zone(d.zone).roofHeight,
                              d.closed() ? "closed" : d.open() ? "open" : "moving");
        }
    }
}
