package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;

/**
 * The option-screen font, from includes/OptFont.
 *
 * {@code DRAWOPTSCRN} indexes it with {@code lea (a0,d2.w*8),a5} and copies
 * eight bytes, so a glyph is eight bytes -- eight rows of eight pixels, one
 * plane -- and the character's own code is the index. Two thousand and
 * forty-eight bytes is two hundred and fifty-six of them.
 */
public final class OptFont {

    public static final int GLYPH = 8;

    private final byte[] data;

    private OptFont(byte[] data) {
        this.data = data;
    }

    public static OptFont load(GameData game) throws IOException {
        return new OptFont(game.bytes("OptFont"));
    }

    /** One row of one glyph, as eight bits from the left. */
    public int row(char c, int y) {
        int at = (c & 0xff) * GLYPH + y;
        return at >= 0 && at < data.length ? data[at] & 0xff : 0;
    }
}
