package ab3d.data;

/**
 * One entry of a level's object list: a pickup, an enemy, a light, a decoration.
 *
 * Records are 64 bytes and the list ends on a negative point index. The field
 * layout comes from {@code insertanobj} and {@code BitMapObj} in
 * source/objdraw3.chipram, which between them read every field the renderer
 * needs:
 *
 * <pre>
 *    0 (w)  index into the level's object point table -- the x/z position
 *    2 (w)  brightness offset, added to the distance shade
 *    4 (w)  height; the engine scales it by 128 before subtracting the eye
 *    6 (b)  width scale, or 0xff to mark a polygon object instead of a sprite
 *    7 (b)  height scale
 *    8 (w)  graphic slot, indexing the 16-byte entries of the Objects table
 *   12 (w)  the zone the object is in play in, or -1 when it is not
 *   10 (w)  frame number within that slot's frame table
 *   14 (b)  sprite width in texels
 *   15 (b)  sprite height in texels
 *   26 (w)  GraphicRoom: the zone the object stands in
 *   63 (b)  ObjInTop: set when the object is on a zone's upper storey
 * </pre>
 */
public final class GameObject {

    public static final int SIZE = 64;
    /** Value of the width-scale byte that marks a polygon object. */
    public static final int POLYGON_MARKER = 0xff;

    public final int index;
    public final int pointIndex;
    public final int brightness;
    public final int height;
    public final int widthScale, heightScale;
    public final int slot, frame;
    /**
     * True when the word at offset 12 is negative.
     *
     * That word is a second zone number, written from {@code Roompt} as an
     * object enters play and set to {@code -1} when it leaves it. The records
     * holding {@code -1} in a shipped level are the dynamic tail -- the shot
     * pool and the weapon in hand -- which sit after the placed objects and are
     * the same count in every level. {@code RotateObjectPts} tests it with
     * {@code tst.w 12(a4)} and skips the point entirely, so an object out of
     * play costs nothing and cannot be drawn.
     */
    public final boolean notInPlay;
    /**
     * The word at offset 12 itself: the zone the object is in play in.
     *
     * The control code copies it straight into {@code GraphicRoom} whenever the
     * object updates ({@code move.w 12(a0),GraphicRoom(a0)}), so it -- not a
     * geometric test -- is what decides which zone's stream draws the sprite.
     */
    public final int inPlayZone;
    public final int spriteWidth, spriteHeight;
    /**
     * Zone the object stands in. The files leave this at -1 for many objects;
     * the engine fills it in at load time, and so does {@link Level}.
     */
    public int zone;
    /** The zone field as the file holds it, before {@link Level} fills it in. */
    public final int rawZone;
    public final boolean inUpperStorey;

    GameObject(BinReader r, int index, int off) {
        this.index = index;
        this.pointIndex = r.s16(off);
        this.brightness = r.s16(off + 2);
        this.height = r.s16(off + 4);
        this.widthScale = r.u8(off + 6);
        this.heightScale = r.u8(off + 7);
        this.slot = r.s16(off + 8);
        this.inPlayZone = r.s16(off + 12);
        this.notInPlay = this.inPlayZone < 0;
        this.frame = r.s16(off + 10);
        this.spriteWidth = r.u8(off + 14);
        this.spriteHeight = r.u8(off + 15);
        this.zone = r.s16(off + 26);
        this.rawZone = this.zone;
        this.inUpperStorey = r.u8(off + 63) != 0;
    }

    /** True when this object draws as a sprite rather than a vector shape. */
    public boolean isSprite() {
        return widthScale != POLYGON_MARKER;
    }

    @Override
    public String toString() {
        return String.format(
                "Object[%d pt=%d zone=%d y=%d slot=%d frame=%d sprite=%dx%d scale=%d/%d%s]",
                index, pointIndex, zone, height, slot, frame,
                spriteWidth, spriteHeight, widthScale, heightScale,
                isSprite() ? "" : " POLYGON");
    }
}
