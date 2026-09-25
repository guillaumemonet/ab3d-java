package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A wall texture file ({@code includes/walls/*.wad}).
 *
 * Two regions, split exactly where {@code itsawalldraw} splits them with its
 * {@code add.l #64*32,a3}:
 *
 * <pre>
 *   0      32 brightness levels x 32 colours, one 12-bit RGB word each (2048 bytes)
 *   2048   the strip pool: each strip is one texture column, one word per texel
 * </pre>
 *
 * Row 0 of the palette is the brightest and each following row is a dimmed copy,
 * which is how the engine shades a wall without touching the texels: the drawing
 * loop picks a row with {@code ffscrpickhowbright[brightness]} and indexes it
 * with the texel. A texel is the low 5 bits of its word ({@code and.b #31,d1}),
 * so only 32 colours are addressable.
 *
 * Columns are not stored one per texture column. The engine maps a wall's
 * horizontal coordinate through {@code divthreetab}, a generated table whose
 * entry {@code i} holds {@code i/3} in its high byte and {@code i mod 3} in its
 * low byte. The strip fetched is {@code ((u & uMask) + tile*16) / 3}, and the
 * remainder picks which of three pixels packed into each word to use:
 *
 * <pre>
 *   0   move.b 1(a5,d4.w*2),d1 / and.b #31     bits 0-4
 *   1   move.w  (a5,d4.w*2),d1 / lsr.w #5      bits 5-9
 *   2   move.b  (a5,d4.w*2),d1 / lsr.b #2      bits 10-14
 * </pre>
 *
 * Those are {@code drawwallPACK0}, {@code PACK1} and {@code PACK2}. Three
 * neighbouring wall columns share one stored strip, one word per texel, three
 * texels per word -- the same packing the sprites use.
 */
public final class WadTexture {

    /** Bytes of palette before the strip pool. */
    public static final int PALETTE_BYTES = 64 * 32;
    /** Brightness rows in the palette. */
    public static final int SHADES = 32;
    /** Colours addressable by a texel. */
    public static final int COLOURS = 32;

    public final String name;
    private final BinReader data;

    public WadTexture(String name, BinReader data) {
        this.name = name;
        this.data = data;
    }

    public static WadTexture load(GameData game, String fileName) throws IOException {
        Path p = game.root().resolve("includes/walls").resolve(fileName);
        if (!Files.isRegularFile(p)) {
            throw new IOException("No wall texture " + p);
        }
        return new WadTexture(fileName, BinReader.of(p));
    }

    /** 12-bit RGB for a texel at a given brightness row (0 = brightest). */
    public int colour(int shade, int texel) {
        int s = shade < 0 ? 0 : Math.min(shade, SHADES - 1);
        int t = texel & (COLOURS - 1);
        return data.u16((s * COLOURS + t) * 2) & 0x0fff;
    }

    /** Number of stored strips, given the texture height this wall uses. */
    public int stripCount(int height) {
        return Math.max(0, (data.size() - PALETTE_BYTES) / (height * 2));
    }

    /**
     * Texel at row {@code v} of stored strip {@code strip}.
     *
     * @param sub which of the three pixels packed into the word to take, the
     *            {@code mod 3} the divide-by-three table yields
     */
    public int texel(int strip, int v, int height, int sub) {
        int off = PALETTE_BYTES + (strip * height + (v & (height - 1))) * 2;
        if (!data.inRange(off, 2)) {
            return 0;
        }
        return (data.u16(off) >>> (5 * Math.floorMod(sub, 3))) & (COLOURS - 1);
    }

    /**
     * The strip a wall column maps to: {@code divthreetab} turns the wrapped
     * horizontal coordinate plus the tile offset into a strip index by dividing
     * by three.
     */
    public static int stripFor(int u, int uMask, int tile) {
        return ((u & uMask) + tile * 16) / 3;
    }

    /** Which of the word's three packed pixels a wall column uses. */
    public static int subPixelFor(int u, int uMask, int tile) {
        return ((u & uMask) + tile * 16) % 3;
    }

    public int size() {
        return data.size();
    }
}
