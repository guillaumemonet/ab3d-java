package ab3d.tools;

import ab3d.data.Border;
import ab3d.data.GameData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** The two screen borders side by side, to judge the plane and colour order. */
public final class BorderCheck {

    public static void main(String[] args) throws Exception {
        Border b = Border.load(GameData.fromSystemProperty());
        b.setEnergy(args.length > 0 ? Integer.parseInt(args[0]) : Border.ENERGY_MAX);
        b.setAmmo(args.length > 1 ? Integer.parseInt(args[1]) : Border.AMMO_MAX);
        BufferedImage img = new BufferedImage(Border.WIDTH * 2 + 8, Border.LINES,
                                              BufferedImage.TYPE_INT_RGB);
        for (int pair = 0; pair < 2; pair++) {
            byte[] px = b.pixels(pair);
            for (int y = 0; y < Border.LINES; y++) {
                for (int x = 0; x < Border.WIDTH; x++) {
                    img.setRGB(pair * (Border.WIDTH + 8) + x, y,
                               b.palette[px[y * Border.WIDTH + x] & 0xf]);
                }
            }
        }
        File out = new File("build/borders.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }
}
