package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A sprite sheet: a {@code .wad} of pixel columns, a {@code .ptr} table naming
 * them, and a {@code .pal} of shade rows.
 *
 * The layout comes from the column loop of {@code BitMapObj} in
 * source/objdraw3.chipram:
 *
 * <pre>
 *   move.l (a5),d1            ; pointer table entry
 *   beq    blankstrip         ; a zero entry is an empty column
 *   and.l  #$ffffff,d1        ; low 24 bits: byte offset into the .wad
 *   add.l  d1,a0
 *   move.b (a5),d1            ; top byte: which of three draw variants to use
 * </pre>
 *
 * Those three variants are the crux of the format. Each reads the <em>same</em>
 * 16-bit word but a different five-bit field of it:
 *
 * <pre>
 *   variant 0   move.b 1(a0,d1.w*2),d0 / and.b #31    bits 0-4
 *   variant 1   move.w  (a0,d1.w*2),d0 / lsr.w #5     bits 5-9
 *   variant 2   move.b  (a0,d1.w*2),d0 / lsr.b #2     bits 10-14
 * </pre>
 *
 * So <b>three sprite columns share one stored column</b>, packed three pixels to
 * a word -- the same divide-by-three idea the wall routine uses through
 * {@code divthreetab}. Index 0 is transparent.
 *
 * A sprite occupies {@code width} consecutive entries of the pointer table,
 * starting at the byte offset its frame gives. Neither the width nor the height
 * is recorded here -- both live in the 64-byte object record, which is the only
 * place the engine keeps them (see {@link GameObject}).
 *
 * Palettes are 960 bytes: 15 shade rows of 32 twelve-bit RGB colours, the same
 * shape the wall textures use and the step the {@code objscalecols} table walks.
 */
public final class SpriteSheet {

    /** Colours a five-bit pixel can name. */
    public static final int COLOURS = 32;
    /** Shade rows in a sprite palette. */
    public static final int SHADES = 15;
    /** Index that means "leave the background alone". */
    public static final int TRANSPARENT = 0;

    public final String name;
    private final byte[] wad;
    private final byte[] ptr;
    private final byte[] palette;

    private SpriteSheet(String name, byte[] wad, byte[] ptr, byte[] palette) {
        this.name = name;
        this.wad = wad;
        this.ptr = ptr;
        this.palette = palette;
    }

    public static SpriteSheet load(GameData game, String name) throws IOException {
        Path wad = game.include(name + ".wad");
        Path ptr = game.include(name + ".ptr");
        if (!Files.isRegularFile(wad) || !Files.isRegularFile(ptr)) {
            throw new IOException("No sprite sheet '" + name + "' under " + game.root());
        }
        Path pal = game.include(name + ".pal");
        return new SpriteSheet(name,
                SbDepacker.unpack(Files.readAllBytes(wad)),
                SbDepacker.unpack(Files.readAllBytes(ptr)),
                Files.isRegularFile(pal) ? SbDepacker.unpack(Files.readAllBytes(pal)) : null);
    }

    /** Number of entries in the pointer table. */
    public int columnCount() {
        return ptr.length / 4;
    }

    /**
     * Pixel at row {@code y} of the column named by pointer entry {@code column},
     * or {@link #TRANSPARENT} where nothing should be drawn.
     */
    public int pixel(int column, int y) {
        int e = column * 4;
        if (e + 4 > ptr.length || y < 0) {
            return TRANSPARENT;
        }
        int entry = readInt(ptr, e);
        if (entry == 0) {
            return TRANSPARENT;          // blank column
        }
        int off = (entry & 0xffffff) + y * 2;
        if (off + 1 >= wad.length) {
            return TRANSPARENT;
        }
        int word = ((wad[off] & 0xff) << 8) | (wad[off + 1] & 0xff);
        int variant = (entry >>> 24) & 0xff;
        return (word >>> (5 * Math.min(variant, 2))) & 0x1f;
    }

    /**
     * Where the fetch for a row would land relative to the graphic: -1 before
     * it, 1 past it, 0 inside. The original does the read either way and takes
     * whatever is there; this says when that would have happened.
     */
    public int rowPlacement(int column, int y) {
        if (y < 0) {
            return -1;
        }
        int e = column * 4;
        if (e < 0 || e + 4 > ptr.length) {
            return 1;
        }
        int off = (readInt(ptr, e) & 0xffffff) + y * 2;
        return off + 1 >= wad.length ? 1 : 0;
    }

    /**
     * True where a column has no pointer-table entry at all, the
     * {@code move.l (a5),d1 / beq blankstrip} case. Distinct from a column
     * whose pixels are all transparent: this one is skipped without looking at
     * the graphic.
     */
    public boolean isBlank(int column) {
        int e = column * 4;
        return e < 0 || e + 4 > ptr.length || readInt(ptr, e) == 0;
    }

    /** Which of the three draw variants a column asks for. */
    public int variant(int column) {
        int e = column * 4;
        return e + 4 > ptr.length ? 0 : (readInt(ptr, e) >>> 24) & 0xff;
    }

    /** 12-bit RGB for a pixel at a brightness row, or -1 for transparent. */
    public int colour(int shade, int pixel) {
        if (pixel == TRANSPARENT || palette == null) {
            return -1;
        }
        int s = shade < 0 ? 0 : Math.min(shade, SHADES - 1);
        int off = (s * COLOURS + (pixel & (COLOURS - 1))) * 2;
        if (off + 1 >= palette.length) {
            return -1;
        }
        return (((palette[off] & 0xff) << 8) | (palette[off + 1] & 0xff)) & 0x0fff;
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
             | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }
}
