package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;

/**
 * The engine's sine table (newinclude/bigsine).
 *
 * 8192 signed words scaled to 32768 = 1.0. A quarter turn is 1024 entries, so a
 * full turn is 4096; the table is twice that length purely so cosine can be read
 * as {@code sin(angle + 1024)} without wrapping.
 */
public final class SineTable {

    public static final int QUARTER = 1024;
    public static final int FULL = 4096;
    /** Value of 1.0 in the table. */
    public static final int ONE = 32768;

    private final short[] table;

    private SineTable(short[] table) {
        this.table = table;
    }

    public static SineTable load(GameData game) throws IOException {
        byte[] raw = game.bytes("bigsine");
        short[] t = new short[raw.length / 2];
        for (int i = 0; i < t.length; i++) {
            t[i] = (short) (((raw[i * 2] & 0xff) << 8) | (raw[i * 2 + 1] & 0xff));
        }
        return new SineTable(t);
    }

    public int sin(int angle) {
        return table[angle & (FULL - 1)];
    }

    public int cos(int angle) {
        return table[((angle & (FULL - 1)) + QUARTER)];
    }

    /**
     * The same, for an angle held the way the level data and the object records
     * hold it.
     *
     * The original indexes the table by a byte offset -- {@code move.w (a1,d0.w),d1}
     * against a table of words -- so its angles run to 8192 for a full turn and
     * are always even. {@code Facing}, {@code angpos} and everything
     * {@code and.w #8190} touches is in those units, and feeding one of them to
     * {@link #sin} instead turns the thing through twice the angle. That is a
     * mistake with no symptom at all until something has to face a particular
     * way, so the two are named apart here.
     */
    public int sinByte(int byteAngle) {
        return sin(byteAngle >> 1);
    }

    public int cosByte(int byteAngle) {
        return cos(byteAngle >> 1);
    }
}
