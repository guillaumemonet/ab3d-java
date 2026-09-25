package ab3d.tools;

import ab3d.data.Border;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;
import ab3d.game.Hud;
import ab3d.game.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * The whole screen as the window shows it: borders, view and status panel.
 *
 * Worth having separately from the frame dumps, because the three pieces come
 * from three different places in the original -- hardware sprites, the bitmap,
 * and a picture loaded off the disk -- and only fit together at one set of
 * dimensions. If any of them is out by a pixel it shows here and nowhere else.
 */
public final class ScreenCheck {

    private static final int W = 320, VIEW_H = 160, PANEL_H = 96;
    private static final int VIEW_LEFT = 64, VIEW_W = 192;

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "level_a";
        int angle = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int keys = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int energy = args.length > 3 ? Integer.parseInt(args[3]) : Border.ENERGY_MAX;
        int ammo = args.length > 4 ? Integer.parseInt(args[4]) : Border.AMMO_MAX;

        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        Player p = new Player(lv);
        p.camera.angle = angle;
        frame.render(p.camera.zone, p.camera.x, p.camera.z, p.camera.yoff,
                     p.camera.angle);

        Hud hud = new Hud(game);
        hud.setKeys(keys);
        hud.setBars(energy, ammo);

        BufferedImage img = new BufferedImage(W, VIEW_H + PANEL_H,
                                              BufferedImage.TYPE_INT_RGB);
        EngineState st = frame.state();
        for (int y = 0; y < VIEW_H; y++) {
            int row = y * EngineState.VIEW_ROWS / VIEW_H;
            int base = st.rowStart(row);
            for (int x = 0; x < VIEW_W; x++) {
                int col = x * EngineState.VIEW_COLUMNS / VIEW_W;
                int c = st.screen[base + EngineState.columnWord(col)] & 0x0fff;
                int r = (c >> 8) & 0xf, g = (c >> 4) & 0xf, b = c & 0xf;
                img.setRGB(VIEW_LEFT + x, y, (r * 17) << 16 | (g * 17) << 8 | (b * 17));
            }
        }
        for (int pair = 0; pair < 2; pair++) {
            byte[] px = hud.border.pixels(pair);
            int x0 = pair == 0 ? 0 : VIEW_LEFT + VIEW_W;
            for (int y = 0; y < VIEW_H; y++) {
                for (int x = 0; x < Border.WIDTH; x++) {
                    img.setRGB(x0 + x, y,
                               hud.border.palette[px[y * Border.WIDTH + x] & 0xf]);
                }
            }
        }
        byte[] px = hud.pixels();
        int[] pal = hud.palette();
        for (int y = 0; y < PANEL_H; y++) {
            for (int x = 0; x < W; x++) {
                img.setRGB(x, VIEW_H + y, pal[px[y * Hud.WIDTH + x] & 0xff]);
            }
        }
        File out = new File("build/screen.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.printf("%s angle %d: %d zones, %d walls, %d floors -> %s%n",
                          name, angle, frame.zonesDrawn, frame.wallsDrawn,
                          frame.surfacesDrawn, out);
    }
}
