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
 * Walks the player about a level and looks for frames that do not belong.
 *
 * A one-frame flicker is a frame that differs a great deal from the frame
 * before and the frame after while those two are close to each other: nothing
 * moved that much, so the odd one out was drawn wrong. A hole is a run of
 * view pixels left at nought where the frames around it drew something. Each
 * suspect is written out with its two neighbours, and the viewpoint printed so
 * it can be drawn again.
 *
 * Arguments: level, frames, output directory, seed.
 */
public final class FlickerHunt {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String name = args.length > 0 ? args[0] : "level_a";
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 1500;
        File out = new File(args.length > 2 ? args[2] : "build/flicker");
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 1;
        out.mkdirs();

        Level lv = Level.load(game, name);
        Frame68k f = new Frame68k(lv, game);
        SineTable sine = SineTable.load(game);
        Player p = new Player(lv);
        java.util.Random rnd = new java.util.Random(seed);
        EngineState st = f.state();
        int n = EngineState.VIEW_ROWS * EngineState.VIEW_COLUMNS;

        short[][] ring = new short[3][n];
        int[][] where = new int[3][];
        float acc = 0;
        boolean turnL = false, turnR = false;
        int turnLeft = 0, stuck = 0, suspects = 0, holes = 0;
        for (int t = 0; t < frames; t++) {
            // a wander: forward, and now and then a turn, longer when stuck
            if (turnLeft <= 0 && rnd.nextInt(40) == 0) {
                turnLeft = 10 + rnd.nextInt(30);
                turnL = rnd.nextBoolean();
                turnR = !turnL;
            }
            int x0 = p.camera.x, z0 = p.camera.z;
            acc += 50f / 60f;
            int ticks = 0;
            while (acc >= 1) {
                acc -= 1;
                ticks++;
                p.tick();
                p.controlTick(sine, true, false, turnLeft > 0 && turnL,
                              turnLeft > 0 && turnR, false, false, false, null);
            }
            p.applyMove();
            turnLeft--;
            if (p.camera.x == x0 && p.camera.z == z0 && ticks > 0) {
                if (++stuck > 10) {
                    turnLeft = 20 + rnd.nextInt(40);
                    stuck = 0;
                }
            } else {
                stuck = 0;
            }
            if (ticks > 0) {
                f.updateDoors(ticks, t % 30 == 0);
                f.updateLifts(ticks, p.camera.zone, t % 30 == 0);
                f.updateObjects(p.camera.zone, p.camera.x, p.camera.z,
                                p.camera.yoff, p.stoodInTop, ticks, p.camera.angle);
                f.armObjects(p.camera.zone);
            }
            f.render(p.camera.zone, p.camera.x, p.camera.z, p.camera.yoff,
                     p.camera.angle);

            short[] cur = ring[t % 3];
            int i = 0;
            for (int r = 0; r < EngineState.VIEW_ROWS; r++) {
                for (int c = 0; c < EngineState.VIEW_COLUMNS; c++) {
                    cur[i++] = st.screen[st.rowStart(r) + EngineState.columnWord(c)];
                }
            }
            where[t % 3] = new int[]{p.camera.x, p.camera.z, p.camera.angle,
                                     p.camera.zone, p.camera.yoff, t};
            if (t < 2) {
                continue;
            }
            short[] a = ring[(t - 2) % 3], b = ring[(t - 1) % 3];
            int ab = diff(a, b), bc = diff(b, cur), ac = diff(a, cur);
            int blackB = 0, blackBoth = 0;
            for (int k = 0; k < n; k++) {
                if (b[k] == 0) {
                    blackB++;
                    if (a[k] != 0 && cur[k] != 0) {
                        blackBoth++;
                    }
                }
            }
            boolean flicker = ab > n / 6 && bc > n / 6 && ac < n / 20;
            boolean hole = blackBoth > n / 10;
            if (flicker || hole) {
                int[] w = where[(t - 1) % 3];
                System.out.printf("%s frame %d: %s  differs %d/%d from its "
                        + "neighbours, they %d from each other; %d pixels black "
                        + "only here | -Dab3d.at=%d,%d,%d,%d%n",
                        name, w[5], flicker ? "FLICKER" : "HOLE   ", ab, bc, ac,
                        blackBoth, w[0], w[1], w[2], w[3]);
                if (suspects < 12) {
                    ImageIO.write(img(a), "png", new File(out, name + "_" + w[5] + "_a.png"));
                    ImageIO.write(img(b), "png", new File(out, name + "_" + w[5] + "_b.png"));
                    ImageIO.write(img(cur), "png", new File(out, name + "_" + w[5] + "_c.png"));
                }
                suspects++;
                if (hole) {
                    holes++;
                }
            }
        }
        System.out.printf("%s: %d frames, %d suspects (%d holes), ended at zone %d%n",
                          name, frames, suspects, holes, p.camera.zone);
    }

    private static int diff(short[] a, short[] b) {
        int d = 0;
        for (int k = 0; k < a.length; k++) {
            if (a[k] != b[k]) {
                d++;
            }
        }
        return d;
    }

    private static BufferedImage img(short[] px) {
        int w = EngineState.VIEW_COLUMNS, h = EngineState.VIEW_ROWS, sx = 6, sy = 3;
        BufferedImage im = new BufferedImage(w * sx, h * sy, BufferedImage.TYPE_INT_RGB);
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                int v = px[r * w + c] & 0xfff;
                int R = (v >> 8) & 15, G = (v >> 4) & 15, B = v & 15;
                int rgb = ((R | R << 4) << 16) | ((G | G << 4) << 8) | (B | B << 4);
                for (int y = 0; y < sy; y++) {
                    for (int x = 0; x < sx; x++) {
                        im.setRGB(c * sx + x, r * sy + y, rgb);
                    }
                }
            }
        }
        return im;
    }
}
