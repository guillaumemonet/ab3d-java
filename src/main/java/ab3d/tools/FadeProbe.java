package ab3d.tools;

import ab3d.data.GameData;
import ab3d.game.Shell;

/**
 * What the fade actually does to one bright pixel, step by step.
 *
 * {@code FADEUPTITLE} counts sixty-four times by four, and {@code PUTIN32}
 * multiplies each gun by the counter and shifts down eight -- so the picture
 * should arrive from black rather than from anything else, and should reach
 * very nearly its own colours at the end.
 */
public final class FadeProbe {

    public static void main(String[] args) throws Exception {
        Shell shell = new Shell(GameData.fromSystemProperty());
        byte[] screen = new byte[320 * 256 * 4];
        int at = (120 * 320 + 250) * 4;          // somewhere on the marine's helmet
        for (int tick = 0; tick <= 64; tick++) {
            if (tick % 8 == 0 || tick == 64) {
                shell.draw(screen, 320 * 4);
                System.out.printf("tick %2d  state %-5s  rgb %3d,%3d,%3d%n", tick,
                                  shell.state, screen[at] & 0xff,
                                  screen[at + 1] & 0xff, screen[at + 2] & 0xff);
            }
            shell.tick();
        }
    }
}
