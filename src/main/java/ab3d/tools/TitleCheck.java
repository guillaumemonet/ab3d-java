package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.TitleScreen;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Writes the title screen out, so the plane count and palette can be judged. */
public final class TitleCheck {

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "titlescrnraw";
        TitleScreen t = TitleScreen.load(GameData.fromSystemProperty(), name);
        BufferedImage img = new BufferedImage(TitleScreen.WIDTH, TitleScreen.HEIGHT,
                                              BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < TitleScreen.HEIGHT; y++) {
            for (int x = 0; x < TitleScreen.WIDTH; x++) {
                img.setRGB(x, y,
                        t.palette[t.pixels[y * TitleScreen.WIDTH + x] & 0xff]);
            }
        }
        File out = new File("build/" + name + ".png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }
}
