package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;

/**
 * How much work the two animations left undone would actually do.
 *
 * Lifts are zones whose floor moves, laid out like doors; the brightness
 * animation is selected by the high byte of a brightness word, and where that
 * byte is zero the value is used as it stands.
 */
public final class LiftCheck {

    /** A zone word is animated only when it is positive and carries a byte. */
    private static boolean animated(int word) {
        return word >= 0 && ((word >> 8) & 0xff) != 0;
    }

    /** A point word is tested on its low byte, then on the animation byte. */
    private static boolean animatedPoint(int word) {
        return (byte) word >= 0 && ((word >> 8) & 0xff) != 0;
    }

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : args.length > 0 ? args
                : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            BinReader g = lv.graphics.data;

            int at = lv.graphics.liftDataOffset;
            int lifts = 0;
            while (g.inRange(at, 18) && lifts < 64) {
                if (g.s16(at) == 999) {
                    break;
                }
                lifts++;
                int p = at + 18;
                while (g.inRange(p, 10) && g.s16(p) >= 0) {
                    p += 10;
                }
                at = p + 2;
            }

            int animZones = 0;
            for (Zone z : lv.zones) {
                // tst.w d2 / blt justbright comes first: a negative word is
                // taken as it stands, whatever its high byte looks like
                if (animated(z.brightness) || animated(z.upperBrightness)) {
                    animZones++;
                }
            }
            int animPoints = 0;
            int ptr = lv.ptrPoints + 4 + lv.numPoints * 4;
            for (int i = 0; i < lv.numPoints && lv.data.inRange(ptr + i * 4, 4); i++) {
                // tst.b d2 / blt: for a point it is the low byte that is tested
                if (animatedPoint(lv.data.s16(ptr + i * 4))
                        || animatedPoint(lv.data.s16(ptr + i * 4 + 2))) {
                    animPoints++;
                }
            }

            System.out.printf("%-8s lifts %2d | zones with an animated brightness %d "
                            + "of %d | points with one %d of %d%n",
                            name, lifts, animZones, lv.zones.length,
                            animPoints, lv.numPoints);
        }
    }
}
