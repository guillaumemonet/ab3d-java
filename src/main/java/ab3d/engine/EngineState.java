package ab3d.engine;

import ab3d.data.Level;
import ab3d.m68k.M68k;

/**
 * The engine's global state and working buffers, laid out as the assembly lays
 * them out.
 *
 * Names are the ones the sources use, so a routine can be read side by side with
 * source/master.s. Sizes follow the originals: a {@code Rotated} entry is eight
 * bytes, an {@code OnScreen} entry two, and the view buffer advances
 * {@code 104*4} bytes per row.
 */
public final class EngineState {

    /** Columns the projection spans; {@code move.w #96,d2} clamps to this. */
    public static final int VIEW_COLUMNS = 96;
    /** Horizontal centre, from {@code add.w #47,d2}. */
    public static final int CENTRE_X = 47;
    /** Vertical centre, from {@code add.w #40,d5}. */
    public static final int CENTRE_Y = 40;
    /** Rows in the view. */
    public static final int VIEW_ROWS = 80;
    /**
     * Bytes between two rows of the view buffer. Every AB3D1 source steps by
     * this; the 96*2 that newsource uses belongs to the later RTG port and does
     * not apply here.
     */
    public static final int ROW_BYTES = 104 * 4;
    /** Words between two rows, since a pixel is a 12-bit colour word. */
    public static final int ROW_WORDS = ROW_BYTES / 2;

    public final Level level;

    // ---- camera ------------------------------------------------------------

    /** {@code xoff} / {@code zoff}: the viewer's position, in level units. */
    public int xoff, zoff;
    /** {@code yoff}: the viewer's eye height. */
    public int yoff;
    /** {@code sinval} / {@code cosval}: the facing, as sine table words. */
    public int sinval, cosval;
    /** {@code xwobble}: the horizontal shake added to every rotated point. */
    public int xwobble;

    /**
     * {@code wallyoff} and {@code flooryoff}: the eye height as each surface
     * wants it. The setup derives them from the same value but differently --
     * {@code yoff & 63} for walls, {@code yoff * 4} for floors -- so they are
     * kept apart rather than shared.
     */
    public int wallYoff, floorYoff;

    /**
     * {@code sxoff} / {@code szoff}: the viewer's position as the floor walk
     * wants it, scaled by the surface's own scale before use.
     */
    public int sxoff, szoff;

    /**
     * {@code ZoneBright}, the brightness of the zone being drawn. A surface adds
     * it to its own light before shading, so it is carried here rather than
     * passed: {@code add.w ZoneBright,d6} in {@code itsafloordraw}.
     */
    public int zoneBright;
    /** {@code xwobxoff} and {@code xwobzoff}, the floor's share of the wobble. */
    public int xwobXoff, xwobZoff;
    /**
     * {@code TOPOFROOM} and {@code BOTOFROOM}, the room part currently being
     * drawn. The RTG build rejects a surface that lies outside them before doing
     * anything else; master.s has no such test.
     */
    public int topOfRoom, botOfRoom;
    /**
     * {@code CurrentPointBrights}: the brightness of each point this frame.
     *
     * The engine rebuilds it once a frame from the raw words, resolving any
     * animation byte, and the wall and floor routines read it rather than the
     * level data. Keeping that shape means the animation is worked out once
     * instead of at every corner.
     */
    public int[] currentPointBright;

    /** {@code currzone} and {@code DOUPPER}. */
    public int currZone;
    public boolean doUpper;

    /**
     * Recomputes the two derived eye heights, as {@code DrawDisplay} does:
     * {@code move.l yoff,d0 / asr.l #8,d0}, then {@code d0 & 63} for walls and
     * {@code d0 << 2} for floors.
     */
    public void deriveYoffs() {
        int d0 = yoff >> 8;                  // asr.l #8,d0
        // jg.s: add.w #256-32,d1 / and.w #255,d1. master.s masks with 63 and has
        // no bias, which offsets every wall texture vertically and wraps it four
        // times as often.
        wallYoff = M68k.w(d0 + 256 - 32) & 255;
        floorYoff = (short) (d0 << 2);       // asl.w #2,d0
    }

    /** {@code leftclip} / {@code rightclip}: the columns drawing is confined to. */
    public int leftClip, rightClip = VIEW_COLUMNS;
    /** {@code topclip} / {@code botclip}: the rows drawing is confined to. */
    public int topClip, botClip = VIEW_ROWS;

    // ---- per-frame buffers -------------------------------------------------

    /**
     * {@code Rotated}, eight bytes per point. The first long is the rotated X
     * already scaled by 128 and wobbled; the second is the depth, stored swapped
     * so its low word is what the drawing routines read at offset 6.
     */
    public final int[] rotatedX;
    public final int[] rotatedZ;

    /** {@code OnScreen}, one word per point: the projected column. */
    public final short[] onScreen;

    /**
     * {@code ObjRotated}, eight bytes per object point and a different shape
     * from {@code Rotated}: a word of rotated X, a word of depth, then a
     * longword of the same X scaled by 128 and wobbled. {@code BitMapObj} reads
     * the depth at offset 2 and the long at offset 4, which is why both are
     * kept rather than one being derived from the other.
     */
    public final short[] objRotX;
    public final short[] objRotZ;
    public final int[] objScaledX;

    /** {@code ObsInLine}: set where an object is near the centre of the view. */
    public final byte[] objInLine;

    /** The view buffer, one 12-bit colour word per pixel. */
    public final short[] screen;

    public EngineState(Level level) {
        this.level = level;
        this.rotatedX = new int[level.numPoints];
        this.rotatedZ = new int[level.numPoints];
        this.onScreen = new short[level.numPoints];
        int objPts = level.objectPointX.length;
        this.objRotX = new short[objPts];
        this.objRotZ = new short[objPts];
        this.objScaledX = new int[objPts];
        this.objInLine = new byte[objPts];
        this.screen = new short[ROW_WORDS * VIEW_ROWS];
    }

    /** Depth of a rotated point: the word the routines read at {@code 6(a1,d7*8)}. */
    public int depth(int point) {
        return (short) rotatedZ[point];
    }

    /** Index of the first word of a screen row. */
    public int rowStart(int row) {
        return row * ROW_WORDS;
    }

    /**
     * Word offset of a column within a row.
     *
     * A row is <em>not</em> ninety-six four-byte slots in a line. It is three
     * blocks of thirty-two with one unused slot between them, which is why
     * {@code xtocopx} steps by four except at entries 32 and 64 where it steps
     * by eight, and why the floor routine walks thirty-two columns at a time and
     * does {@code addq #4,a3} between blocks ({@code adda.w #33*4,a3} when it
     * starts in the middle one). Anything that addresses a column with a plain
     * multiply lands in the gap at column 32 and is a column out from there on.
     */
    public static int columnWord(int column) {
        return (column + column / 32) * 2;
    }
}
