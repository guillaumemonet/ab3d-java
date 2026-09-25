package ab3d.tools;

import ab3d.data.FloorLine;
import ab3d.data.GameData;
import ab3d.data.Level;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the level from above: solid walls in white, portals in blue, the player
 * start as a red dot. A top-down plot is the quickest way to tell a correctly
 * decoded map from a plausible-looking pile of numbers.
 */
public final class MapTool {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String name = args.length > 0 ? args[0] : "lev1";
        File out = new File(args.length > 1 ? args[1] : "build/" + name + "-map.png");
        out.getParentFile().mkdirs();

        Level lv = Level.load(game, name);

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (FloorLine fl : lv.floorLines) {
            minX = Math.min(minX, Math.min(fl.x1, fl.x2()));
            maxX = Math.max(maxX, Math.max(fl.x1, fl.x2()));
            minZ = Math.min(minZ, Math.min(fl.z1, fl.z2()));
            maxZ = Math.max(maxZ, Math.max(fl.z1, fl.z2()));
        }
        System.out.printf("extent x %d..%d  z %d..%d%n", minX, maxX, minZ, maxZ);

        int pad = 20;
        double scale = 1400.0 / Math.max(maxX - minX, maxZ - minZ);
        int w = (int) ((maxX - minX) * scale) + pad * 2;
        int h = (int) ((maxZ - minZ) * scale) + pad * 2;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(12, 12, 18));
        g.fillRect(0, 0, w, h);
        g.setStroke(new BasicStroke(1.4f));

        for (FloorLine fl : lv.floorLines) {
            g.setColor(fl.isSolid() ? new Color(230, 230, 235) : new Color(60, 110, 200));
            g.drawLine(px(fl.x1, minX, scale, pad), px(fl.z1, minZ, scale, pad),
                       px(fl.x2(), minX, scale, pad), px(fl.z2(), minZ, scale, pad));
        }

        g.setColor(new Color(230, 60, 60));
        int sx = px(lv.startX, minX, scale, pad), sz = px(lv.startZ, minZ, scale, pad);
        g.fillOval(sx - 5, sz - 5, 11, 11);
        g.dispose();

        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out + " (" + w + "x" + h + ")");
    }

    private static int px(int v, int min, double scale, int pad) {
        return (int) ((v - min) * scale) + pad;
    }
}
