package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.WadTexture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dumps every wall texture to PNG: the strip pool laid out side by side, plus
 * the 32 x 32 shade palette underneath it. Seeing the strips is the only honest
 * way to confirm the wad layout was read correctly.
 */
public final class TextureTool {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        int height = args.length > 0 ? Integer.parseInt(args[0]) : 64;
        File outDir = new File(args.length > 1 ? args[1] : "build/textures");
        int scale = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        outDir.mkdirs();

        Path walls = game.root().resolve("includes/walls");
        List<Path> wads = new ArrayList<>();
        try (var s = Files.list(walls)) {
            s.filter(p -> p.getFileName().toString().endsWith(".wad")).sorted().forEach(wads::add);
        }

        System.out.printf("%-24s %8s %8s %7s%n", "texture", "bytes", "strips", "height");
        for (Path p : wads) {
            WadTexture tex = new WadTexture(p.getFileName().toString(), BinReader.of(p));
            int strips = tex.stripCount(height);
            System.out.printf("%-24s %8d %8d %7d%n", tex.name, tex.size(), strips, height);
            if (strips <= 0) {
                continue;
            }
            File out = new File(outDir, tex.name.replace(".wad", "") + ".png");
            ImageIO.write(render(tex, strips, height, scale), "png", out);
        }
        System.out.println("\nwrote " + wads.size() + " PNGs to " + outDir);
    }

    private static BufferedImage render(WadTexture tex, int strips, int height, int scale) {
        int gap = 4;
        int w = Math.max(strips, WadTexture.COLOURS);
        int h = height + gap + WadTexture.SHADES;
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);

        // The strip pool at full brightness
        for (int x = 0; x < strips; x++) {
            for (int y = 0; y < height; y++) {
                put(img, x, y, scale, rgb(tex.colour(0, tex.texel(x, y, height, 0))));
            }
        }
        // Every shade row of the palette, so the ramp is visible too
        for (int s = 0; s < WadTexture.SHADES; s++) {
            for (int c = 0; c < WadTexture.COLOURS; c++) {
                put(img, c, height + gap + s, scale, rgb(tex.colour(s, c)));
            }
        }
        return img;
    }

    private static void put(BufferedImage img, int x, int y, int scale, int rgb) {
        for (int dy = 0; dy < scale; dy++) {
            for (int dx = 0; dx < scale; dx++) {
                int px = x * scale + dx, py = y * scale + dy;
                if (px < img.getWidth() && py < img.getHeight()) {
                    img.setRGB(px, py, rgb);
                }
            }
        }
    }

    /** 12-bit RGB to 24-bit, replicating each nibble as the engine does. */
    private static int rgb(int c) {
        int r = (c >> 8) & 0xf, g = (c >> 4) & 0xf, b = c & 0xf;
        return ((r | (r << 4)) << 16) | ((g | (g << 4)) << 8) | (b | (b << 4));
    }
}
