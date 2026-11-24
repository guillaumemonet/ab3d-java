package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The game executable on disk one, {@code abd.pk}, decrunched -- for the few
 * tables the Amiga build {@code INCBIN}'d and the source release holds only in a
 * different version.
 *
 * Comparing the decrunched executable with the source tree, byte for byte, finds
 * every model and nearly every table identical, but three are not:
 * <ul>
 *   <li>{@code Texturemaps}, the polygon-object textures. The release's copy is
 *       an older, noisier sheet: the block the key indicators use is a striped
 *       multicoloured smear there, a plain grey housing in the game.</li>
 *   <li>{@code brightentab}. {@code jg.s} names {@code brightenfile}, but what
 *       sits between {@code darkenedcols} and {@code WorkSpace} in the executable
 *       is the 11264-byte table the release calls {@code oldbrightenfile}: words
 *       that are twelve-bit colours, which is what {@code texturedwater} reads.
 *       The release's {@code brightenfile} is a byte table of a later build.</li>
 *   <li>{@code optcop}, the colours of the menu text: gold in the game, grey in
 *       the release.</li>
 * </ul>
 *
 * The executable is a hunk file whose last hunk carries a decruncher and the
 * other five hunks' contents packed together. {@link #decrunch} is that
 * decruncher, transcribed; the offsets below are where the tables fall in the
 * second hunk (the first after the stub), found by locating their neighbours --
 * {@code texturepalscaled} right after {@code TextureMaps}, {@code waterfile}
 * {@code 8192*4} bytes after {@code brightentab}, {@code optcop} right after
 * {@code OptFont}. The packed and unpacked lengths in the cruncher's header
 * identify the release; any other executable is left alone.
 */
public final class ShippedExe {

    /** {@code {hunk, offset, length}} of each table, by the name the port asks. */
    private static final Map<String, int[]> TABLES = Map.ofEntries(
            // the three the source release has in another version
            Map.entry("Texturemaps", new int[]{1, 0x2ddec, 65536}),
            Map.entry("brightenfile", new int[]{1, 0x78412, 11264}),
            Map.entry("optcop", new int[]{1, 0x4ef56, 512}),
            // and the rest of what it INCBIN'd, identical to the release's
            // copies but read from the player's disk all the same
            Map.entry("OptFont", new int[]{1, 0x4e756, 2048}),
            Map.entry("texturepalscaled", new int[]{1, 0x3ddec, 7680}),
            Map.entry("floorpalscaled", new int[]{1, 0x53420, 7680}),
            Map.entry("backfile", new int[]{1, 0x55a40, 32832}),
            Map.entry("bigsine", new int[]{1, 0x5da8c, 16384}),
            Map.entry("constantfile", new int[]{1, 0x6641a, 65528}),
            Map.entry("waterfile", new int[]{1, 0x83012, 65536}),
            Map.entry("newleftbord", new int[]{2, 0x00008, 5184}),
            Map.entry("newrightbord", new int[]{2, 0x01448, 5184}),
            Map.entry("healthstrip", new int[]{2, 0x02888, 512}),
            Map.entry("ammostrip", new int[]{2, 0x02a88, 512}),
            Map.entry("greenkey", new int[]{2, 0x02d28, 1056}),
            Map.entry("redkey", new int[]{2, 0x03148, 1056}),
            Map.entry("yellowkey", new int[]{2, 0x03568, 1056}),
            Map.entry("bluekey", new int[]{2, 0x03988, 1056}),
            Map.entry("titlescrnpal", new int[]{2, 0x04dd4, 512}),
            Map.entry("borderpal", new int[]{2, 0x08dc8, 136}),
            Map.entry("panelpal", new int[]{2, 0x08fb2, 2112}),
            Map.entry("gameover", new int[]{4, 0x03048, 30282}),
            Map.entry("welldone", new int[]{4, 0x0a692, 33028}));
    // "ingame" is not here because it is not in the game: jg.s has its INCBIN
    // commented out, so the label falls on gameover's. The port plays only the
    // two jingles.

    /** The cruncher header of the release the offsets were taken from. */
    private static final int UNPACKED = 0xb4586, PACKED = 0x3d276;

    private final byte[][] hunks;

    private ShippedExe(byte[][] hunks) {
        this.hunks = hunks;
    }

    /** Every table taken from the executable, sorted. */
    public static java.util.List<String> names() {
        return TABLES.keySet().stream().sorted().toList();
    }

    /** Whether this table is one taken from the executable. */
    public static boolean provides(String name) {
        return TABLES.containsKey(name);
    }

    /** The table's bytes. */
    public byte[] table(String name) {
        int[] t = TABLES.get(name);
        byte[] out = new byte[t[2]];
        System.arraycopy(hunks[t[0]], t[1], out, 0, t[2]);
        return out;
    }

    /** Any span of a decrunched hunk, for checking the layout. */
    public byte[] slice(int hunk, int at, int length) {
        byte[] out = new byte[length];
        System.arraycopy(hunks[hunk], at, out, 0, length);
        return out;
    }

    /**
     * Reads and decrunches the executable, or returns null when the file is not
     * the release this knows.
     */
    public static ShippedExe load(Path file) throws IOException {
        byte[] d = Files.readAllBytes(file);
        int[] sizes;
        int p = 0;
        if (be32(d, p) != 0x3f3) {
            return null;
        }
        p += 4;
        while (be32(d, p) != 0) {          // resident library names
            p += 4 + be32(d, p) * 4;
        }
        p += 4;
        int n = be32(d, p);
        int first = be32(d, p + 4), last = be32(d, p + 8);
        p += 12;
        if (n != 7 || first != 0 || last != 6) {
            return null;
        }
        sizes = new int[n];
        for (int i = 0; i < n; i++) {
            sizes[i] = (be32(d, p) & 0x3fffffff) * 4;
            p += 4;
        }
        // hunks one to five are empty in the file; the sixth carries it all.
        // This cruncher writes no HUNK_END, each HUNK_CODE starts the next.
        byte[] cruncher = null;
        for (int h = 0; h < n; h++) {
            int type = be32(d, p) & 0x3fffffff;
            int longs = be32(d, p + 4);
            p += 8;
            if (type != 0x3e9) {
                return null;
            }
            if (h == n - 1) {
                cruncher = new byte[sizes[h]];
                System.arraycopy(d, p, cruncher, 0, longs * 4);
            }
            p += longs * 4;
            if (p + 4 <= d.length && be32(d, p) == 0x3ec) {
                p = skipRelocs(d, p + 4);
            }
            if (p + 4 <= d.length && be32(d, p) == 0x3f2) {
                p += 4;
            }
        }
        if (cruncher == null) {
            return null;
        }
        // lea $156(pc),a2 : the header, then the packed stream
        int header = 0x156;
        if (be32(cruncher, header) != UNPACKED || be32(cruncher, header + 4) != PACKED) {
            return null;
        }
        int start = header + 8;
        decrunch(cruncher, start, UNPACKED, PACKED);
        // the copy loop: hunks one to five, one after the other, from a5
        byte[][] hunks = new byte[n][];
        int at = start;
        for (int h = 1; h < n - 1; h++) {
            hunks[h] = new byte[sizes[h]];
            int len = Math.min(sizes[h], UNPACKED - (at - start));
            System.arraycopy(cruncher, at, hunks[h], 0, Math.max(0, len));
            at += sizes[h];
        }
        return new ShippedExe(hunks);
    }

    private static int skipRelocs(byte[] d, int p) {
        while (true) {
            int count = be32(d, p);
            p += 4;
            if (count == 0) {
                return p;
            }
            p += 4 + count * 4;
        }
    }

    // ------------------------------------------------------------------
    // the decruncher, register for register

    private static final int[] MASK = {
        0, 0x0001, 0x0003, 0x0007, 0x000f, 0x001f, 0x003f, 0x007f, 0x00ff,
        0x01ff, 0x03ff, 0x07ff, 0x0fff, 0x1fff, 0x3fff,
    };

    private byte[] buf0;
    private int a2, d6, d7;

    /**
     * Unpacks in place, backwards: the stream is read from its end down, the
     * output written from {@code start+unpacked} down to {@code start}.
     */
    static void decrunch(byte[] buf, int start, int unpacked, int packed) {
        ShippedExe s = new ShippedExe(null);
        s.buf0 = buf;
        int a5 = start;
        int a1 = start + unpacked;
        s.a2 = start + packed;
        s.a2 -= 2;
        int d0 = be16(buf, s.a2);                  // move.w -(a2),d0
        s.a2 -= 4;
        s.d6 = be32(buf, s.a2);                    // move.l -(a2),d6
        s.d6 >>>= (16 - d0);                       // lsr.l d7,d6
        s.d7 = d0;
        while (a1 > a5) {
            int d4;
            if (s.bit()) {
                d4 = 0;                            // one literal
            } else {
                int d1;
                if (!s.bit()) {
                    d1 = 1; d4 = 1;
                } else if (!s.bit()) {
                    d1 = 2; d4 = 3;
                } else if (!s.bit()) {
                    d1 = 4; d4 = 7;
                } else {
                    d1 = 8; d4 = 0x17;
                }
                d4 += s.bits(d1);
                if (d4 == 0x16) {
                    // a long run of literals
                    d4 = 14;
                    d1 = s.bit() ? 5 : 14;
                    d4 += s.bits(d1);
                } else {
                    if (d4 > 0x16) {
                        d4--;
                    }
                    int d2;
                    if (!s.bit()) {
                        d1 = 9; d2 = 0x20;
                    } else if (!s.bit()) {
                        d1 = 5; d2 = 0;
                    } else {
                        d1 = 14; d2 = 0x220;
                    }
                    int off = (short) (s.bits(d1) + d2);
                    int a3 = a1 + off;
                    for (int i = 0; i <= d4; i++) {    // dbra d4
                        buf[--a1] = buf[--a3];
                    }
                    continue;
                }
            }
            for (int i = 0; i <= d4; i++) {
                buf[--a1] = (byte) s.bits(8);
            }
        }
    }

    /** {@code bsr $aa}: one bit, the carry. */
    private boolean bit() {
        d7 = (d7 - 1) & 0xffff;
        if (d7 != 0) {
            boolean c = (d6 & 1) != 0;
            d6 >>>= 1;
            return c;
        }
        d7 = 16;
        boolean c = (d6 & 1) != 0;                 // lsr.w #1,d0 on the old low word
        d6 >>>= 1;
        a2 -= 2;
        d6 = (be16(buf0, a2) << 16) | (d6 & 0xffff);   // swap / move.w -(a2),d6 / swap
        return c;
    }

    /** {@code bsr $c2}: {@code n} bits, low first. */
    private int bits(int n) {
        int d0 = d6 & 0xffff;
        d6 >>>= n;
        d7 = (short) (d7 - n);
        if (d7 <= 0) {
            d7 += 16;
            d6 = Integer.rotateRight(d6, d7);
            a2 -= 2;
            d6 = (d6 & 0xffff0000) | be16(buf0, a2);
            d6 = Integer.rotateLeft(d6, d7);
        }
        return d0 & MASK[n];
    }

    private static int be16(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    private static int be32(byte[] b, int at) {
        return (be16(b, at) << 16) | be16(b, at + 2);
    }
}
