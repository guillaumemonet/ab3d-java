package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;

/**
 * The title picture, from includes/titlescrnraw.
 *
 * {@code titlecop} in source/titlecop.s sets {@code bplcon0} to {@code $7201},
 * which is seven bitplanes, and {@code INCBIN}s the palette straight after. The
 * file's length settles the rest: forty bytes a plane row times seven planes
 * times two hundred and fifty-six rows is exactly its seventy-one thousand six
 * hundred and eighty bytes, so the picture is three hundred and twenty across, a
 * full PAL screen deep, in a hundred and twenty-eight colours.
 *
 * Unlike the status panel's, this palette is neither copper writes nor
 * twelve-bit words. {@code PUTIN32}, which the fade calls to push it into the
 * copper, reads it with {@code move.l (a0)+} and takes the high word as red and
 * the two bytes of the low word as green and blue -- a longword of true colour
 * per entry, which the fade multiplies down and then squeezes into the two
 * twelve-bit halves AGA wants. A hundred and twenty-eight entries of four bytes
 * is the file's whole five hundred and twelve, and a hundred and twenty-eight is
 * what seven planes address. And unlike the panel, the planes are not
 * interleaved: {@code SETUPTITLESCRN} walks the bitplane pointers with
 * {@code add.l #10240,d0}, so each plane is one contiguous block of forty bytes
 * by two hundred and fifty-six rows and they follow one another.
 *
 * The screen after the game is a second file, {@code titlescrnraw1}, which
 * {@code LOADTITLESCRN2} puts up to carry the level statistics.
 */
public final class TitleScreen {

    public static final int WIDTH = 320, HEIGHT = 256, PLANES = 7;
    /** {@code add.l #10240,d0}: the gap between one plane and the next. */
    public static final int PLANE_BYTES = WIDTH / 8 * HEIGHT;
    public static final int SIZE = PLANE_BYTES * PLANES;

    public final byte[] pixels;
    public final int[] palette;

    private TitleScreen(byte[] planes, int[] palette) {
        this.palette = palette;
        this.pixels = new byte[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int p = 0; p < PLANES; p++) {
                int at = p * PLANE_BYTES + y * (WIDTH / 8);
                for (int b = 0; b < WIDTH / 8; b++) {
                    int bits = planes[at + b] & 0xff;
                    if (bits == 0) {
                        continue;
                    }
                    for (int i = 0; i < 8; i++) {
                        if ((bits & (0x80 >> i)) != 0) {
                            pixels[y * WIDTH + b * 8 + i] |= (byte) (1 << p);
                        }
                    }
                }
            }
        }
    }

    public static TitleScreen load(GameData game) throws IOException {
        return load(game, "titlescrnraw");
    }

    public static TitleScreen load(GameData game, String name) throws IOException {
        byte[] raw = Files.readAllBytes(game.include(name));
        if (SbDepacker.isPacked(raw)) {
            raw = SbDepacker.unpack(raw);
        }
        if (raw.length < SIZE) {
            byte[] grown = new byte[SIZE];
            System.arraycopy(raw, 0, grown, 0, raw.length);
            raw = grown;
        }
        return new TitleScreen(raw, readPalette(
                Files.readAllBytes(game.include("titlescrnpal"))));
    }

    /** A longword a colour: red in the high word, green and blue below it. */
    public static int[] readPalette(byte[] file) {
        int[] out = new int[256];
        for (int i = 0; i < out.length && i * 4 + 3 < file.length; i++) {
            int at = i * 4;
            out[i] = ((file[at + 1] & 0xff) << 16)     // swap d3: the high word
                   | ((file[at + 2] & 0xff) << 8)      // lsr.w #8,d4
                   | (file[at + 3] & 0xff);            // move.b d4,d5
        }
        return out;
    }

    /**
     * {@code FADEUPTITLE}: the palette scaled by a value that climbs by four.
     *
     * {@code PUTIN32} multiplies each gun by it and shifts down eight, so the
     * picture arrives from black over sixteen steps of the fade counter.
     */
    public static int[] faded(int[] palette, int amount) {
        int[] out = new int[palette.length];
        for (int i = 0; i < palette.length; i++) {
            int c = palette[i];
            int r = (((c >> 16) & 0xff) * amount) >> 8;
            int g = (((c >> 8) & 0xff) * amount) >> 8;
            int b = ((c & 0xff) * amount) >> 8;
            out[i] = Math.min(r, 255) << 16 | Math.min(g, 255) << 8 | Math.min(b, 255);
        }
        return out;
    }
}
