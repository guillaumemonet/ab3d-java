package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The floor and ceiling texture sheet.
 *
 * Unlike walls, floors do not use the {@code .wad} textures. The {@code FloorLine}
 * routine in source/master.s sets {@code a0 = floortile + whichtile}, so every
 * surface reads from one global sheet ({@code includes/floortile}, 65536 bytes)
 * and the {@code whichtile} field of a surface record is an offset into it.
 *
 * The sheet is byte-interleaved four ways. Each phase is a plane 256 texels wide
 * and 64 tall holding four 64 x 64 tiles side by side, so a texel is at
 * {@code whichtile + (v * 256 + u) * 4}. That layout is what the surface records
 * themselves point to: across a level the {@code whichtile} values are a phase
 * of 0 to 3 plus a multiple of 256 bytes, and 256 bytes is exactly 64 texels --
 * one tile width.
 *
 * Colours come from {@code newinclude/floorpalscaled}: 7680 bytes, which is 15
 * brightness levels of 256 twelve-bit RGB words. Row 0 is the brightest and
 * mean luminance falls off monotonically to row 14; the sheet uses the whole
 * byte range, which is what settles the row width at 256 rather than 128. As
 * with the wall textures, the engine shades a surface by choosing a row rather
 * than by touching the indices.
 */
public final class FloorTexture {

    /** Texels across one tile. */
    public static final int TILE = 64;
    /** Texels across one interleaved plane. */
    public static final int PLANE_WIDTH = 256;
    /** Bytes between neighbouring texels of the same plane. */
    public static final int INTERLEAVE = 4;
    public static final int SHADES = 15;
    public static final int COLOURS = 256;

    private final byte[] tile;
    private final byte[] palette;

    private FloorTexture(byte[] tile, byte[] palette) {
        this.tile = tile;
        this.palette = palette;
    }

    public static FloorTexture load(GameData game) throws IOException {
        // Prefer the sheet off the original disk: the copy in the source tree is
        // a different revision.
        Path tile = game.disk() == null ? null : game.disk().resolve("disk1/includes/floortile");
        if (tile == null || !Files.isRegularFile(tile)) {
            tile = game.include("floortile");
        }
        if (!Files.isRegularFile(tile)) {
            throw new IOException("Missing floortile under " + game.root());
        }
        return new FloorTexture(SbDepacker.unpack(Files.readAllBytes(tile)),
                                SbDepacker.unpack(game.bytes("floorpalscaled")));
    }

    /** Index at tile position (u, v), for a surface's {@code whichtile} offset. */
    public int index(int u, int v, int whichTile) {
        int off = whichTile + (((v & (TILE - 1)) * PLANE_WIDTH) + (u & (TILE - 1))) * INTERLEAVE;
        return tile[off & (tile.length - 1)] & 0xff;
    }

    /** 12-bit RGB for an index at a brightness row (0 is the brightest). */
    public int colour(int shade, int index) {
        int s = shade < 0 ? 0 : Math.min(shade, SHADES - 1);
        int off = (s * COLOURS + (index & 0xff)) * 2;
        if (off + 1 >= palette.length) {
            return 0;
        }
        return (((palette[off] & 0xff) << 8) | (palette[off + 1] & 0xff)) & 0x0fff;
    }
}
