package ab3d.tools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Prints one column of several renders side by side, to see which layer wrote what. */
public final class ColumnProbe {

    public static void main(String[] args) throws Exception {
        int column = Integer.parseInt(args[0]);
        BufferedImage[] imgs = new BufferedImage[args.length - 1];
        for (int i = 1; i < args.length; i++) {
            imgs[i - 1] = ImageIO.read(new File(args[i]));
        }
        System.out.printf("column %d of %s%n", column, String.join(" | ",
                java.util.Arrays.stream(args).skip(1)
                        .map(a -> new File(a).getParentFile().getName()).toList()));
        int h = imgs[0].getHeight();
        for (int y = 0; y < h; y += Math.max(1, h / 40)) {
            StringBuilder sb = new StringBuilder(String.format("  y=%3d ", y));
            for (BufferedImage img : imgs) {
                sb.append(String.format(" %06x", img.getRGB(column, y) & 0xffffff));
            }
            System.out.println(sb);
        }
    }
}
