package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;
import ab3d.game.Player;

/**
 * Which colours the view is actually made of, and where the red comes from.
 *
 * Large flat areas of one colour are what a texture looks like when it is not
 * being read -- a wall drawn from a palette entry rather than from its own
 * pixels. Counting how many screen columns hold a single repeated value says
 * whether that is what is happening, and the twelve-bit value itself says which
 * palette entry it is.
 */
public final class RedProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_a");
        Frame68k frame = new Frame68k(lv, game);
        Player p = new Player(lv);
        int steps = args.length > 1 ? Integer.parseInt(args[1]) : 40;
        for (int i = 0; i < steps; i++) {
            p.move(0, 24);
        }
        frame.render(p.camera.zone, p.camera.x, p.camera.z, p.camera.yoff,
                     p.camera.angle);
        EngineState st = frame.state();

        java.util.Map<Integer, Integer> tally = new java.util.TreeMap<>();
        for (int row = 0; row < EngineState.VIEW_ROWS; row++) {
            int base = st.rowStart(row);
            for (int col = 0; col < EngineState.VIEW_COLUMNS; col++) {
                int c = st.screen[base + EngineState.columnWord(col)] & 0x0fff;
                tally.merge(c, 1, Integer::sum);
            }
        }
        System.out.printf("at (%d,%d) zone %d: %d walls, %d floors%n",
                          p.camera.x, p.camera.z, p.camera.zone,
                          frame.wallsDrawn, frame.surfacesDrawn);
        System.out.println("the ten commonest colours on screen:");
        tally.entrySet().stream()
             .sorted((a, b) -> b.getValue() - a.getValue())
             .limit(10)
             .forEach(e -> {
                 int c = e.getKey();
                 System.out.printf("  $%03x  r%2d g%2d b%2d  %5d pixels%s%n", c,
                                   (c >> 8) & 0xf, (c >> 4) & 0xf, c & 0xf,
                                   e.getValue(),
                                   ((c >> 8) & 0xf) > 8 && ((c >> 4) & 0xf) < 4
                                           && (c & 0xf) < 4 ? "   <-- red" : "");
             });
    }
}
