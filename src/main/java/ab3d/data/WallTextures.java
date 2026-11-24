package ab3d.data;

import java.io.IOException;

/**
 * The {@code walltiles} table: wall texture index to {@code .wad} file.
 *
 * jg.s fills it at run time from {@code wallchunkdata} in source/wallchunk.s,
 * which it includes: fourteen file names, each with the size it unpacks to.
 * master.s and the other variants declare only the first six as
 * {@code INCBIN}s, and jg.s keeps those six commented out -- which is why this
 * table was once pinned down past index five by guesswork, from which files
 * were large enough for the strips the levels ask of them. Four of those
 * guesses were wrong (6, 8, 9 and 13 had scummy, bigdoor, redrock and
 * bluemechanic shuffled), and a texture of the wrong size drawn on a wall comes
 * out as a block of unrelated colours. {@code jackietest} is not in the list;
 * no level asks for a fifteenth.
 */
public final class WallTextures {

    /** Y units per texture row: 256 Y units per level unit, 2 level units per texel. */
    public static final int Y_PER_TEXEL = 512;

    /** {@code wallchunkdata}, in its own order. */
    private static final String[] FILES = {
        "greenmechanic.wad",   // 0   18560 bytes unpacked
        "bluegreymetal.wad",   // 1   13056
        "technodetail.wad",    // 2   13056
        "bluestone.wad",       // 3    4864
        "redalert.wad",        // 4    7552
        "rock.wad",            // 5   10368
        "scummy.wad",          // 6   13056
        "stairfronts.wad",     // 7    2400
        "bigdoor.wad",         // 8   13056
        "redrock.wad",         // 9   13056
        "dirt.wad",            // 10  24064
        "switches.wad",        // 11   3456
        "shinymetal.wad",      // 12  24064
        "bluemechanic.wad",    // 13  15744
    };

    /** The wall files this table names, for anything that has to count them. */
    public static String[] files() {
        return FILES.clone();
    }

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
