package ab3d.engine;

import ab3d.data.SpriteBank;
import ab3d.data.SpriteSheet;

/**
 * {@code DRAWINGUN} and {@code DRAWCHUNK}, transcribed from source/jg.s.
 *
 * The weapon in hand is drawn last of all, after every room, straight over
 * whatever the world left behind. It is not a sprite in the {@code BitMapObj}
 * sense: there is no projection, no depth and no scaling. It occupies the whole
 * width of the view and each column is read one word per row from the top of the
 * graphic, which is why it is a separate routine.
 *
 * The column walk is worth noting because it confirms the view's shape from a
 * second direction: {@code DRAWCHUNK} advances four bytes a column and is called
 * three times with thirty-two columns each, with an {@code addq.w #4,a6} between
 * the calls. That extra slot is the gap {@code xtocopx} steps over.
 *
 * master.s has none of this -- its {@code jumpoutofrooms} is a bare {@code rts}.
 */
public final class GunDraw {

    /** {@code Objects+9*16}: the weapon graphics always come from slot nine. */
    public static final int SLOT = 9;
    /** {@code DRAWCHUNK} is called three times with 32 columns each. */
    private static final int CHUNKS = 3, PER_CHUNK = 32;
    /** {@code move.w #78,d3 / sub.w d7,d3}: the last row a column reaches. */
    private static final int LAST_ROW = 78;

    /**
     * {@code GUNYOFFS}: the row each weapon starts at.
     *
     * Two of them sit at the very top of the view rather than twenty rows down,
     * which is what makes the shotgun and the grenade fill more of the screen.
     */
    private static final int[] Y_OFFSET = {20, 20, 0, 20, 20, 0, 0, 0};

    private final EngineState s;
    private final SpriteBank bank;
    /**
     * {@code GunAnims}: the frame each step of a weapon's animation shows.
     *
     * Read from the source rather than written out here, because two of the
     * six lists are made of {@code dcb.w} runs -- the shotgun's is sixty-four
     * entries from four of them -- and a hand copy of those is a guess.
     */
    private final ab3d.data.GunAnims anims;

    /** Counters for checking. */
    public int columnsDrawn, pixelsWritten, blankColumns;

    public GunDraw(EngineState state, SpriteBank bank,
                   ab3d.data.GunAnims anims) {
        this.s = state;
        this.bank = bank;
        this.anims = anims;
    }

    /** {@code move.b 7(a0,d0.w*8),MaxFrame}: where a shot starts the count. */
    public int animLength(int gun) {
        return anims.maxFrame(gun);
    }

    /**
     * Draws the weapon in hand.
     *
     * @param gun   {@code PLR1_GunSelected}
     * @param frame {@code PLR1_GunFrame}, a step within that weapon's animation
     */
    public void draw(int gun, int frame) {
        columnsDrawn = 0;
        pixelsWritten = 0;
        blankColumns = 0;

        int[] steps = anims.frames(gun);
        if (gun < 0 || steps.length == 0) {
            return;                                   // dc.l 0,0: no weapon here
        }
        SpriteSheet sheet = bank.sheet(SLOT);
        if (sheet == null) {
            return;
        }

        // move.w (a1,d1.w*2),d5: the frame this step of the animation shows
        int step = steps[Math.floorMod(frame, steps.length)];
        int yoff = Y_OFFSET[gun];                     // move.w (a1,d0.w*2),d7

        // asl.w #2,d0 / add.w d5,d0 / move.w (a2,d0.w*4),d1
        SpriteBank.Frame f = bank.frame(SLOT, gun * 4 + step);
        if (f == null) {
            return;
        }
        int column = f.ptrOffset() / 4;               // lea (a5,d1.w),a5

        int rows = LAST_ROW - yoff;                   // dbra runs rows + 1 times
        if (rows < 0) {
            return;
        }

        for (int c = 0; c < CHUNKS * PER_CHUNK; c++) {
            if (sheet.isBlank(column + c)) {
                blankColumns++;                       // move.l (a5)+,d1 / beq
                continue;
            }
            columnsDrawn++;
            int at = s.rowStart(yoff) + EngineState.columnWord(c);
            for (int r = 0; r <= rows; r++) {
                // The graphic is read straight down, one word a row
                int pixel = sheet.pixel(column + c, r);
                if (pixel != SpriteSheet.TRANSPARENT
                        && at >= 0 && at < s.screen.length) {
                    s.screen[at] = (short) sheet.colour(0, pixel);
                    pixelsWritten++;
                }
                at += EngineState.ROW_WORDS;          // add.w #104*4,a3
            }
        }
    }
}
