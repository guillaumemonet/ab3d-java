package ab3d.data;

import java.util.ArrayList;
import java.util.List;

/**
 * A convex-ish region of the map (the engine's equivalent of a Doom sector).
 *
 * Field offsets come from the {@code To*} equates in newsource/defs.s. Note this
 * version of the engine supports a second storey per zone (upper floor / roof),
 * which is what gives Alien Breed 3D its room-over-room geometry.
 *
 * The exit-list and point-list offsets are words <em>relative to the start of
 * this zone record</em>, exactly as the assembly uses them
 * ({@code adda.w ToExitList(a5),a0}). The exit offset is negative: the engine
 * stores each zone's border list just before its record.
 *
 * Caveat: newsource/defs.s puts those two words at 32 and 34, but the shipped
 * AB3D1 level data has them at 28 and 30 -- verified on lev1, where zone 0's
 * exit list resolves to the four lines that close its quad and the point list
 * starts exactly where the graphics list terminator ends. The data wins.
 */
public final class Zone {

    // Offsets within a zone record (newsource/defs.s)
    public static final int TO_ZONE_FLOOR = 2;
    public static final int TO_ZONE_ROOF = 6;
    public static final int TO_UPPER_FLOOR = 10;
    public static final int TO_UPPER_ROOF = 14;
    public static final int TO_ZONE_WATER = 18;
    public static final int TO_ZONE_BRIGHTNESS = 22;
    public static final int TO_UPPER_BRIGHTNESS = 24;
    public static final int TO_ZONE_CPT = 26;
    /**
     * Where the exit-list and point-list offsets sit inside a zone record.
     * newsource/defs.s documents 32 and 34, which is what the shipped levels
     * use; the development levels in the source tree use 28 and 30 instead.
     */
    public record Layout(int exitList, int zonePts) {
        public static final Layout SHIPPED = new Layout(32, 34);
        public static final Layout DEV = new Layout(28, 30);
    }
    public static final int TO_BACK = 36;
    public static final int TO_TEL_ZONE = 38;
    public static final int TO_TEL_X = 40;
    public static final int TO_TEL_Z = 42;
    public static final int TO_FLOOR_NOISE = 44;
    public static final int TO_UPPER_FLOOR_NOISE = 46;
    public static final int TO_LIST_OF_GRAPH = 48;

    /** Index of this zone, and its byte offset inside the level .bin. */
    public final int index;
    public final int offset;

    /** Heights are 16.16 fixed point; y grows downwards, so floor > roof. */
    /**
     * Not final either: a lift is a zone whose floor {@code LiftRoutine} moves,
     * the way a door moves a roof.
     */
    public int floorHeight;
    /**
     * Not final: a door is a zone whose roof {@code DoorRoutine} rewrites every
     * frame, so this follows the level data rather than being read once.
     */
    public int roofHeight;
    public final int upperFloorHeight, upperRoofHeight;
    public final int waterHeight;

    public final int brightness, upperBrightness;
    public final int controlPoint;
    public final int teleportZone, teleportX, teleportZ;
    public final int floorNoise, upperFloorNoise;
    public final int drawBackdrop;

    /** Absolute offsets of the two variable-length lists, for diagnostics. */
    public final int exitListOffset, pointsListOffset;

    /** Indices into the level's floor line table, bounding this zone. */
    public final int[] exitLines;
    /**
     * The whole list, as {@code checkotherwalls} walks it.
     *
     * The two passes read it differently: {@code checkwalls} stops at the first
     * negative word, while the second pass skips a {@code -1} and carries on to
     * a {@code -2}. The lists hold about twice as many lines past the first
     * {@code -1} as before it, and those extra lines are <em>not</em> the zone's
     * own boundary -- a third of zone centres fall outside them -- so they are
     * walls nearby that the mover must not pass through rather than the edges of
     * the room. {@link #exitLines} is the boundary; this is what the second
     * collision pass tests against.
     */
    public final int[] allExitLines;
    /**
     * Indices into the level's point table: every point the renderer must
     * transform while drawing this zone. It is <em>not</em> the zone outline --
     * it also covers the points of neighbouring zones seen through portals, so
     * it is routinely much longer than the exit list.
     */
    public final int[] points;
    /** Per-wall graphics entries, read from {@link #TO_LIST_OF_GRAPH}. */
    public final List<GraphEntry> graphics;

    /**
     * One entry of the zone's graphics list: which zone-graph record to use, the
     * index of its precomputed clip list, and a coordinate longword.
     */
    public record GraphEntry(int graphNumber, int clipIndex, int cord) {}

    /**
     * Where this zone's graph entries index into the clip file.
     *
     * The file stores {@code clipIndex} as a flag, not an index: negative means
     * the entry has no clip list. {@code assignclips} in source/master.s
     * overwrites every non-negative one with a running position as it walks the
     * clip file, splitting on {@code -2} markers, so the real index is only
     * known after the whole level has been walked in zone order. Filled in by
     * {@link Level}.
     */
    public final int[] clipAt;

    Zone(BinReader r, int index, int offset, Layout layout) {
        this.index = index;
        this.offset = offset;

        this.floorHeight = r.s32(offset + TO_ZONE_FLOOR);
        this.roofHeight = r.s32(offset + TO_ZONE_ROOF);
        this.upperFloorHeight = r.s32(offset + TO_UPPER_FLOOR);
        this.upperRoofHeight = r.s32(offset + TO_UPPER_ROOF);
        this.waterHeight = r.s32(offset + TO_ZONE_WATER);

        this.brightness = r.s16(offset + TO_ZONE_BRIGHTNESS);
        this.upperBrightness = r.s16(offset + TO_UPPER_BRIGHTNESS);
        this.controlPoint = r.s16(offset + TO_ZONE_CPT);
        this.drawBackdrop = r.s16(offset + TO_BACK);
        this.teleportZone = r.s16(offset + TO_TEL_ZONE);
        this.teleportX = r.s16(offset + TO_TEL_X);
        this.teleportZ = r.s16(offset + TO_TEL_Z);
        this.floorNoise = r.s16(offset + TO_FLOOR_NOISE);
        this.upperFloorNoise = r.s16(offset + TO_UPPER_FLOOR_NOISE);

        this.exitListOffset = offset + r.s16(offset + layout.exitList());
        this.pointsListOffset = offset + r.s16(offset + layout.zonePts());
        this.exitLines = readWordListUntilNegative(r, exitListOffset);
        this.allExitLines = readWordListToMinusTwo(r, exitListOffset);
        this.points = readWordListUntilNegative(r, pointsListOffset);
        this.graphics = readGraphics(r, offset + TO_LIST_OF_GRAPH);
        this.clipAt = new int[this.graphics.size()];
        java.util.Arrays.fill(this.clipAt, -1);
    }

    /** Word list terminated by a negative value, the engine's usual convention. */
    private static int[] readWordListUntilNegative(BinReader r, int off) {
        List<Integer> out = new ArrayList<>();
        while (r.inRange(off, 2)) {
            int v = r.s16(off);
            if (v < 0) {
                break;
            }
            out.add(v);
            off += 2;
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = out.get(i);
        }
        return a;
    }

    /** {@code checkotherwalls}: every line, skipping -1, up to the -2. */
    private static int[] readWordListToMinusTwo(BinReader r, int off) {
        java.util.List<Integer> out = new ArrayList<>();
        for (int guard = 0; guard < 1024 && r.inRange(off, 2); guard++, off += 2) {
            int v = r.s16(off);
            if (v == -2) {
                break;
            }
            if (v >= 0) {
                out.add(v);
            }
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = out.get(i);
        }
        return a;
    }

    /** 8-byte records {graphNumber(w), clipIndex(w), cord(l)} ended by a negative word. */
    private static List<GraphEntry> readGraphics(BinReader r, int off) {
        List<GraphEntry> out = new ArrayList<>();
        while (r.inRange(off, 8)) {
            int graph = r.s16(off);
            if (graph < 0) {
                break;
            }
            out.add(new GraphEntry(graph, r.s16(off + 2), r.s32(off + 4)));
            off += 8;
        }
        return out;
    }

    @Override
    public String toString() {
        return String.format(
                "Zone[%d @%d floor=%d roof=%d (h=%d) upper=%d/%d bright=%d exits=%d pts=%d gfx=%d]",
                index, offset, floorHeight, roofHeight, floorHeight - roofHeight,
                upperFloorHeight, upperRoofHeight,
                brightness, exitLines.length, points.length, graphics.size());
    }
}
