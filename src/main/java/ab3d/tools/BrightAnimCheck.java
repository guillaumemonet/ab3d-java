package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.engine.BrightAnim;

/**
 * Does the brightness resolution change what those forty-two zones get?
 *
 * Reading the word as a number gives a value in the hundreds wherever an
 * animation byte is set, which saturates the shade and blackens the floor. This
 * shows the raw value against the resolved one, and how far the animation
 * swings over a second's worth of frames.
 */
public final class BrightAnimCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_a");
        BrightAnim anim = new BrightAnim();

        int animated = 0, worstRaw = 0;
        for (Zone z : lv.zones) {
            if (((z.brightness >> 8) & 0xff) != 0) {
                animated++;
                worstRaw = Math.max(worstRaw, z.brightness);
            }
        }
        System.out.printf("%s: %d zones carry an animation byte, worst raw word %d%n",
                          lv.name, animated, worstRaw);

        int[] lo = new int[4], hi = new int[4];
        java.util.Arrays.fill(lo, 999);
        java.util.Arrays.fill(hi, -999);
        for (int f = 0; f < 60; f++) {
            anim.tick();
            for (int k = 1; k <= 3; k++) {
                lo[k] = Math.min(lo[k], anim.value(k));
                hi[k] = Math.max(hi[k], anim.value(k));
            }
        }
        String[] names = {"", "pulse", "flicker", "fire flicker"};
        for (int k = 1; k <= 3; k++) {
            System.out.printf("  %-13s swings %d..%d over sixty frames%n",
                              names[k], lo[k], hi[k]);
        }

        for (Zone z : lv.zones) {
            int kind = (z.brightness >> 8) & 0xff;
            if (kind != 0) {
                System.out.printf("  zone %3d: raw %5d -> resolved %3d "
                                + "(animation %d)%n",
                                z.index, z.brightness, anim.resolve(z.brightness), kind);
                break;
            }
        }
    }
}
