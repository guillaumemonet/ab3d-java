package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;
import ab3d.engine.Frame68k;

/**
 * Do the models actually reach the screen?
 *
 * {@link VectorCheck} says the files come apart; this says the renderer turns
 * them into pixels. It walks each level, stands at every model in turn and looks
 * straight at it from a short way off, and counts what came out.
 *
 * A model that draws nothing from point-blank range is either culled the wrong
 * way round, projected off the screen, or filled with an empty span -- so the
 * number that matters is how many of the hundred and thirty-nine put anything
 * down at all.
 */
public final class PolyDrawCheck {

    private static final int BACK_OFF = 150;
    /** About eye height above the model's own height, in the engine's units. */
    private static final int EYE = 96 << 7;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        int total = 0;
        int drew = 0;
        java.util.Map<Integer, int[]> bySlot = new java.util.TreeMap<>();

        for (String name : game.diskLevels()) {
            Level lv = Level.load(game, name);
            Frame68k frame = new Frame68k(lv, game);
            if (frame.poly == null) {
                System.out.println("no model renderer: no models in this build");
                return;
            }
            int here = 0;
            int shown = 0;
            for (GameObject o : lv.objects) {
                if (o.isSprite() || o.pointIndex < 0
                        || o.pointIndex >= lv.objectPointX.length) {
                    continue;
                }
                total++;
                here++;
                int before = frame.poly.pixelsWritten;
                // stand off on each of the four sides and look back at it.
                // The view angle is out of 4096 (0 looks along +z, 1024 along
                // +x); this once turned by 2048 from a point off to +x, so it
                // never faced the model -- and passed only because models were
                // then drawn in the middle of the view wherever they stood.
                boolean any = false;
                int[][] sides = {{BACK_OFF, 0, 3072}, {-BACK_OFF, 0, 1024},
                                 {0, BACK_OFF, 2048}, {0, -BACK_OFF, 0}};
                for (int[] sd : sides) {
                    frame.render(o.zone,
                                 lv.objectPointX[o.pointIndex] + sd[0],
                                 lv.objectPointZ[o.pointIndex] + sd[1],
                                 (o.height << 7) - EYE, sd[2]);
                    if (frame.poly.pixelsWritten > before) {
                        any = true;
                        break;
                    }
                }
                if (any) {
                    drew++;
                    shown++;
                }
                int[] tally = bySlot.computeIfAbsent(o.slot, k -> new int[2]);
                tally[0]++;
                if (any) {
                    tally[1]++;
                }
            }
            if (here > 0) {
                System.out.printf("  %-8s %2d models, %2d drew something%n",
                                  name, here, shown);
            }
        }

        System.out.println();
        bySlot.forEach((slot, t) -> System.out.printf(
                "  slot %d %-10s %3d placed, %3d drew%n",
                slot,
                slot < ab3d.data.VectorModel.SLOTS.length
                        ? ab3d.data.VectorModel.SLOTS[slot] : "?",
                t[0], t[1]));
        System.out.printf("%n%d of %d models put pixels on the screen%n",
                          drew, total);
    }
}
