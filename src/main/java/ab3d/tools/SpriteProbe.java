package ab3d.tools;

import ab3d.data.GameData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renders a sprite the way the object drawer addresses one, so the layout can be
 * confirmed by eye.
 *
 * BitMapObj resolves a frame to a byte offset into the {@code .ptr} table and a
 * vertical step; the table then holds one entry per sprite column whose low word
 * is an offset into the {@code .wad}. Palettes are the same shape as the wall
 * ones: shade rows of 32 colours, 12-bit RGB.
 */
public final class SpriteProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String name = args.length > 0 ? args[0] : "lamps";
        int width = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        int height = args.length > 2 ? Integer.parseInt(args[2]) : 32;
        int first = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int scale = args.length > 4 ? Integer.parseInt(args[4]) : 4;

        byte[] wad = Files.readAllBytes(game.include(name + ".wad"));
        byte[] ptr = Files.readAllBytes(game.include(name + ".ptr"));
        Path palPath = game.include(name + ".pal");
        byte[] pal = Files.isRegularFile(palPath) ? Files.readAllBytes(palPath) : null;

        System.out.printf("%s: wad=%d ptr=%d pal=%s%n", name, wad.length, ptr.length,
                pal == null ? "none" : String.valueOf(pal.length));

        // Count the leading non-zero entries: those are this sheet's columns
        // A sprite is `width` consecutive entries of the pointer table, starting
        // where its frame says. The width comes from the object record, which is
        // the only place it is recorded.
        int columns = width;
        System.out.printf("  sprite %dx%d from ptr entry %d%n", width, height, first);

        File dir = new File("build/sprites");
        dir.mkdirs();
        // One word per pixel, the index in its low byte, five bits of it, and
        // zero meaning transparent -- that is what .drawavertstrip reads.
        for (int stride : new int[] { 2 }) {
            BufferedImage img = new BufferedImage(columns * scale, height * scale,
                    BufferedImage.TYPE_INT_RGB);
            for (int c = 0; c < columns; c++) {
                int e = (first + c) * 4;
                if (e + 4 > ptr.length) {
                    break;
                }
                int entry = readInt(ptr, e);
                int off = entry & 0xffffff;      // top byte selects the draw variant
                boolean blank = entry == 0;      // a zero entry is an empty column
                for (int y = 0; y < height; y++) {
                    int i = off + y * stride + 1;
                    int index = (!blank && i < wad.length) ? wad[i] & 0x1f : 0;
                    int rgb = index == 0 ? 0x006080 : (pal == null ? grey(index)
                            : colour(pal, 0, index));
                    for (int dy = 0; dy < scale; dy++) {
                        for (int dx = 0; dx < scale; dx++) {
                            img.setRGB(c * scale + dx, y * scale + dy, rgb);
                        }
                    }
                }
            }
            ImageIO.write(img, "png", new File(dir, name + "-stride" + stride + ".png"));
        }
        System.out.println("  wrote to " + dir);
    }

    private static int colour(byte[] pal, int shade, int index) {
        int off = (shade * 32 + (index & 31)) * 2;
        if (off + 1 >= pal.length) {
            return 0;
        }
        int c = (((pal[off] & 0xff) << 8) | (pal[off + 1] & 0xff)) & 0x0fff;
        int r = (c >> 8) & 0xf, g = (c >> 4) & 0xf, b = c & 0xf;
        return ((r | (r << 4)) << 16) | ((g | (g << 4)) << 8) | (b | (b << 4));
    }

    private static int grey(int v) {
        int g = v * 8;
        return (g << 16) | (g << 8) | g;
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
             | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }
}
