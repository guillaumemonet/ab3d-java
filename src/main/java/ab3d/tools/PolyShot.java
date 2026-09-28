package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * A picture of a model, from a place it can actually be seen.
 *
 * {@link PolyDrawCheck} counts pixels, which says the renderer runs but nothing
 * about whether what it draws is right. This stands in the middle of the zone a
 * model is in, turns to face it, and writes the frame out -- the only way to see
 * whether a key indicator is the right shape and the right colour.
 */
public final class PolyShot {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String name = args.length > 0 ? args[0] : "level_a";
        int wantSlot = args.length > 1 ? Integer.parseInt(args[1]) : -1;

        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);

        for (GameObject o : lv.objects) {
            if (o.isSprite() || o.pointIndex < 0
                    || o.pointIndex >= lv.objectPointX.length) {
                continue;
            }
            if (wantSlot >= 0 && o.slot != wantSlot) {
                continue;
            }
            int ox = lv.objectPointX[o.pointIndex];
            int oz = lv.objectPointZ[o.pointIndex];

            // back off along each of eight directions and keep the view that
            // shows the most of it
            int bestPixels = -1;
            BufferedImage best = null;
            int bestAt = 0;
            for (int dir = 0; dir < 8; dir++) {
                int ang = dir * 1024;
                int back = 300;
                int vx = ox - (int) (Math.sin(ang * Math.PI / 4096) * back);
                int vz = oz - (int) (Math.cos(ang * Math.PI / 4096) * back);
                int before = frame.poly.pixelsWritten;
                frame.render(o.zone, vx, vz, (o.height << 7) - 128, ang);
                int drew = frame.poly.pixelsWritten - before;
                if (drew > bestPixels) {
                    bestPixels = drew;
                    bestAt = dir;
                    best = picture(frame);
                }
            }
            String file = String.format("build/poly-%s-%d-slot%d.png",
                                        name, o.index, o.slot);
            javax.imageio.ImageIO.write(best, "png", new File(file));
            System.out.printf("  object %3d slot %d (%-9s) best from dir %d: "
                              + "%5d pixels -> %s%n",
                              o.index, o.slot,
                              o.slot < ab3d.data.VectorModel.SLOTS.length
                                  ? ab3d.data.VectorModel.SLOTS[o.slot] : "?",
                              bestAt, bestPixels, file);
        }
    }

    /** The view buffer as a picture, the 12-bit colours opened out to 24. */
    private static BufferedImage picture(Frame68k frame) {
        short[] screen = frame.state().screen;
        int w = EngineState.VIEW_COLUMNS;
        int h = EngineState.VIEW_ROWS;
        BufferedImage img = new BufferedImage(w * 4, h * 4,
                                              BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = y * EngineState.ROW_WORDS
                       + (ab3d.data.BuiltTables.columnByte(x) / 2);
                int c = at >= 0 && at < screen.length ? screen[at] & 0xfff : 0;
                int rgb = ((c >> 8 & 0xf) * 17 << 16)
                        | ((c >> 4 & 0xf) * 17 << 8)
                        | ((c & 0xf) * 17);
                for (int dy = 0; dy < 4; dy++) {
                    for (int dx = 0; dx < 4; dx++) {
                        img.setRGB(x * 4 + dx, y * 4 + dy, rgb);
                    }
                }
            }
        }
        return img;
    }
}
