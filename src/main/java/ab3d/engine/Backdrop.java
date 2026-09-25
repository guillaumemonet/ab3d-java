package ab3d.engine;

import ab3d.data.GameData;
import ab3d.data.SbDepacker;
import ab3d.m68k.M68k;

import java.io.IOException;
import java.nio.file.Files;

/**
 * {@code putinbackdrop}, transcribed from source/anims.
 *
 * The backdrop is laid down once a frame, before any room, and every wall and
 * floor then paints over it. It is not part of the polygon stream -- the shipped
 * levels carry no backdrop tag at all -- but is asked for by the zone the viewer
 * stands in, through {@code ToBack}. Between a third and two thirds of the zones
 * in each level set it.
 *
 * The picture is stored a column at a time, thirty-eight rows of twelve-bit
 * colour, and is read a longword at a time so one read fills two rows: the high
 * word is the upper of the pair. It is 432 columns wide and wraps, and the
 * column it starts at follows the view angle, which is what makes it turn with
 * the viewer.
 *
 * The column walk is the same three blocks of thirty-two with a slot skipped
 * between them that the walls, the floor and the weapon all use.
 */
public final class Backdrop {

    /** Columns in the stored picture. */
    public static final int WIDTH = 432;
    /** Rows it covers, and the screen row it starts at. */
    public static final int HEIGHT = 38, TOP_ROW = 1;
    /** {@code move.w #2,d4} and {@code move.w #31,d3}. */
    private static final int CHUNKS = 3, PER_CHUNK = 32;

    private final EngineState s;
    private final byte[] picture;

    /** Pixels the last call wrote, for checking. */
    public int pixelsWritten;

    public Backdrop(EngineState state, GameData game) throws IOException {
        this.s = state;
        this.picture = SbDepacker.unpack(
                game.bytes("backfile"));
    }

    /**
     * Draws the backdrop for one view angle.
     *
     * @param angle the sine table's angle, 8192 to the turn here rather than the
     *              4096 the projection uses -- {@code and.w #8191,d5}
     */
    public void draw(int angle) {
        pixelsWritten = 0;
        if (picture.length < WIDTH * HEIGHT * 2) {
            return;
        }

        // muls #432,d5 / divs #8192,d5: the angle becomes a column
        int d5 = M68k.w(angle) & 8191;
        int column = M68k.w(M68k.divsQuotient(M68k.muls(d5, WIDTH), 8192));

        for (int c = 0; c < CHUNKS * PER_CHUNK; c++) {
            int src = Math.floorMod(column + c, WIDTH) * HEIGHT * 2;
            int at = s.rowStart(TOP_ROW) + EngineState.columnWord(c);
            for (int r = 0; r < HEIGHT; r += 2) {
                // move.l (a1)+,d0: the high word is the upper row of the pair
                int hi = ((picture[src] & 0xff) << 8) | (picture[src + 1] & 0xff);
                int lo = ((picture[src + 2] & 0xff) << 8) | (picture[src + 3] & 0xff);
                src += 4;
                if (at >= 0 && at < s.screen.length) {
                    s.screen[at] = (short) hi;
                    pixelsWritten++;
                }
                int below = at + EngineState.ROW_WORDS;
                if (below >= 0 && below < s.screen.length) {
                    s.screen[below] = (short) lo;
                    pixelsWritten++;
                }
                at += EngineState.ROW_WORDS * 2;
            }
        }
    }
}
