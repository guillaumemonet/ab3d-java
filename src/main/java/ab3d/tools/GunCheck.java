package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SpriteBank;
import ab3d.engine.EngineState;
import ab3d.engine.GunDraw;

/**
 * Draws every weapon at every step of its animation.
 *
 * Two things should hold: each step that the anim table names must put pixels on
 * the screen, and the two weapons the table leaves null must put none. A step
 * that draws nothing would be a frame index pointing past the sheet.
 */
public final class GunCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, "level_c");
        EngineState st = new EngineState(lv);
        GunDraw gun = new GunDraw(st, new SpriteBank(game), ab3d.data.GunAnims.load(game));

        for (int g = 0; g < 8; g++) {
            StringBuilder b = new StringBuilder();
            int empty = 0, steps = 0;
            for (int f = 0; f < 8; f++) {
                java.util.Arrays.fill(st.screen, (short) 0);
                gun.draw(g, f);
                if (gun.columnsDrawn == 0 && gun.blankColumns == 0) {
                    continue;                      // this weapon has no anim
                }
                steps++;
                b.append(String.format(" %d:%dpx/%dcol", f, gun.pixelsWritten,
                                       gun.columnsDrawn));
                if (gun.pixelsWritten == 0) {
                    empty++;
                }
            }
            System.out.printf("gun %d: %s%s%n", g,
                              steps == 0 ? "no animation (null in GunAnims)" : b,
                              empty > 0 ? "   <<< " + empty + " steps drew nothing" : "");
        }
    }
}
