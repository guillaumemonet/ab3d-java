package ab3d.engine;

import ab3d.data.GameData;
import ab3d.m68k.M68k;

import java.io.IOException;
import java.nio.file.Files;

/**
 * {@code Doleftend} and {@code screendivide}, transcribed from
 * source/wallroutine3.chipmem.
 *
 * Between them they turn one strip into a list of per-column records. Doleftend
 * converts each of the strip's six quantities from a left/right pair into a
 * 16.16 start and step; screendivide then walks the columns, advancing all six
 * accumulators together and writing one record per column that lands inside the
 * clip bounds.
 *
 * The step divisor comes from {@code iterfile}, a table of 512 entries giving,
 * for a span of that width, how many iterations to run and how far to shift. The
 * counts are always a power of two at least as large as the span -- 95 columns
 * gets 128 steps and a shift of 7 -- so the shift divides exactly and no
 * rounding creeps into the interpolation.
 */
public final class ScreenDivide {

    /** Quantities interpolated across a strip, in the order the record holds them. */
    public static final int COLUMN = 0, TEX_COLUMN = 1, DEPTH = 2,
            TOP = 3, BOTTOM = 4, BRIGHT = 5;

    /** One emitted column: the integer column plus five 16.16 accumulators. */
    public final int[] columnAt;
    public final int[] texColumnAt;
    public final int[] depthAt;
    public final int[] topAt;
    public final int[] bottomAt;
    public final int[] brightAt;
    /** How many columns the last run emitted. */
    public int count;

    /** {@code itertab}: iterations and shift, indexed by span width. */
    private final short[] iters;
    private final short[] shift;

    private final EngineState s;

    public ScreenDivide(EngineState state, GameData game) throws IOException {
        this.s = state;
        byte[] raw = Files.readAllBytes(game.root().resolve("includes/iterfile"));
        int n = raw.length / 4;
        this.iters = new short[n];
        this.shift = new short[n];
        for (int i = 0; i < n; i++) {
            iters[i] = (short) (((raw[i * 4] & 0xff) << 8) | (raw[i * 4 + 1] & 0xff));
            shift[i] = (short) (((raw[i * 4 + 2] & 0xff) << 8) | (raw[i * 4 + 3] & 0xff));
        }
        int cap = EngineState.VIEW_COLUMNS + 2;
        this.columnAt = new int[cap];
        this.texColumnAt = new int[cap];
        this.depthAt = new int[cap];
        this.topAt = new int[cap];
        this.bottomAt = new int[cap];
        this.brightAt = new int[cap];
    }

    /**
     * Runs both routines for one strip.
     *
     * @return false when the strip has no width, the case {@code Doleftend}
     *         returns on straight away
     */
    public boolean run(CalcAndDraw.Strip st) {
        count = 0;

        // Doleftend: move.w (a0),d0 / move.w 2(a0),d1 / sub.w d0,d1 / blt rts
        int d0 = M68k.w(st.leftColumn);
        int span = M68k.w(st.rightColumn - d0);
        if (span < 0) {
            return false;
        }
        if (span >= iters.length) {
            span = iters.length - 1;
        }
        int d7 = iters[span];               // move.w itertab(pc,d1.w*4),d7
        int d6shift = shift[span];          // move.w itertab+2(pc,d1.w*4),d6

        // Each pair becomes a 16.16 start in a register and a step in the record
        int colStart = d0 << 16;
        int colStep = stepOf(st.leftColumn, st.rightColumn, d6shift);
        int texStart = st.leftTexColumn << 16;
        int texStep = stepOf(st.leftTexColumn, st.rightTexColumn, d6shift);
        int depthStart = st.leftDepth << 16;
        int depthStep = stepOf(st.leftDepth, st.rightDepth, d6shift);
        int topStart = st.leftTop << 16;
        int topStep = stepOf(st.leftTop, st.rightTop, d6shift);
        int botStart = st.leftBottom << 16;
        int botStep = stepOf(st.leftBottom, st.rightBottom, d6shift);

        // Gouraud: the brightness delta is doubled before being scaled
        int brightStep = M68k.asrL(M68k.w(st.rightBright - st.leftBright) * 2 << 16,
                d6shift);
        int brightStart = (M68k.w(st.leftBright) * 2) << 16;

        // screendivide
        int leftClipAndLast = M68k.w(s.leftClip - 1);   // move.w leftclip,d0 / sub.w #1,d0
        int d6 = leftClipAndLast;

        int col = colStart, tex = texStart, depth = depthStart;
        int top = topStart, bot = botStart, bright = brightStart;

        for (int i = d7; i >= 0; i--) {
            int column = M68k.w(col >> 16);            // swap d0 : the integer part

            if (column <= d6) {
                // still off the left: advance without emitting
                col += colStep;
                tex += texStep;
                depth += depthStep;
                top += topStep;
                bot += botStep;
                bright += brightStep;
                continue;
            }
            d6 = column;                               // move.w d0,d6
            if (column >= s.rightClip) {
                break;                                 // outofcalc
            }
            if (count >= columnAt.length) {
                break;
            }
            columnAt[count] = column;
            texColumnAt[count] = tex;
            depthAt[count] = depth;
            topAt[count] = top;
            bottomAt[count] = bot;
            brightAt[count] = bright;
            count++;

            col += colStep;
            tex += texStep;
            depth += depthStep;
            top += topStep;
            bot += botStep;
            bright += brightStep;
        }
        return true;
    }

    /** {@code sub / swap / asr.l d6}: the 16.16 step between two ends. */
    private static int stepOf(int left, int right, int shiftBy) {
        return M68k.asrL(M68k.w(right - left) << 16, shiftBy);
    }

    /** Integer part of a 16.16 accumulator. */
    public static int whole(int fixed) {
        return fixed >> 16;
    }
}
