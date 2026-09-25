package ab3d.tools;

import ab3d.data.GameData;
import ab3d.game.Password;
import ab3d.game.Shell;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * The shell as the window shows it, at the four points worth looking at.
 *
 * The fade has no still frame of its own, and the password line is the only part
 * of the menu whose text the game writes rather than reads, so both are here
 * beside the two screens that are simply drawn.
 */
public final class ShellCheck {

    private static final int W = 320, H = 256;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Shell shell = new Shell(game);

        BufferedImage img = new BufferedImage(W * 4 + 24, H,
                                              BufferedImage.TYPE_INT_RGB);
        byte[] screen = new byte[W * H * 4];

        // the picture part way up
        for (int i = 0; i < 20; i++) {
            shell.tick();
        }
        shot(img, shell, screen, 0);

        // the menu, once it is up
        while (shell.state == Shell.State.TITLE) {
            shell.tick();
        }
        shell.selected = 1;
        shot(img, shell, screen, 1);

        // a password being typed in, one that names the third level
        String word = Password.encode(new Password.State(
                127, 2, 0, new int[]{0, 0, 0, 0, 0}));
        System.out.println("typing " + word + " (level 3)");
        shell.selected = 4;
        shell.startPassword();
        for (int i = 0; i < word.length() - 1; i++) {
            shell.type(word.charAt(i));
        }
        shot(img, shell, screen, 2);

        shell.type(word.charAt(word.length() - 1));
        System.out.println("accepted: " + shell.levelChanged
                           + ", level file now " + shell.levelFile());

        // and the credits
        shell.show("CREDITMENU_TXT");
        shot(img, shell, screen, 3);

        File out = new File("build/shell.png");
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
