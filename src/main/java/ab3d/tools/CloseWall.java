package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;
import ab3d.game.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * What the view does as the player walks into a wall.
 *
 * Standing against a surface is the case every projection handles worst: the
 * depth goes to nothing and whatever divides by it goes with it. The strip shows
 * the same wall from further and further in, so the frame the picture breaks on
 * can be named rather than guessed at.
 */
public final class CloseWall {

    private static final int W = 192, H = 80;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_a");
        Frame68k frame = new Frame68k(lv, game);
        Player p = new Player(lv);
        int angle = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        p.camera.angle = angle;

        int shots = 8;
        BufferedImage img = new BufferedImage(W * shots + 4 * (shots - 1), H,
                                              BufferedImage.TYPE_INT_RGB);
        System.out.printf("walking into a wall at angle %d from (%d,%d)%n",
                          angle, p.camera.x, p.camera.z);
        for (int shot = 0; shot < shots; shot++) {
            // walk forward until the mover stops making progress
            for (int step = 0; step < 10; step++) {
                p.move(Math.round((float) (Math.sin(angle / 4096.0 * 2 * Math.PI) * 24)),
                       Math.round((float) (Math.cos(angle / 4096.0 * 2 * Math.PI) * 24)));
            }
            frame.render(p.camera.zone, p.camera.x, p.camera.z, p.camera.yoff,
                         p.camera.angle);
            EngineState st = frame.state();
            int black = 0;
            for (int y = 0; y < H; y++) {
                int base = st.rowStart(y * EngineState.VIEW_ROWS / H);
                for (int x = 0; x < W; x++) {
                    int col = x * EngineState.VIEW_COLUMNS / W;
                    int c = st.screen[base + EngineState.columnWord(col)] & 0x0fff;
                    if (c == 0) {
                        black++;
                    }
                    int r = (c >> 8) & 0xf, g = (c >> 4) & 0xf, b = c & 0xf;
                    img.setRGB(shot * (W + 4) + x, y,
                               (r * 17) << 16 | (g * 17) << 8 | (b * 17));
                }
            }
            System.out.printf("  %d: at (%5d,%5d) zone %3d -- %d walls, %d floors, "
                              + "%d of %d pixels black%n", shot, p.camera.x,
                              p.camera.z, p.camera.zone, frame.wallsDrawn,
                              frame.surfacesDrawn, black, W * H);
            if (shot == shots - 1) {
                System.out.println("     why things were not drawn:");
                frame.rejects.forEach((why, n) ->
                        System.out.printf("       %-28s %d%n", why, n));
            }
        }
        File out = new File("build/closewall.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }
}
