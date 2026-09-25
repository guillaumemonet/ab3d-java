package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;

/**
 * A ProTracker module, as {@code mt_init} in source/jg.s reads one.
 *
 * Three of them are built into the game -- {@code ingame}, {@code welldone} and
 * {@code gameover} -- and all three carry the {@code M.K.} mark at offset one
 * thousand and eighty, so they are the four-channel thirty-one-sample kind.
 *
 * {@code mt_init} works out two things the file does not state. How many
 * patterns there are, by walking the hundred and twenty-eight entries of the
 * order list and taking the highest plus one; and where each sample's bytes
 * begin, by adding up the lengths from the start of the pattern data. Both are
 * done here the same way.
 */
public final class Module {

    public static final int SAMPLES = 31, CHANNELS = 4, ROWS = 64;
    /** {@code add.l #$3b8,a1}: the order list. */
    private static final int ORDER = 0x3b8;
    /** {@code add.l #$43c,d2}: where the patterns start. */
    private static final int PATTERNS = 0x43c;
    /**
     * The sample headers: twenty bytes of song name, then thirty-one of thirty.
     *
     * Within one, the twenty-two-byte name comes first and the numbers after it,
     * so the length is at twenty-two and not at the start.
     */
    private static final int HEADERS = 20, HEADER_SIZE = 30, NAME = 22;

    /** One of the thirty-one samples. Lengths and offsets are in bytes. */
    public record Sample(int start, int length, int loopStart, int loopLength,
                         int volume, int fineTune) {
        public boolean loops() {
            return loopLength > 2;
        }
    }

    private final byte[] data;
    private final Sample[] samples = new Sample[SAMPLES];
    private final int songLength;
    private final int[] order = new int[128];
    private final int patternCount;

    public static Module load(GameData game, String name) throws IOException {
        return new Module(game.bytes(name));
    }

    public Module(byte[] data) {
        this.data = data;
        this.songLength = u8(ORDER - 2);
        for (int i = 0; i < order.length; i++) {
            order[i] = u8(ORDER + i);
        }

        // mt_loop: the highest number in the order list, plus one
        int high = 0;
        for (int i = 0; i < 128; i++) {
            high = Math.max(high, order[i]);
        }
        this.patternCount = high + 1;

        // mt_lop3: each sample's bytes follow the patterns, in order
        int at = PATTERNS + patternCount * ROWS * CHANNELS * 4;
        for (int i = 0; i < SAMPLES; i++) {
            int h = HEADERS + i * HEADER_SIZE + NAME;
            int len = u16(h) * 2;
            int fine = u8(h + 2) & 0xf;
            int vol = u8(h + 3);
            int loopAt = u16(h + 4) * 2;
            int loopLen = u16(h + 6) * 2;
            samples[i] = new Sample(at, len, loopAt, loopLen, vol,
                                    fine > 7 ? fine - 16 : fine);
            at += len;
        }
    }

    private int u8(int at) {
        return at >= 0 && at < data.length ? data[at] & 0xff : 0;
    }

    private int u16(int at) {
        return (u8(at) << 8) | u8(at + 1);
    }

    public int songLength() {
        return Math.max(1, Math.min(128, songLength));
    }

    public int order(int position) {
        return position >= 0 && position < order.length ? order[position] : 0;
    }

    public int patternCount() {
        return patternCount;
    }

    public Sample sample(int i) {
        return i >= 0 && i < SAMPLES ? samples[i] : samples[0];
    }

    /** The four bytes of one channel's cell, as one longword. */
    public int cell(int pattern, int row, int channel) {
        int at = PATTERNS + (pattern * ROWS * CHANNELS + row * CHANNELS + channel) * 4;
        return (u8(at) << 24) | (u8(at + 1) << 16) | (u8(at + 2) << 8) | u8(at + 3);
    }

    /** The sample's own bytes, signed eight-bit. */
    public byte[] pcm() {
        return data;
    }

    /** {@code M.K.} at one thousand and eighty: is this the kind we can play? */
    public boolean isProTracker() {
        return u8(1080) == 'M' && u8(1081) == '.' && u8(1082) == 'K'
               && u8(1083) == '.';
    }
}
