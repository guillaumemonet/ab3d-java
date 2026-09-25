package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;

/** Finds columns that come out much darker than their neighbours. */
public final class BarProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_c");
        int angle = args.length > 1 ? Integer.parseInt(args[1]) : 1536;
        Frame68k f = new Frame68k(lv, game);
        EngineState st = f.state();
        f.render(lv.startZone, lv.startX, lv.startZ,
                 lv.zone(lv.startZone).floorHeight - 12 * 1024, angle);

        int[] black = new int[EngineState.VIEW_COLUMNS];
        int[] lit = new int[EngineState.VIEW_COLUMNS];
        for (int r = 0; r < EngineState.VIEW_ROWS; r++) {
            for (int c = 0; c < EngineState.VIEW_COLUMNS; c++) {
                if ((st.screen[st.rowStart(r) + EngineState.columnWord(c)] & 0x0fff) == 0) {
                    black[c]++;
                } else {
                    lit[c]++;
                }
            }
        }
        int[] wr = f.wallRowsPerColumn();
        System.out.printf("%s angle %d%n", lv.name, angle);
        for (int c = 1; c + 1 < EngineState.VIEW_COLUMNS; c++) {
            if (black[c] > black[c - 1] + 15 && black[c] > black[c + 1] + 15) {
                System.out.printf("  column %2d: black %2d (neighbours %2d / %2d), "
                                + "wall rows %3d (neighbours %3d / %3d)%n",
                                c, black[c], black[c - 1], black[c + 1],
                                wr[c], wr[c - 1], wr[c + 1]);
            }
        }
        System.out.println("  clip windows (left, right, zone):");
        for (int[] w : f.clipWindows) {
            System.out.printf("    %3d..%3d zone %d%n", w[0], w[1], w[2]);
        }
    }
}
