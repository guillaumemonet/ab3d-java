package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;
import ab3d.game.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Walks the camera forward the way the game does and reports what each step
 * draws, so a wall that vanishes on approach shows up as a step where the count
 * drops rather than as something only visible by eye.
 */
public final class WalkProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_c");
        int angle = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int steps = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        SineTable sine = SineTable.load(game);

        Frame68k f = new Frame68k(lv, game);
        EngineState st = f.state();
        Player p = new Player(lv);
        p.camera.angle = angle;

        double sin = p.camera.sin(sine) / 32768.0;
        double cos = p.camera.cos(sine) / 32768.0;

        boolean sweep = System.getProperty("sweep") != null;
        for (int i = 0; i < steps; i++) {
            if (sweep) {
                p.camera.angle = (angle + i * 64) & 4095;
            }
            f.render(p.camera.zone, p.camera.x, p.camera.z, p.camera.yoff,
                     p.camera.angle);
            int lit = 0;
            for (int r = 0; r < EngineState.VIEW_ROWS; r++) {
                for (int c = 0; c < EngineState.VIEW_COLUMNS; c++) {
                    if (st.screen[st.rowStart(r) + EngineState.columnWord(c)] != 0) {
                        lit++;
                    }
                }
            }
            System.out.printf("%s %4d  (%6d,%6d) zone %3d | walls %3d/%3d "
                            + "surfaces %3d/%3d | rows %5d | %5.1f%% lit%n",
                            sweep ? "angle" : "step ",
                            sweep ? p.camera.angle : i,
                            p.camera.x, p.camera.z, p.camera.zone,
                            f.wallsDrawn, f.wallsSeen, f.surfacesDrawn,
                            f.surfacesSeen, f.wallRows,
                            100.0 * lit / (EngineState.VIEW_ROWS * EngineState.VIEW_COLUMNS));
            if (i == steps - 1) {
                System.out.printf("   connect reads %d, out of range %d%n",
                                  f.connectReads, f.connectOutOfRange);
                System.out.println("   final rejects: " + f.rejects);
            }
            File dir = new File("build/walk");
            dir.mkdirs();
            BufferedImage img = new BufferedImage(EngineState.VIEW_COLUMNS * 8,
                                                  EngineState.VIEW_ROWS * 4,
                                                  BufferedImage.TYPE_INT_RGB);
            for (int r = 0; r < EngineState.VIEW_ROWS; r++) {
                for (int c = 0; c < EngineState.VIEW_COLUMNS; c++) {
                    int v = st.screen[st.rowStart(r) + EngineState.columnWord(c)] & 0x0fff;
                    int rr = (v >> 8) & 0xf, gg = (v >> 4) & 0xf, bb = v & 0xf;
                    int rgb = ((rr | (rr << 4)) << 16) | ((gg | (gg << 4)) << 8)
                            | (bb | (bb << 4));
                    for (int dy = 0; dy < 4; dy++) {
                        for (int dx = 0; dx < 8; dx++) {
                            img.setRGB(c * 8 + dx, r * 4 + dy, rgb);
                        }
                    }
                }
            }
            ImageIO.write(img, "png", new File(dir, String.format("s%02d.png", i)));

            if (!sweep) {
                p.move((int) Math.round(sin * 60), (int) Math.round(cos * 60));
            }
        }
    }
}
