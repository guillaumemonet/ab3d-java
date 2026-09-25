package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;

/**
 * {@code waterfile} and {@code brightentab}, the two tables the water surface
 * uses.
 *
 * The water is not a texture in the way the floor is. {@code texturedwater}
 * reads a <em>word</em> from {@code waterfile} at the same packed coordinate the
 * floor reads a byte from, keeps only its high byte, and puts the low byte of
 * whatever is already on the screen underneath it in its place. The pair then
 * indexes {@code brightentab}, so the water tints what lies beneath rather than
 * covering it -- and since the screen byte is read a few rows away, the amount of
 * displacement is what makes it ripple.
 *
 * {@code brightentab} is 8192 bytes: sixteen levels of 256 entries. The depth
 * picks a base level, the water word's high byte adds to it, and the screen byte
 * indexes within it.
 */
public final class WaterTexture {

    /** Offsets {@code waterlist} cycles through, one per animation frame. */
    public static final int[] PHASES = {0, 2, 256, 258, 512, 514, 768, 770};
    /** Bytes per level of {@code brightentab}. */
    public static final int LEVEL_BYTES = 512;
    /** {@code cmp.w #12*512,d0}: how far the depth may shift the level. */
    public static final int MAX_DEPTH_SHIFT = 12 * LEVEL_BYTES;

    private final byte[] water;
    private final byte[] brighten;

    private WaterTexture(byte[] water, byte[] brighten) {
        this.water = water;
        this.brighten = brighten;
    }

    public static WaterTexture load(GameData game) throws IOException {
        return new WaterTexture(
                SbDepacker.unpack(Files.readAllBytes(game.root().resolve("includes/waterfile"))),
                SbDepacker.unpack(Files.readAllBytes(game.root().resolve("includes/brightenfile"))));
    }

    /** {@code move.w (a0,d5.w*4),d0}: the water word at a packed coordinate. */
    public int pattern(int packed, int phase) {
        int off = PHASES[Math.floorMod(phase, PHASES.length)] + (packed & 0x3f3f) * 4;
        if (off < 0 || off + 2 > water.length) {
            return 0;
        }
        return ((water[off] & 0xff) << 8) | (water[off + 1] & 0xff);
    }

    /** {@code move.w (a1,d0.w*2),(a3)}: the tint for a level and a screen byte. */
    public int tint(int depthShift, int patternWord, int screenByte) {
        int index = (depthShift + ((patternWord & 0xff00) | (screenByte & 0xff)) * 2);
        if (index < 0 || index + 2 > brighten.length) {
            return -1;
        }
        return ((brighten[index] & 0xff) << 8) | (brighten[index + 1] & 0xff);
    }

    /** How many of the table's entries are valid twelve-bit colours. */
    public int[] validity() {
        int ok = 0, bad = 0;
        for (int i = 0; i + 2 <= brighten.length; i += 2) {
            int v = ((brighten[i] & 0xff) << 8) | (brighten[i + 1] & 0xff);
            if ((v & 0xf000) == 0) {
                ok++;
            } else {
                bad++;
            }
        }
        return new int[]{ok, bad};
    }
}
