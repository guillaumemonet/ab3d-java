package ab3d.tools;

import ab3d.data.EndZones;
import ab3d.data.GameData;
import ab3d.game.Password;
import ab3d.game.Shell;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Does finishing a level hand back a password that opens the next one?
 *
 * The reward for a level is not a screen, it is a word. {@code CALCPASSWORD}
 * runs only when {@code FINISHEDLEVEL} is set, writes the result onto the menu's
 * own password line, and the player finds it waiting there when the fade lets
 * them back. So the claim to test is a round trip through the menu: win level
 * one, read what the line says, and decode it back to level two.
 *
 * Losing has to be the other way. A level lost leaves the line holding whatever
 * it held before, which is what stops dying from being a way to earn a password.
 */
public final class EndLevelCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        EndZones ends = EndZones.load(game);

        for (int level = 0; level < 4; level++) {
            Shell shell = new Shell(game);
            while (shell.state == Shell.State.TITLE) {
                shell.tick();
            }
            shell.setLevel(level);
            String before = passwordLine(shell);

            shell.endLevel(true, new Password.State(
                    100, level, 0, new int[]{160, 0, 0, 0, 0}));
            String after = passwordLine(shell);
            Password.State back = Password.decode(after);

            System.out.printf("level %d ends in zone %3d; won -> password %s%n",
                              level + 1, ends.of(level), after);
            System.out.printf("   which decodes to level %s, energy %s%s%n",
                              back == null ? "REJECTED" : "" + (back.level() + 1),
                              back == null ? "-" : "" + back.energy(),
                              back != null && back.level() == level + 1
                                      ? "" : "   <-- not the next level");

            // and it should take the fade to get back to the menu
            int frames = 0;
            while (shell.state == Shell.State.ENDED && frames < 400) {
                shell.tick();
                frames++;
            }
            System.out.printf("   back at the menu after %d frames, showing %s%n",
                              frames, shell.state);

            // losing must not write one
            Shell lost = new Shell(game);
            while (lost.state == Shell.State.TITLE) {
                lost.tick();
            }
            lost.setLevel(level);
            String was = passwordLine(lost);
            lost.endLevel(false, new Password.State(
                    0, level, 0, new int[]{0, 0, 0, 0, 0}));
            System.out.printf("   lost -> the line is %s%n%n",
                              passwordLine(lost).equals(was) ? "left alone"
                                      : "CHANGED, which it should not be");
            if (level == 0) {
                picture(shell, game);
            }
        }
    }

    /** {@code PASSWORDLINE}: row twenty-three, sixteen letters from column twelve. */
    private static String passwordLine(Shell shell) {
        return shell.passwordLine();
    }

    /** The screen the player sees between the level and the menu. */
    private static void picture(Shell shell, GameData game) throws Exception {
        Shell s = new Shell(game);
        while (s.state == Shell.State.TITLE) {
            s.tick();
        }
        s.endLevel(true, new Password.State(100, 0, 0, new int[]{160, 0, 0, 0, 0}));
        for (int i = 0; i < 40; i++) {
            s.tick();
        }
        byte[] screen = new byte[320 * 256 * 4];
        s.draw(screen, 320 * 4);
        BufferedImage img = new BufferedImage(320, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 320; x++) {
                int at = y * 320 * 4 + x * 4;
                img.setRGB(x, y, (screen[at] & 0xff) << 16
                                 | (screen[at + 1] & 0xff) << 8
                                 | (screen[at + 2] & 0xff));
            }
        }
        File out = new File("build/endlevel.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }
}
