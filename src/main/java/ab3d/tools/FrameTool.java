package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Renders frames with the transcribed chain and writes them as PNG. */
public final class FrameTool {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String name = args.length > 0 ? args[0] : "level_b";
        File out = new File(args.length > 1 ? args[1] : "build/frames");
        int scale = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        out.mkdirs();

        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        EngineState st = frame.state();

        int zone = lv.startZone;
        int x = lv.startX, z = lv.startZ;
        int eye = lv.zone(zone).floorHeight - 12 * 1024;

        System.out.printf("%s: start zone %d at (%d,%d), eye %d%n",
                          name, zone, x, z, eye);
        for (int a = 0; a < 4096; a += 512) {
            frame.render(zone, x, z, eye, a);
            int lit = 0;
            int blankGaps = 0;
            boolean[] colLit = new boolean[EngineState.VIEW_COLUMNS];
            for (int r = 0; r < EngineState.VIEW_ROWS; r++) {
                for (int c = 0; c < EngineState.VIEW_COLUMNS; c++) {
                    if (st.screen[st.rowStart(r) + EngineState.columnWord(c)] != 0) {
                        lit++;
                        colLit[c] = true;
                    }
                }
            }
            System.out.printf("  angle %4d: %2d zones (%2d clipped out), "
                            + "%3d walls %3d surfaces %2d sprites | "
                            + "rows %5d floor %5d sprite %5d | %4.1f%% of view lit%n",
                            a, frame.zonesDrawn, frame.zonesClippedOut,
                            frame.wallsDrawn, frame.surfacesDrawn, frame.spritesDrawn,
                            frame.wallRows, frame.floorPixels, frame.spritePixels,
                            100.0 * lit / (EngineState.VIEW_ROWS * EngineState.VIEW_COLUMNS));
            for (int c = 1; c + 1 < EngineState.VIEW_COLUMNS; c++) {
                if (!colLit[c] && colLit[c - 1] && colLit[c + 1]) {
                    blankGaps++;
                }
            }
            StringBuilder dark = new StringBuilder();
            for (int c = 0; c < EngineState.VIEW_COLUMNS; c++) {
                if (!colLit[c]) {
                    dark.append(' ').append(c)
                        .append(frame.columnCovered[c] ? "(covered)" : "(uncovered)");
                }
            }
            System.out.printf("      single blank columns between lit ones: %d%n",
                              blankGaps);
            if (dark.length() > 0) {
                System.out.println("      dark columns:" + dark);
            }
            int[] wr = frame.wallRowsPerColumn();
            StringBuilder holes = new StringBuilder();
            for (int c = 1; c + 1 < EngineState.VIEW_COLUMNS; c++) {
                if (wr[c] == 0 && wr[c - 1] > 0 && wr[c + 1] > 0) {
                    holes.append(' ').append(c)
                         .append(frame.columnCovered[c] ? "(covered)" : "(uncovered)");
                }
            }
            if (holes.length() > 0) {
                System.out.println("      wall gaps:" + holes);
            }
            System.out.printf("      seen: %d walls %d surfaces %d objects | %s%n",
                              frame.wallsSeen, frame.surfacesSeen, frame.objectsSeen,
                              frame.rejects);
            System.out.printf("      wall endpoints not rotated: %d of %d%n",
                              frame.pointsNotRotated, frame.pointsChecked);
            System.out.printf("      surface corners not rotated: %d of %d%n",
                              frame.cornersNotRotated, frame.cornersChecked);
            System.out.printf("      floor: %d black of %d, %d zero indices | tiles %s shades %s%n",
                              frame.floorBlack, frame.floorPixels, frame.floorIndexZero,
                              frame.floorTiles, frame.floorShades);
            ImageIO.write(toImage(st, scale), "png",
                          new File(out, name + "_" + a + ".png"));
        }
        System.out.println("wrote PNGs to " + out);
    }

    private static BufferedImage toImage(EngineState st, int scale) {
        // Two display pixels per engine column, as the 192-wide display does
        int sx = scale * 2;
        BufferedImage img = new BufferedImage(EngineState.VIEW_COLUMNS * sx,
                                              EngineState.VIEW_ROWS * scale,
                                              BufferedImage.TYPE_INT_RGB);
        for (int r = 0; r < EngineState.VIEW_ROWS; r++) {
            for (int c = 0; c < EngineState.VIEW_COLUMNS; c++) {
                int rgb = rgb(st.screen[st.rowStart(r) + EngineState.columnWord(c)] & 0x0fff);
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < sx; dx++) {
                        img.setRGB(c * sx + dx, r * scale + dy, rgb);
                    }
                }
            }
        }
        return img;
    }

    private static int rgb(int c) {
        int r = (c >> 8) & 0xf, g = (c >> 4) & 0xf, b = c & 0xf;
        return ((r | (r << 4)) << 16) | ((g | (g << 4)) << 8) | (b | (b << 4));
    }
}
