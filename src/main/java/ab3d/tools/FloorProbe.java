package ab3d.tools;

import ab3d.data.GameData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

/** Renders candidate layouts of the floor sheet so the right one can be picked by eye. */
public final class FloorProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        File dir = new File("build/floorprobe");
        dir.mkdirs();

        byte[] repo = Files.readAllBytes(game.include("floortile"));
        byte[] disk = Files.readAllBytes(
                game.disk().resolve("disk1/includes/floortile"));

        for (var src : new Object[][] { { "repo", repo }, { "disk", disk } }) {
            String tag = (String) src[0];
            byte[] t = (byte[]) src[1];
            // Straight 256x256
            ImageIO.write(grey(t, 256, 256, 1, 0), "png", new File(dir, tag + "-linear256.png"));
            // Four byte-interleaved planes, each 256 wide and 64 tall,
            // holding four 64x64 tiles side by side
            for (int phase = 0; phase < 4; phase++) {
                ImageIO.write(grey(t, 256, 64, 4, phase), "png",
                        new File(dir, tag + "-plane" + phase + ".png"));
            }
            // Two interleaved planes of 128x256 / 256x128
            ImageIO.write(grey(t, 128, 256, 2, 0), "png", new File(dir, tag + "-stride2.png"));
            // 64-wide column-major style
            ImageIO.write(grey(t, 64, 1024, 1, 0), "png", new File(dir, tag + "-w64.png"));
        }
        System.out.println("wrote candidates to " + dir);
    }

    /** Greyscale so spatial coherence shows without any palette guesswork. */
    private static BufferedImage grey(byte[] t, int w, int h, int stride, int phase) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * stride + phase;
                int v = i < t.length ? t[i] & 0xff : 0;
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }
}
