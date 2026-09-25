package ab3d.game;

import ab3d.data.MenuData;
import ab3d.data.OptFont;

/**
 * One option screen drawn as the original draws it, into sprites over the title
 * picture.
 *
 * {@code DRAWOPTSCRN} walks forty characters by thirty-two lines and puts each
 * glyph's eight bytes into one plane of five sprites -- eight characters each,
 * which is a sprite's sixty-four pixels, and five of them across the three
 * hundred and twenty. {@code HIGHLIGHT} then does {@code not.b} over the chosen
 * line in the <em>other</em> plane, nine rows of it rather than eight, so the
 * selected option changes colour and carries a line under it.
 *
 * Here the two planes come out as a two-bit value a pixel: bit zero is the
 * glyph, bit one the highlight. What is left is which colours those are, and
 * they come from {@code OPTCOP} in source/titlecop.s, which sets three of them
 * per scanline and animates the first -- the ripple that runs down the title
 * screen's text.
 */
public final class OptionScreen {

    public static final int WIDTH = MenuData.COLUMNS * 8;
    public static final int HEIGHT = MenuData.ROWS * 8;
    /** {@code not.b 128(a2)}: the highlight is one row taller than the glyph. */
    private static final int HIGHLIGHT_ROWS = 9;

    private final OptFont font;
    /** Two bits a pixel: one for the glyph, one for the highlight. */
    public final byte[] pixels = new byte[WIDTH * HEIGHT];

    public OptionScreen(OptFont font) {
        this.font = font;
    }

    /** {@code DRAWOPTSCRN} then {@code HIGHLIGHT}. */
    public void draw(MenuData.Screen screen, int selected) {
        java.util.Arrays.fill(pixels, (byte) 0);
        for (int row = 0; row < MenuData.ROWS; row++) {
            for (int col = 0; col < MenuData.COLUMNS; col++) {
                char c = screen.text()[row][col];
                for (int y = 0; y < OptFont.GLYPH; y++) {
                    int bits = font.row(c, y);
                    if (bits == 0) {
                        continue;
                    }
                    int at = (row * 8 + y) * WIDTH + col * 8;
                    for (int x = 0; x < 8; x++) {
                        if ((bits & (0x80 >> x)) != 0) {
                            pixels[at + x] |= 1;
                        }
                    }
                }
            }
        }
        if (selected < 0 || selected >= screen.options().size()) {
            return;
        }
        MenuData.Option o = screen.options().get(selected);
        for (int y = 0; y < HIGHLIGHT_ROWS; y++) {
            int line = o.top() * 8 + y;
            if (line >= HEIGHT) {
                break;
            }
            int at = line * WIDTH + o.left() * 8;
            for (int x = 0; x < o.width() * 8 && o.left() * 8 + x < WIDTH; x++) {
                pixels[at + x] ^= 2;                 // not.b, in the other plane
            }
        }
    }
}
