package ab3d.tools;

import ab3d.data.FloorTexture;
import ab3d.data.GameData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Dumps the floor sheet and its shade palette, to check indexing and ordering. */
public final class FloorTool {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        FloorTexture floor = FloorTexture.load(game);
        File dir = new File(args.length > 0 ? args[0] : "build/floor");
        dir.mkdirs();

        // The palette: one row per brightness level, 256 colours across
        BufferedImage pal = new BufferedImage(256 * 2, FloorTexture.SHADES * 8,
                BufferedImage.TYPE_INT_RGB);
        for (int s = 0; s < FloorTexture.SHADES; s++) {
            for (int c = 0; c < 256; c++) {
                int rgb = rgb(floor.colour(s, c));
                for (int dy = 0; dy < 8; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        pal.setRGB(c * 2 + dx, s * 8 + dy, rgb);
                    }
                }
            }
        }
        ImageIO.write(pal, "png", new File(dir, "floorpal.png"));

        // The sheet at the brightest and darkest rows
        for (int shade : new int[] { 0, FloorTexture.SHADES - 1 }) {
            BufferedImage sheet = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
            for (int v = 0; v < 256; v++) {
                for (int u = 0; u < 256; u++) {
                    sheet.setRGB(u, v, rgb(floor.colour(shade, floor.index(u, v, 0))));
                }
            }
            ImageIO.write(sheet, "png", new File(dir, "floortile-shade" + shade + ".png"));
        }
        System.out.println("wrote palette and sheet to " + dir);
    }

    private static int rgb(int c) {
        int r = (c >> 8) & 0xf, g = (c >> 4) & 0xf, b = c & 0xf;
        return ((r | (r << 4)) << 16) | ((g | (g << 4)) << 8) | (b | (b << 4));
    }
}
