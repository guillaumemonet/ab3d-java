package ab3d.data;

import java.io.IOException;

/**
 * The {@code walltiles} table: wall texture index to {@code .wad} file.
 *
 * Indices 0 to 5 are the table source/master.s declares, and every AB3D1 variant
 * in the repository declares the same six. The shipped levels reference 0 to 13
 * though, so they were built against a longer table that is not in the sources;
 * the shipped executable is self-decompressing, so it cannot be read out of
 * there either.
 *
 * The rest is pinned down as far as the data allows. Every wall names a texture
 * height and a strip, and that strip has to exist inside the file, so the
 * deepest strip an index ever asks for rules out any wad too small to hold it
 * ({@code TextureMatchTool} does this sum). That check clears all six of
 * master.s's assignments, which is good evidence the shipped table kept them,
 * and it leaves only {@code dirt} and {@code shinymetal} for indices 10 and 12,
 * and only {@code switches} plausibly for 11.
 *
 * Indices 6, 8, 9, 13 and 14 have several candidates each and are a judgement
 * call. Correct them here if a wall looks wrong; nothing else depends on it.
 */
public final class WallTextures {

    /** Y units per texture row: 256 Y units per level unit, 2 level units per texel. */
    public static final int Y_PER_TEXEL = 512;

    private static final String[] FILES = {
        "greenmechanic.wad",   // 0  - from source/master.s
        "bluegreymetal.wad",   // 1  - from source/master.s
        "technodetail.wad",    // 2  - from source/master.s
        "bluestone.wad",       // 3  - from source/master.s
        "redalert.wad",        // 4  - from source/master.s
        "rock.wad",            // 5  - from source/master.s
        "bluemechanic.wad",    // 6  - one of several that fit
        "stairfronts.wad",     // 7  - unused by every shipped level
        "redrock.wad",         // 8  - one of several that fit
        "scummy.wad",          // 9  - one of several that fit
        "dirt.wad",            // 10 - only dirt or shinymetal fit
        "switches.wad",        // 11 - the only plausible fit at 32 texels tall
        "shinymetal.wad",      // 12 - only dirt or shinymetal fit
        "bigdoor.wad",         // 13 - one of several that fit
        "jackietest.wad",      // 14 - unused by every shipped level
    };

    private final WadTexture[] textures = new WadTexture[FILES.length];

    public WallTextures(GameData game) {
        for (int i = 0; i < FILES.length; i++) {
            try {
                textures[i] = WadTexture.load(game, FILES[i]);
            } catch (IOException e) {
                textures[i] = null;   // missing file: the renderer falls back to flat colour
            }
        }
    }

    /** The texture for a wall record's index, or null when it is not known. */
    public WadTexture get(int index) {
        return index >= 0 && index < textures.length ? textures[index] : null;
    }

    public int count() {
        return textures.length;
    }

    public String fileName(int index) {
        return index >= 0 && index < FILES.length ? FILES[index] : "?";
    }
}
