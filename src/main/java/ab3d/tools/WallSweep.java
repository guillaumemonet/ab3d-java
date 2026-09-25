package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;
import ab3d.game.Player;

/** Where, between the middle of a room and its wall, the view starts to fail. */
public final class WallSweep {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_a");
        Frame68k frame = new Frame68k(lv, game);
        Player p = new Player(lv);
        int angle = args.length > 1 ? Integer.parseInt(args[1]) : 2048;
        p.camera.angle = angle;
        int x0 = p.camera.x, z0 = p.camera.z;

        double a = angle / 4096.0 * 2 * Math.PI;
        int from = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int to = args.length > 3 ? Integer.parseInt(args[3]) : 220;
        int by = args.length > 4 ? Integer.parseInt(args[4]) : 20;
        for (int back = from; back <= to; back += by) {
            p.camera.x = x0 + (int) Math.round(Math.sin(a) * back);
            p.camera.z = z0 + (int) Math.round(Math.cos(a) * back);
            frame.render(p.camera.zone, p.camera.x, p.camera.z, p.camera.yoff,
                         p.camera.angle);
            EngineState st = frame.state();
            int black = 0;
            for (int row = 0; row < EngineState.VIEW_ROWS; row++) {
                int base = st.rowStart(row);
                for (int col = 0; col < EngineState.VIEW_COLUMNS; col++) {
                    if ((st.screen[base + EngineState.columnWord(col)] & 0x0fff) == 0) {
                        black++;
                    }
                }
            }
            System.out.printf("  %3d along: (%5d,%5d) %2d walls %2d floors, "
                              + "%4d of %d black%n", back, p.camera.x, p.camera.z,
                              frame.wallsDrawn, frame.surfacesDrawn, black,
                              EngineState.VIEW_ROWS * EngineState.VIEW_COLUMNS);
        }
    }
}
