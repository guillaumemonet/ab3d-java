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

    private static final int BACK_OFF = 400;

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
                // look at it from four angles, in case one has it edge-on
                boolean any = false;
                for (int turn = 0; turn < 4 && !any; turn++) {
                    frame.render(o.zone,
                                 lv.objectPointX[o.pointIndex] + BACK_OFF,
                                 lv.objectPointZ[o.pointIndex],
                                 o.height << 7, turn * 2048);
                    any = frame.poly.pixelsWritten > before;
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
