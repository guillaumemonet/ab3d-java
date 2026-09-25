package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.MenuData;
import ab3d.data.OptFont;
import ab3d.data.TitleScreen;
import ab3d.game.OptionScreen;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** An option screen over the title picture, which is where it is really seen. */
public final class MenuCheck {

    /** {@code OPTCOP}: the text colour, and the two the highlight brings. */
    private static final int[] COLOURS = {0, 0xeeeedd, 0x220000, 0x7777aa};

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        MenuData menu = MenuData.load(game);
        System.out.println("MENUDATA holds " + menu.screens.size() + " screens:");
        for (MenuData.Screen s : menu.screens) {
            System.out.printf("  %-24s %d selectable%n", s.name(), s.options().size());
        }

        int which = args.length > 0 ? Integer.parseInt(args[0]) : MenuData.ONE_PLAYER;
        int selected = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        MenuData.Screen screen = menu.screens.get(which);
        System.out.println();
        for (char[] row : screen.text()) {
            String line = new String(row);
            if (!line.isBlank()) {
                System.out.println("  |" + line + "|");
            }
        }

        OptionScreen opt = new OptionScreen(OptFont.load(game));
        opt.draw(screen, selected);

        TitleScreen t = TitleScreen.load(game);
        BufferedImage img = new BufferedImage(TitleScreen.WIDTH, TitleScreen.HEIGHT,
                                              BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < TitleScreen.HEIGHT; y++) {
            for (int x = 0; x < TitleScreen.WIDTH; x++) {
                int c = t.palette[t.pixels[y * TitleScreen.WIDTH + x] & 0xff];
                int over = opt.pixels[y * OptionScreen.WIDTH + x] & 3;
                img.setRGB(x, y, over == 0 ? c : COLOURS[over]);
            }
        }
        File out = new File("build/menu.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("\nwrote " + out);
    }
}
