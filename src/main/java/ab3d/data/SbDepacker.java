package ab3d.data;

/**
 * Decompressor for the {@code =SB=} files the game ships on its floppies.
 *
 * The container is twelve bytes -- the tag {@code =SB=}, the decompressed size
 * and the compressed length -- wrapping a standard LZH stream, the same scheme
 * LHA calls {@code -lh5-}/{@code -lh6-}. The engine's own depacker is the 68k
 * blob newsource/decomp4.raw, which the sources call {@code unLHA}; disassembling
 * it shows the giveaway sequence {@code read_pt_len(19, 5, 3)} for the pre-tree,
 * a literal/length tree, then a position tree whose width bit is 4 or 5
 * depending on whether the position count is below 16.
 *
 * Blocks are: a 16-bit symbol count, a 19-symbol pre-tree, the literal/length
 * code lengths coded with that pre-tree, and the position tree. Symbols below
 * 256 are literals; above that they are match lengths paired with a distance
 * from the position tree.
 */
public final class SbDepacker {

    /** Container tag, "=SB=". */
    private static final int TAG = 0x3d53423d;
    public static final int HEADER_SIZE = 12;

    // LZH constants
    private static final int THRESHOLD = 3;
    private static final int NC = 255 + 256 - THRESHOLD + 1;  // 510
    private static final int CBIT = 9;
    private static final int NT = 19;
    private static final int TBIT = 5;

    private final byte[] in;
    private int inPos;
    private final int inEnd;

    private int bitbuf, subbitbuf, bitcount;

    private final byte[] cLen = new byte[NC];
    private final byte[] ptLen = new byte[NT + 32];
    private final int[] cTable = new int[4096];
    private final int[] ptTable = new int[256];
    // The literal/length tree and the position tree get their own node pools:
    // the position tree is rebuilt after the literal tree within a block, and
    // sharing one pool would let it clobber nodes the literal tree still needs.
    private final int[] cLeft = new int[2 * NC], cRight = new int[2 * NC];
    private final int[] ptLeft = new int[2 * NC], ptRight = new int[2 * NC];

    private final int np, pbit;
    private int blockSize;

    private SbDepacker(byte[] in, int offset, int length, int np, int pbit) {
        this.in = in;
        this.inPos = offset;
        this.inEnd = Math.min(in.length, offset + length);
        this.np = np;
        this.pbit = pbit;
    }

    /** True when the buffer carries the {@code =SB=} tag. */
    public static boolean isPacked(byte[] data) {
        return data.length >= HEADER_SIZE && readInt(data, 0) == TAG;
    }

    /** Decompressed size recorded in the header. */
    public static int unpackedSize(byte[] data) {
        return readInt(data, 4);
    }

    /**
     * Unpacks a {@code =SB=} buffer, or returns it unchanged when it carries no
     * tag -- the repository already holds several files in plain form.
     *
     * The window size is not recorded in the container, so the three standard
     * ones are tried in turn and the first that decodes the declared number of
     * bytes wins.
     */
    public static byte[] unpack(byte[] data) {
        if (!isPacked(data)) {
            return data;
        }
        int size = unpackedSize(data);
        int packed = readInt(data, 8);
        if (size < 0 || size > (1 << 28)) {
            throw new IllegalArgumentException("Implausible unpacked size " + size);
        }

        // {np, pbit} for -lh5-, -lh6- and -lh7-
        int[][] variants = { { 14, 4 }, { 16, 5 }, { 17, 5 } };
        RuntimeException last = null;
        for (int[] v : variants) {
            try {
                return new SbDepacker(data, HEADER_SIZE, packed, v[0], v[1]).decode(size);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw new IllegalStateException("Could not unpack =SB= stream", last);
    }

    private byte[] decode(int size) {
        byte[] out = new byte[size];
        initBits();
        blockSize = 0;

        int pos = 0;
        while (pos < size) {
            int c = decodeC();
            if (c <= 255) {
                out[pos++] = (byte) c;
                continue;
            }
            int length = c - 256 + THRESHOLD;
            int distance = decodeP() + 1;
            if (distance > pos) {
                throw new IllegalStateException("Match before start of output");
            }
            int from = pos - distance;
            for (int i = 0; i < length && pos < size; i++) {
                out[pos++] = out[from + i];
            }
        }
        return out;
    }

    // ---- bit reader: big-endian, 16 bits of look-ahead -----------------------

    private void initBits() {
        bitbuf = 0;
        subbitbuf = 0;
        bitcount = 0;
        fillbuf(16);
    }

    private int nextByte() {
        return inPos < inEnd ? in[inPos++] & 0xff : 0;
    }

    private void fillbuf(int n) {
        while (n > bitcount) {
            n -= bitcount;
            bitbuf = ((bitbuf << bitcount) | (subbitbuf >>> (8 - bitcount))) & 0xffff;
            subbitbuf = nextByte();
            bitcount = 8;
        }
        bitcount -= n;
        bitbuf = ((bitbuf << n) | (n == 0 ? 0 : subbitbuf >>> (8 - n))) & 0xffff;
        subbitbuf = (subbitbuf << n) & 0xff;
    }

    private int getbits(int n) {
        int x = bitbuf >>> (16 - n);
        fillbuf(n);
        return x;
    }

    // ---- Huffman ------------------------------------------------------------

    /**
     * Builds the decode table LHA-style: codes no longer than {@code tablebits}
     * resolve in a single lookup, longer ones walk a binary tree held in
     * {@link #left} / {@link #right}.
     */
    private void makeTable(int nchar, byte[] bitlen, int tablebits, int[] table,
                           int[] left, int[] right) {
        int[] count = new int[17];
        int[] weight = new int[17];
        int[] start = new int[18];

        for (int i = 0; i < nchar; i++) {
            count[bitlen[i]]++;
        }
        count[0] = 0;

        start[1] = 0;
        for (int i = 1; i <= 16; i++) {
            start[i + 1] = start[i] + (count[i] << (16 - i));
        }
        if (start[17] != 0x10000) {
            throw new IllegalStateException("Code lengths do not form a full tree");
        }

        int jutbits = 16 - tablebits;
        for (int i = 1; i <= tablebits; i++) {
            start[i] >>>= jutbits;
            weight[i] = 1 << (tablebits - i);
        }
        for (int i = tablebits + 1; i <= 16; i++) {
            weight[i] = 1 << (16 - i);
        }

        int mask = 1 << (15 - tablebits);
        for (int ch = 0; ch < nchar; ch++) {
            int len = bitlen[ch];
            if (len == 0) {
                continue;
            }
            int nextcode = start[len] + weight[len];
            if (len <= tablebits) {
                for (int i = start[len]; i < nextcode && i < table.length; i++) {
                    table[i] = ch;
                }
            } else {
                setLeaf(table, start[len], len, tablebits, jutbits, mask, ch, left, right);
            }
            start[len] = nextcode;
        }
    }

    /** Places {@code ch} at the end of its code path, creating nodes on the way. */
    private void setLeaf(int[] table, int code, int len, int tablebits,
                         int jutbits, int mask, int ch, int[] left, int[] right) {
        int idx = code >>> jutbits;
        int k = code;
        int bits = len - tablebits;

        if (table[idx] == 0) {
            left[nextAvail] = 0;
            right[nextAvail] = 0;
            table[idx] = nextAvail++;
        }
        int node = table[idx];
        while (bits > 1) {
            if ((k & mask) != 0) {
                if (right[node] == 0) {
                    left[nextAvail] = 0;
                    right[nextAvail] = 0;
                    right[node] = nextAvail++;
                }
                node = right[node];
            } else {
                if (left[node] == 0) {
                    left[nextAvail] = 0;
                    right[nextAvail] = 0;
                    left[node] = nextAvail++;
                }
                node = left[node];
            }
            k = (k << 1) & 0xffff;
            bits--;
        }
        if ((k & mask) != 0) {
            right[node] = ch;
        } else {
            left[node] = ch;
        }
    }

    private int nextAvail;

    private void readPtLen(int nn, int nbit, int iSpecial) {
        int n = getbits(nbit);
        if (n == 0) {
            int c = getbits(nbit);
            java.util.Arrays.fill(ptLen, 0, nn, (byte) 0);
            java.util.Arrays.fill(ptTable, c);
            return;
        }
        int i = 0;
        while (i < n && i < nn) {
            int c = bitbuf >>> 13;
            if (c == 7) {
                int mask = 1 << 12;
                while ((mask & bitbuf) != 0) {
                    mask >>>= 1;
                    c++;
                }
            }
            fillbuf(c < 7 ? 3 : c - 3);
            ptLen[i++] = (byte) c;
            if (i == iSpecial) {
                int z = getbits(2);
                while (--z >= 0 && i < nn) {
                    ptLen[i++] = 0;
                }
            }
        }
        while (i < nn) {
            ptLen[i++] = 0;
        }
        buildTable(nn, ptLen, 8, ptTable, ptLeft, ptRight);
    }

    private void readCLen() {
        int n = getbits(CBIT);
        if (n == 0) {
            int c = getbits(CBIT);
            java.util.Arrays.fill(cLen, (byte) 0);
            java.util.Arrays.fill(cTable, c);
            return;
        }
        int i = 0;
        while (i < n && i < NC) {
            int c = ptTable[bitbuf >>> 8];
            if (c >= NT) {
                int mask = 1 << 7;
                do {
                    c = (bitbuf & mask) != 0 ? ptRight[c] : ptLeft[c];
                    mask >>>= 1;
                } while (c >= NT && mask != 0);
            }
            fillbuf(ptLen[c]);
            if (c <= 2) {
                if (c == 0) {
                    c = 1;
                } else if (c == 1) {
                    c = getbits(4) + 3;
                } else {
                    c = getbits(CBIT) + 20;
                }
                while (--c >= 0 && i < NC) {
                    cLen[i++] = 0;
                }
            } else {
                cLen[i++] = (byte) (c - 2);
            }
        }
        while (i < NC) {
            cLen[i++] = 0;
        }
        buildTable(NC, cLen, 12, cTable, cLeft, cRight);
    }

    private int decodeC() {
        if (blockSize == 0) {
            blockSize = getbits(16);
            readPtLen(NT, TBIT, 3);
            readCLen();
            readPtLen(np, pbit, -1);
        }
        blockSize--;
        int j = cTable[bitbuf >>> 4];
        if (j >= NC) {
            int mask = 1 << 3;
            do {
                j = (bitbuf & mask) != 0 ? cRight[j] : cLeft[j];
                mask >>>= 1;
            } while (j >= NC && mask != 0);
        }
        fillbuf(cLen[j]);
        return j;
    }

    private int decodeP() {
        int j = ptTable[bitbuf >>> 8];
        if (j >= np) {
            int mask = 1 << 7;
            do {
                j = (bitbuf & mask) != 0 ? ptRight[j] : ptLeft[j];
                mask >>>= 1;
            } while (j >= np && mask != 0);
        }
        fillbuf(ptLen[j]);
        if (j != 0) {
            j = (1 << (j - 1)) + getbits(j - 1);
        }
        return j;
    }

    /** Wrapper that resets the shared node pool before each table build. */
    private void buildTable(int nchar, byte[] bitlen, int tablebits, int[] table,
                            int[] left, int[] right) {
        java.util.Arrays.fill(table, 0);
        java.util.Arrays.fill(left, 0);
        java.util.Arrays.fill(right, 0);
        nextAvail = nchar;
        makeTable(nchar, bitlen, tablebits, table, left, right);
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
             | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }
}
