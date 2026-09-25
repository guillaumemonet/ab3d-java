package ab3d.tools;

import ab3d.data.Border;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.TitleScreen;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;
import ab3d.game.Hud;
import ab3d.game.OptionScreen;
import ab3d.game.Player;
import ab3d.game.Shell;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** The pictures the README uses, rendered the way the window composes them. */
public final class Shots {

    private static final int W = 320, VIEW_H = 160, PANEL_H = 96;
    private static final int VIEW_LEFT = 64, VIEW_W = 192;
    private static final int SCALE = 2;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        new File("docs/images").mkdirs();

        // the title, and the menu over it
        Shell shell = new Shell(game);
        byte[] screen = new byte[W * (VIEW_H + PANEL_H) * 4];
        while (shell.state == Shell.State.TITLE) {
            shell.tick();
        }
        shell.selected = 1;
        java.util.Arrays.fill(screen, (byte) 0);
        shell.draw(screen, W * 4);
        write(screen, W, TitleScreen.HEIGHT, "docs/images/menu.png");

        // three views of the game
        shot(game, "level_a", 400, 3, "docs/images/level_a.png");
        shot(game, "level_c", 2100, 0, "docs/images/level_c.png");
        shot(game, "level_d", 1200, 5, "docs/images/level_d.png");
        System.out.println("wrote the README's pictures to docs/images");
    }

    private static void shot(GameData game, String name, int angle, int keys,
                             String to) throws Exception {
        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        Player p = new Player(lv);
        p.camera.angle = angle;
        frame.render(p.camera.zone, p.camera.x, p.camera.z, p.camera.yoff,
                     p.camera.angle);
        Hud hud = new Hud(game);
        hud.setKeys(keys);
        hud.setBars(Border.ENERGY_MAX, frame.gunData.shownAmmo(0));

        byte[] screen = new byte[W * (VIEW_H + PANEL_H) * 4];
        EngineState st = frame.state();
        int stride = W * 4;
        for (int y = 0; y < VIEW_H; y++) {
            int base = st.rowStart(y * EngineState.VIEW_ROWS / VIEW_H);
            for (int x = 0; x < VIEW_W; x++) {
                int col = x * EngineState.VIEW_COLUMNS / VIEW_W;
                int c = st.screen[base + EngineState.columnWord(col)] & 0x0fff;
                put(screen, y * stride + (VIEW_LEFT + x) * 4,
                    ((c >> 8) & 0xf) * 17, ((c >> 4) & 0xf) * 17, (c & 0xf) * 17);
            }
        }
        for (int pair = 0; pair < 2; pair++) {
            byte[] px = hud.border.pixels(pair);
            int x0 = pair == 0 ? 0 : VIEW_LEFT + VIEW_W;
            for (int y = 0; y < VIEW_H; y++) {
                for (int x = 0; x < Border.WIDTH; x++) {
                    int c = hud.border.palette[px[y * Border.WIDTH + x] & 0xf];
                    put(screen, y * stride + (x0 + x) * 4, c >> 16, c >> 8, c);
                }
            }
        }
        byte[] px = hud.pixels();
        int[] pal = hud.palette();
        for (int y = 0; y < PANEL_H; y++) {
            for (int x = 0; x < W; x++) {
                int c = pal[px[y * Hud.WIDTH + x] & 0xff];
                put(screen, (VIEW_H + y) * stride + x * 4, c >> 16, c >> 8, c);
            }
        }
        write(screen, W, VIEW_H + PANEL_H, to);
    }

    private static void put(byte[] out, int at, int r, int g, int b) {
        out[at] = (byte) r;
        out[at + 1] = (byte) g;
        out[at + 2] = (byte) b;
        out[at + 3] = (byte) 0xff;
    }

    private static void write(byte[] screen, int w, int h, String to)
            throws Exception {
        BufferedImage img = new BufferedImage(w * SCALE, h * SCALE,
                                              BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h * SCALE; y++) {
            for (int x = 0; x < w * SCALE; x++) {
                int at = (y / SCALE) * w * 4 + (x / SCALE) * 4;
                img.setRGB(x, y, (screen[at] & 0xff) << 16
                                 | (screen[at + 1] & 0xff) << 8
                                 | (screen[at + 2] & 0xff));
            }
        }
        ImageIO.write(img, "png", new File(to));
    }
}
