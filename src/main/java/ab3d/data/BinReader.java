package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Big-endian random access reader over a byte[].
 *
 * The original data was produced by a 68000 and is big-endian throughout.
 * All "read" methods take an absolute offset so the caller can follow the
 * pointer-chasing style of the assembly sources literally.
 */
public final class BinReader {

    private final byte[] data;

    public BinReader(byte[] data) {
        this.data = data;
    }

    /**
     * Reads a file, unpacking it when it carries the {@code =SB=} tag. Data off
     * the original floppies is compressed; the copies in the source tree are
     * not, so both work without the caller caring which it has.
     */
    public static BinReader of(Path file) throws IOException {
        return new BinReader(SbDepacker.unpack(Files.readAllBytes(file)));
    }

    public byte[] data() {
        return data;
    }

    public int size() {
        return data.length;
    }

    /** Unsigned byte. */
    public int u8(int off) {
        return data[off] & 0xff;
    }

    /** Signed byte. */
    public int s8(int off) {
        return data[off];
    }

    /** Signed 16-bit word, as the 68k {@code move.w} into a sign-extended register. */
    public int s16(int off) {
        return (short) (((data[off] & 0xff) << 8) | (data[off + 1] & 0xff));
    }

    /** Unsigned 16-bit word. */
    public int u16(int off) {
        return ((data[off] & 0xff) << 8) | (data[off + 1] & 0xff);
    }

    /** Signed 32-bit longword. */
    public int s32(int off) {
        return ((data[off] & 0xff) << 24)
             | ((data[off + 1] & 0xff) << 16)
             | ((data[off + 2] & 0xff) << 8)
             | (data[off + 3] & 0xff);
    }

    /**
     * Writes a word back into the buffer.
     *
     * The engine patches level data in place -- {@code DoorRoutine} rewrites a
     * zone's roof and a wall's bottom every frame -- so the same buffer the
     * renderer reads has to be writable rather than copied out first.
     */
    public void setS16(int off, int value) {
        if (off < 0 || off + 2 > data.length) {
            return;
        }
        data[off] = (byte) (value >> 8);
        data[off + 1] = (byte) value;
    }

    /** Writes a single byte back, for the flag bytes the object records hold. */
    public void setU8(int off, int value) {
        if (off < 0 || off >= data.length) {
            return;
        }
        data[off] = (byte) value;
    }

    /** Writes a longword back into the buffer. */
    public void setS32(int off, int value) {
        setS16(off, value >> 16);
        setS16(off + 2, value);
    }

    public boolean inRange(int off, int length) {
        return off >= 0 && length >= 0 && off + length <= data.length;
    }
}
