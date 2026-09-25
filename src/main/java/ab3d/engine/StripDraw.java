package ab3d.engine;

import ab3d.data.GameData;
import ab3d.data.WadTexture;
import ab3d.m68k.M68k;

import java.io.IOException;
import java.nio.file.Files;

/**
 * {@code scrdrawlop} and {@code ScreenWallstripdraw}, transcribed from
 * source/wallroutine3.chipmem.
 *
 * This is where a wall finally reaches the buffer. For each column the divider
 * emitted it picks the texture strip, clips the run of rows against the current
 * clip bounds, and walks down writing one colour word per row.
 *
 * Two tables carry the arithmetic:
 *
 * <ul>
 *   <li>{@code xtocopx}, 96 words, turns a column into a byte offset. Its step is
 *       a constant 4, which with the 416-byte row stride makes 104 four-byte
 *       slots per row, of which the view uses 96.</li>
 *   <li>{@code constantfile}, 8191 entries of two longwords indexed by depth,
 *       gives the vertical texture walk. The first is the step per screen row and
 *       the second the value at row zero, and they are built so that
 *       {@code base + 40 * step} comes out at 2^21 whatever the depth: the
 *       texture is anchored at the horizon, and only how fast it runs away from
 *       there depends on distance. The table is a rounded version of that
 *       relation rather than an exact one -- it is off by up to 64 in 2^21, and
 *       exact for about seventy percent of depths -- so the table is read rather
 *       than the relation recomputed.</li>
 * </ul>
 *
 * The texel itself is one of three five-bit fields packed into a word, chosen by
 * the remainder the divide-by-three table yields -- {@code drawwallPACK0},
 * {@code PACK1} and {@code PACK2}.
 */
public final class StripDraw {

    /** Brightness is clamped to this before indexing the shade table. */
    private static final int MAX_BRIGHT = 64;

    /**
     * {@code ffscrpickhowbright}, the {@code SCALE} macro's table: a brightness
     * becomes a palette row at {@code 64 * ((i + 1) / 2)}, so two brightness
     * steps share a row, and it saturates at row 31. Reading it as a proportion
     * of the range instead is off by a row over much of the scale.
     */
    private static int shadeRowFor(int bright) {
        int row = (bright + 1) / 2;
        return Math.min(row, WadTexture.SHADES - 1);
    }
    /** Row at which the texture walk is anchored, {@code add.w #40,d5}. */
    private static final int ANCHOR_ROW = EngineState.CENTRE_Y;

    private final EngineState s;
    /** {@code scrintocop}: column to byte offset. */
    private final short[] xToCop;
    /** {@code constantfile}: step and base of the vertical walk, per depth. */
    private final int[] stepByDepth;
    private final int[] baseByDepth;

    /** Rows written by the last run, for checking. */
    public int rowsWritten;
    /** Columns skipped because the clip left nothing to draw. */
    public int columnsClippedAway;
    /** Rows written per screen column, cleared by the caller each frame. */
    public final int[] rowsPerColumn = new int[EngineState.VIEW_COLUMNS];

    public StripDraw(EngineState state, GameData game) throws IOException {
        this.s = state;

        byte[] x = Files.readAllBytes(game.root().resolve("includes/xtocopx"));
        xToCop = new short[x.length / 2];
        for (int i = 0; i < xToCop.length; i++) {
            xToCop[i] = (short) (((x[i * 2] & 0xff) << 8) | (x[i * 2 + 1] & 0xff));
        }

        byte[] c = Files.readAllBytes(game.root().resolve("includes/constantfile"));
        int n = c.length / 8;
        stepByDepth = new int[n];
        baseByDepth = new int[n];
        for (int i = 0; i < n; i++) {
            stepByDepth[i] = readInt(c, i * 8);
            baseByDepth[i] = readInt(c, i * 8 + 4);
        }
    }

    /**
     * Draws every column the divider produced for one strip.
     *
     * @param sd   the per-column list
     * @param tex  the wall's texture
     * @param w    the wall record, for its masks, tile and vertical offset
     */
    public void run(ScreenDivide sd, WadTexture tex, WallDraw.Wall w) {
        rowsWritten = 0;
        columnsClippedAway = 0;
        if (tex == null) {
            return;
        }
        int height = w.valAnd + 1;
        int strips = tex.stripCount(height);
        if (strips <= 0) {
            return;
        }

        for (int i = 0; i < sd.count; i++) {
            int column = sd.columnAt[i];
            if (column < 0 || column >= xToCop.length) {
                continue;
            }

            // pastscrinto: the texture column, wrapped and offset by the tile
            int u = M68k.w(sd.texColumnAt[i] >> 16) & w.horAnd;
            int index = M68k.w(u + w.fromTile);
            int strip = Math.floorMod(index / 3, strips);
            int sub = Math.floorMod(index, 3);

            int depth = M68k.w(sd.depthAt[i] >> 16);
            int top = M68k.w(sd.topAt[i] >> 16);
            int bottom = M68k.w(sd.bottomAt[i] >> 16);

            // move.w d2,d6 / asr.w #7,d6 / move.l (a0)+,d5 / swap d5 / ext.w d5.
            // The gouraud term is sign-extended from its low BYTE, so it is
            // limited to -128..127 and wraps past that; taking the whole word
            // lets a bright wall run away instead of wrapping as it should.
            int bright = M68k.w(M68k.asrW(M68k.w(depth), 7)
                              + M68k.extW(M68k.swap(sd.brightAt[i])));
            if (bright < 0) {
                bright = 0;
            } else if (bright > MAX_BRIGHT) {
                bright = MAX_BRIGHT;
            }
            int shadeRow = shadeRowFor(bright);

            drawColumn(column, top, bottom, depth, strip, sub, height, shadeRow, tex, w);
        }
    }

    /**
     * {@code ScreenWallstripdraw}: clips one column's run of rows and walks it.
     */
    private void drawColumn(int column, int top, int bottom, int depth,
                            int strip, int sub, int height, int shadeRow,
                            WadTexture tex, WallDraw.Wall w) {
        // cmp.w topclip,d6 / blt nostripq   (d6 is the bottom)
        if (bottom < s.topClip || top > s.botClip) {
            columnsClippedAway++;
            return;
        }
        int end = Math.min(bottom, s.botClip);      // noclipbot
        int start = Math.max(top, s.topClip);       // nocliptop
        // sub.w d5,d6 / ble nostripq, then dbra d6 runs d6 + 1 times
        int height68k = end - start;
        if (height68k <= 0) {
            columnsClippedAway++;
            return;
        }
        int rows = height68k + 1;

        if (depth < 0) {
            depth = 0;
        } else if (depth >= stepByDepth.length) {
            depth = stepByDepth.length - 1;
        }
        int step = stepByDepth[depth];
        int base = baseByDepth[depth];

        // gotoend: the walk starts at the clipped top row
        int v = base + start * step;

        int screen = s.rowStart(start) + (xToCop[column] & 0xffff) / 2;
        for (int r = 0; r < rows; r++) {
            int texelRow = (M68k.w(v >> 16) + w.totalYoff) & w.valAnd;
            int texel = tex.texel(strip, texelRow, height, sub);
            if (screen >= 0 && screen < s.screen.length) {
                s.screen[screen] = (short) tex.colour(shadeRow, texel);
                if (column >= 0 && column < rowsPerColumn.length) {
                    rowsPerColumn[column]++;
                }
            }
            screen += EngineState.ROW_WORDS;
            v += step;
            rowsWritten++;
        }
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16)
             | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    /** Anchor value the table is built around, kept for the check tool. */
    public int anchorAt(int depth) {
        int d = Math.max(0, Math.min(depth, stepByDepth.length - 1));
        return baseByDepth[d] + ANCHOR_ROW * stepByDepth[d];
    }
}
