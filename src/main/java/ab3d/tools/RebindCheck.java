package ab3d.tools;

import ab3d.data.Controls;
import ab3d.data.GameData;
import ab3d.game.KeyMap;
import ab3d.game.Shell;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Does rebinding a control change both the screen and the binding?
 *
 * {@code CHANGECONTROLS} writes the new code into {@code CONTROLBUFFER} and the
 * name onto the line, and the game reads the buffer. Those are two different
 * places, so the check is that one action moves both -- and that the key the
 * game then answers to is the new one, which needs the crossing from jME's key
 * codes to the Amiga's to be right as well.
 */
public final class RebindCheck {

    private static final int W = 320, H = 256;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Shell shell = new Shell(game);
        while (shell.state == Shell.State.TITLE) {
            shell.tick();
        }
        shell.show("CONTROL_TXT");

        Controls c = shell.controls;
        System.out.printf("before: FIRE is $%02x '%s'%n",
                          c.key(Controls.Action.FIRE),
                          c.name(c.key(Controls.Action.FIRE)).trim());

        // choose the FIRE line and press the left control key
        shell.selected = Controls.Action.FIRE.ordinal();
        boolean waiting = shell.chooseControl();
        System.out.println("waiting for a key: " + waiting);

        BufferedImage img = new BufferedImage(W * 2 + 8, H,
                                              BufferedImage.TYPE_INT_RGB);
        byte[] screen = new byte[W * H * 4];
        shot(img, shell, screen, 0);                // the line blanked

        int raw = KeyMap.raw(com.jme3.input.KeyInput.KEY_LCONTROL);
        shell.bindKey(raw);
        shot(img, shell, screen, 1);                // and its new name

        System.out.printf("after:  FIRE is $%02x '%s'%n",
                          c.key(Controls.Action.FIRE),
                          c.name(c.key(Controls.Action.FIRE)).trim());

        // and the game's own test: is that key now the fire key?
        KeyMap keys = new KeyMap();
        keys.set(com.jme3.input.KeyInput.KEY_LCONTROL, true);
        System.out.println("left control now fires: "
                           + keys.down(c.key(Controls.Action.FIRE)));
        keys.set(com.jme3.input.KeyInput.KEY_LCONTROL, false);
        keys.set(com.jme3.input.KeyInput.KEY_RMENU, true);
        System.out.println("the old key still fires: "
                           + keys.down(c.key(Controls.Action.FIRE)));

        // every default should survive the crossing to jME and back
        int kept = 0;
        for (Controls.Action a : Controls.Action.values()) {
            KeyMap k = new KeyMap();
            int want = shell.controls.key(a);
            for (int code = 0; code < 256; code++) {
                if (KeyMap.raw(code) == want) {
                    k.set(code, true);
                    break;
                }
            }
            if (k.down(want)) {
                kept++;
            } else {
                System.out.printf("  %s ($%02x '%s') has no key on this keyboard%n",
                                  a.label, want, shell.controls.name(want).trim());
            }
        }
        System.out.printf("%d of %d bindings reachable from a real key%n",
                          kept, Controls.Action.values().length);

        File out = new File("build/rebind.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }

    private static void shot(BufferedImage img, Shell shell, byte[] screen, int n) {
        java.util.Arrays.fill(screen, (byte) 0);
        shell.draw(screen, W * 4);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int at = y * W * 4 + x * 4;
                img.setRGB(n * (W + 8) + x, y,
                           (screen[at] & 0xff) << 16 | (screen[at + 1] & 0xff) << 8
                           | (screen[at + 2] & 0xff));
            }
        }
    }
}
