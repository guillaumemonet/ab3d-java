package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Panel;
import ab3d.game.Hud;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Writes the status panel out as a PNG, so the decode can be looked at. */
public final class PanelCheck {

    public static void main(String[] args) throws Exception {
        Panel p = Panel.load(GameData.fromSystemProperty());

        int used = 0;
        boolean[] seen = new boolean[256];
        for (byte b : p.pixels) {
            if (!seen[b & 0xff]) {
                seen[b & 0xff] = true;
                used++;
            }
        }
        int set = 0;
        for (int c : p.palette) {
            if (c != 0) {
                set++;
            }
        }
        System.out.printf("panel %dx%d, %d colour indices used, %d palette entries non-black%n",
                          Panel.WIDTH, Panel.HEIGHT, used, set);

        // and again with all four keys in hand, which is what ItsAKey draws
        Hud hud = new Hud(GameData.fromSystemProperty());
        hud.setKeys(0xf);
        byte[] px = hud.pixels();
        int[] pal = hud.palette();
        BufferedImage img = new BufferedImage(Panel.WIDTH, Panel.HEIGHT,
                                              BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < Panel.HEIGHT; y++) {
            for (int x = 0; x < Panel.WIDTH; x++) {
                img.setRGB(x, y, pal[px[y * Panel.WIDTH + x] & 0xff]);
            }
        }
        File out = new File("build/panel.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }
}
