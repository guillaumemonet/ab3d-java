package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;

/**
 * Does looking behind give the same view as turning half a circle?
 *
 * {@code DrawDisplay} negates the sine and the cosine rather than adding 2048 to
 * the angle, which should be the same rotation. Comparing the two buffers pixel
 * for pixel says whether it is, and whether the weapon really is left out.
 */
public final class BehindCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_c");
        Frame68k f = new Frame68k(lv, game);
        EngineState st = f.state();
        int eye = lv.zone(lv.startZone).floorHeight - 12 * 1024;

        for (int a = 0; a < 4096; a += 1024) {
            f.lookBehind = false;
            f.render(lv.startZone, lv.startX, lv.startZ, eye, (a + 2048) & 4095);
            short[] turned = st.screen.clone();
            int gunTurned = f.gunPixels;

            f.lookBehind = true;
            f.render(lv.startZone, lv.startX, lv.startZ, eye, a);
            int differ = 0;
            for (int r = 0; r < EngineState.VIEW_ROWS; r++) {
                for (int c = 0; c < EngineState.VIEW_COLUMNS; c++) {
                    int at = st.rowStart(r) + EngineState.columnWord(c);
                    if (turned[at] != st.screen[at]) {
                        differ++;
                    }
                }
            }
            System.out.printf("  angle %4d: %d of %d pixels differ from a half turn"
                            + " | gun %d px turned, %d px looking behind%n",
                            a, differ, EngineState.VIEW_ROWS * EngineState.VIEW_COLUMNS,
                            gunTurned, f.gunPixels);
        }
    }
}
