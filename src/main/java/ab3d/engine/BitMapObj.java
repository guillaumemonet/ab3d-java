package ab3d.engine;

import ab3d.data.BuiltTables;
import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.SpriteBank;
import ab3d.data.SpriteSheet;
import ab3d.m68k.M68k;

import java.io.IOException;
import java.nio.file.Files;

/**
 * {@code BitMapObj}, transcribed from source/objdraw3.chipram.
 *
 * One sprite, from its object record to the view buffer. The routine does its
 * own setup -- projection, brightness, scaling, clipping -- so everything from
 * the label down to {@code objbehind} lives here; only the depth sort that
 * chooses the order belongs to {@link ObjDraw}.
 *
 * Three tables drive it:
 *
 * <ul>
 *   <li>{@code consttab}, which is {@code constantfile} -- the same incbin
 *       {@link StripDraw} reads for walls, viewed differently. Here only the
 *       first longword of each eight-byte entry is used, as a 16.16 step, and
 *       the index is not a depth but {@code z * 2 * texels / scale}: how far the
 *       sprite advances through its graphic per screen pixel.</li>
 *   <li>{@code objintocop}, which is {@code XTOCOPX} again: column to byte
 *       offset, step 4.</li>
 *   <li>{@code objscalecols}, seventy-four words mapping a brightness to one of
 *       fifteen palette rows. It is flat at both ends -- the first two entries
 *       share row 0 and the last twenty share row 14 -- with four entries per
 *       row in between.</li>
 * </ul>
 *
 * The pixel fetch is the same three-pixels-per-word packing the walls use, but
 * selected differently: a wall takes the remainder of a divide by three, while a
 * sprite column carries its variant in the top byte of its pointer-table entry.
 * A zero entry is a blank column and index zero is transparent, so a sprite gets
 * its shape from the graphic rather than from a mask.
 */
public final class BitMapObj {

    /** {@code objscalecols}: brightness to palette row. */
    private static final int[] OBJ_SCALE_COLS = buildScaleCols();

    private static int[] buildScaleCols() {
        int[] t = new int[74];
        int at = 0;
        for (int i = 0; i < 2; i++) {
            t[at++] = 0;                       // dcb.w 2,64*0
        }
        for (int level = 1; level <= 13; level++) {
            for (int i = 0; i < 4; i++) {
                t[at++] = level;               // dcb.w 4,64*level
            }
        }
        while (at < t.length) {
            t[at++] = 14;                      // dcb.w 20,64*14
        }
        return t;
    }

    /** Depth at or below which an object is not drawn, {@code cmp.w #50,d1}. */
    private static final int MIN_DEPTH = 50;

    private final EngineState s;
    private final SpriteBank bank;
    /** {@code objintocop}: column to byte offset. */
    private final short[] objIntoCop;
    /** First longword of each {@code consttab} entry, a 16.16 step. */
    private final int[] constStep;

    /** {@code objclipt} and {@code objclipb}, the vertical window for one sprite. */
    public int objClipT, objClipB;
    /** {@code leftclipb} and {@code rightclipb}, copied from the wall clip. */
    public int leftClipB, rightClipB;

    /** Counters for checking. */
    public int pixelsWritten, columnsDrawn, blankColumns;
    /** Rows whose fetch fell before or past the stored graphic. */
    public int rowsBefore, rowsPast;
    /** Pixel fetches the last call made, whatever came back. */
    public int rowsFetched;
    /** The last call's working values, for probing. */
    public int lastDepth, lastColumn, lastHoriz, lastVert, lastStartRow, lastRows, lastShade;
    /** Why the last call drew nothing, or null when it drew. */
    public String rejectedBy;

    public BitMapObj(EngineState state, SpriteBank bank, GameData game) throws IOException {
        this.s = state;
        this.bank = bank;

        int[] x = BuiltTables.xToCopX();
        objIntoCop = new short[x.length];
        for (int i = 0; i < objIntoCop.length; i++) {
            objIntoCop[i] = (short) x[i];
        }

        byte[] c = game.bytes("constantfile");
        constStep = new int[c.length / 8];
        for (int i = 0; i < constStep.length; i++) {
            int o = i * 8;
            constStep[i] = ((c[o] & 0xff) << 24) | ((c[o + 1] & 0xff) << 16)
                         | ((c[o + 2] & 0xff) << 8) | (c[o + 3] & 0xff);
        }
    }

    /**
     * Draws one sprite.
     *
     * @param o    the object record
     * @param ty3d the top of the room part the object stands in, and
     * @param by3d its bottom; {@link ObjDraw} picks the pair before calling
     * @return true when something reached the buffer
     */
    public boolean draw(GameObject o, int ty3d, int by3d) {
        pixelsWritten = 0;
        columnsDrawn = 0;
        blankColumns = 0;
        rowsBefore = 0;
        rowsPast = 0;
        rowsFetched = 0;
        rejectedBy = null;

        // move.w leftclip,leftclipb / move.w rightclip,rightclipb
        leftClipB = s.leftClip;
        rightClipB = s.rightClip;

        // cmp.b #$ff,6(a0) / bne BitMapObj -- the caller has already chosen us
        // tst.l 8(a0) / blt glassobj: slot and frame read as one longword
        if (o.slot < 0) {
            return reject("glassobj");
        }

        int point = o.pointIndex;
        if (point < 0 || point >= s.objRotZ.length) {
            return reject("nopoint");
        }

        // move.w 2(a1,d0.w*8),d1 / cmp.w #50,d1 / ble objbehind
        int d1 = s.objRotZ[point];
        if (d1 <= MIN_DEPTH) {
            return reject("tooclose");
        }

        // The room top and bottom, projected, then clamped to the wall clip
        int d2 = s.topClip;
        int d3 = s.botClip;

        int d6 = M68k.w(M68k.w(M68k.divsInto(ty3d - s.yoff, d1)) + 40);
        if (d6 >= d3) {
            return reject("toplow");
        }
        if (d6 < d2) {
            d6 = d2;
        }
        objClipT = d6;

        d6 = M68k.w(M68k.w(M68k.divsInto(by3d - s.yoff, d1)) + 40);
        if (d6 <= d2) {
            return reject("bothigh");
        }
        if (d6 > d3) {
            d6 = d3;
        }
        objClipB = d6;

        // move.l 4(a1,d0.w*8),d0: the scaled, wobbled X
        int d0 = s.objScaledX[point];

        // move.w d1,d6 / asr.w #7,d6 / add.w (a0)+,d6 / bge / moveq #0,d6
        d6 = M68k.w(M68k.asrW(d1, 7) + o.brightness);
        if (d6 < 0) {
            d6 = 0;
        }
        // move.w objscalecols(pc,d6.w*2),a4 -- past the table the original reads
        // its own code; the tail is flat at row 14, so clamping gives the same
        // answer for every depth the projection can actually produce.
        int shade = OBJ_SCALE_COLS[Math.min(d6, OBJ_SCALE_COLS.length - 1)];

        // move.w (a0)+,d2 / ext.l / asl.l #7 / sub.l yoff / divs d1 / add.w #39
        int height = M68k.aslL(M68k.extL(o.height), 7) - s.yoff;
        d2 = M68k.w(M68k.w(M68k.divsInto(height, d1)) + 39);

        // divs d1,d0 / add.w #47,d0
        d0 = M68k.w(M68k.w(M68k.divsInto(d0, d1)) + EngineState.CENTRE_X);

        // move.b (a0)+,d3 / move.b (a0)+,d4 / lsl.w #7 / divs d1
        d3 = M68k.w(M68k.divsInto(M68k.aslW(o.widthScale, 7), d1));
        int d4 = M68k.w(M68k.divsInto(M68k.aslW(o.heightScale, 7), d1));

        // sub.w d4,d2 / sub.w d3,d0: the top left corner
        d2 = M68k.w(d2 - d4);
        d0 = M68k.w(d0 - d3);
        if (d0 >= rightClipB) {
            return reject("offright");
        }
        d3 = M68k.w(d3 + d3);                  // full width
        if (d2 >= objClipB) {
            return reject("offbottom");
        }
        d4 = M68k.w(d4 + d4);                  // full height

        SpriteSheet sheet = bank.sheet(o.slot);
        if (sheet == null) {
            return reject("nosheet");
        }
        SpriteBank.Frame frame = bank.frame(o.slot, o.frame);
        if (frame == null) {
            return reject("noframe");
        }
        int downStrip = M68k.w(frame.downStrip());
        int column = M68k.w(frame.ptrOffset()) / 4;

        // The two consttab entries: how far the graphic moves per screen pixel
        int horiz = constEntry(d1, o.spriteWidth, o.widthScale);
        int vert = constEntry(d1, o.spriteHeight, o.heightScale);

        // CLIP OBJECT TO TOP AND BOTTOM OF THE VISIBLE DISPLAY
        int d7 = 0;
        if (d2 < objClipT) {
            d2 = M68k.w(d2 - objClipT);
            d4 = M68k.w(d4 + d2);
            if (d4 <= 0) {
                return reject("clippedout");
            }
            d7 = M68k.w(-d2);                  // rows skipped at the top
            d2 = objClipT;
        }
        d6 = M68k.w(objClipB - d2);
        if (d4 > d6) {
            d4 = d6;
        }
        d4 = M68k.w(d4 - 1);                   // subq #1: dbra runs d4 + 1 times
        if (d4 < 0) {
            return reject("noheight");
        }

        // move.l (a6,d2.w*4),d2 / add.l frompt,d2 / move.l d2,toppt
        int topPt = s.rowStart(d2);

        // Left edge
        if (d0 < leftClipB) {
            d0 = M68k.w(d0 - leftClipB);
            d3 = M68k.w(d3 + d0);
            if (d3 <= 0) {
                return reject("clippedleft");
            }
            // The skipped columns, stepped through the horizontal constant
            int skipped = M68k.w(-d0);
            int hi = M68k.muls(skipped, M68k.w(horiz >> 16));
            int lo = M68k.swap(M68k.mulu(skipped, M68k.w(horiz)));
            column += M68k.w(M68k.w(hi) + M68k.w(lo));
            d0 = leftClipB;
        }

        // Right edge
        d6 = M68k.w(M68k.w(d0 + d3) - rightClipB);
        if (d6 >= 0) {
            d3 = M68k.w(M68k.w(d3 - 1) - d6);
        }

        // lea (a1,d0.w*2),a1 -- objintocop, one word per column
        int copAt = d0;

        // The row the graphic starts at, stepped by however much was clipped off
        // the top, plus the frame own offset, plus the rounding bias.
        int vhi = M68k.muls(d7, M68k.w(vert >> 16));
        int vlo = M68k.swap(M68k.mulu(d7, M68k.w(vert)));
        int d5 = M68k.w(M68k.w(vhi) + M68k.w(vlo));
        d5 = M68k.setW(0, M68k.w(d5 + downStrip));
        d5 += 0x80000000;                      // add.l #$80000000,d5

        // move.l (a3),d2 / swap d2: the step, integer part in the low word
        int vertStep = M68k.swap(vert);

        lastDepth = d1;
        lastColumn = column;
        lastHoriz = horiz;
        lastVert = vert;
        lastStartRow = M68k.w(d5);
        lastRows = d4 + 1;
        lastShade = shade;

        int midObj = column;
        d7 = 0;                                // 16.16, integer in the high word

        for (int col = d3; col >= 0; col--) {
            // swap d7 / lea (a5,d7.w*4),a5 / swap d7 / add.l a2,d7
            int stripColumn = midObj + M68k.w(d7 >> 16);
            d7 += horiz;

            if (copAt < 0 || copAt >= objIntoCop.length) {
                break;
            }
            int screen = topPt + (objIntoCop[copAt] & 0xffff) / 2;
            copAt++;

            if (sheet.isBlank(stripColumn)) {
                blankColumns++;                // move.l (a5),d1 / beq blankstrip
                continue;
            }
            drawStrip(sheet, stripColumn, screen, d5, vertStep, d4, shade);
            columnsDrawn++;
        }
        return pixelsWritten > 0;
    }

    /**
     * One column: {@code drawavertstrip}, the loop each of the three variants
     * repeats around its own pixel fetch.
     *
     * The vertical walk keeps the texel row in a word and the fraction in a
     * separate longword, and moves both with {@code add.l} then {@code addx.w}
     * so the carry out of the fraction lands in the row. That pairing is why the
     * row cannot simply be the top half of one 16.16 value here.
     */
    private void drawStrip(SpriteSheet sheet, int column, int screen,
                           int d5, int step, int rows, int shade) {
        int d1 = d5;                           // move.l d5,d1
        int d6 = d5;                           // move.l d5,d6

        for (int r = rows; r >= 0; r--) {      // dbra d4
            int where = sheet.rowPlacement(column, M68k.w(d1));
            if (where < 0) {
                rowsBefore++;
            } else if (where > 0) {
                rowsPast++;
            }
            rowsFetched++;
            int pixel = sheet.pixel(column, M68k.w(d1));
            if (pixel != SpriteSheet.TRANSPARENT
                    && screen >= 0 && screen < s.screen.length) {
                s.screen[screen] = (short) sheet.colour(shade, pixel);
                pixelsWritten++;
            }
            screen += EngineState.ROW_WORDS;   // adda.w #104*4,a6

            // add.l d2,d6 / addx.w d2,d1
            long sum = (d6 & 0xffffffffL) + (step & 0xffffffffL);
            int carry = (int) (sum >>> 32);
            d6 = (int) sum;
            d1 = M68k.setW(d1, M68k.w(d1) + M68k.w(step) + carry);
        }
    }

    /**
     * The {@code consttab} entry for one axis.
     *
     * <pre>
     *   move.w d1,d7 / move.b n(a0),d6 / add.w d6,d6 / mulu d6,d7
     *   move.b m(a0),d6 / divu d6,d7 / swap / clr.w / swap
     * </pre>
     *
     * The divide is unsigned and the swap-clear-swap throws the remainder away,
     * so the index is a plain quotient however large the product got.
     */
    private int constEntry(int depth, int texels, int scale) {
        if (scale == 0) {
            return 0;
        }
        int d7 = M68k.mulu(M68k.w(texels * 2), M68k.w(depth));
        int index = M68k.w(M68k.divu(d7, scale)) & 0xffff;
        return constStep[Math.min(index, constStep.length - 1)];
    }


    private boolean reject(String why) {
        rejectedBy = why;
        return false;
    }
}
