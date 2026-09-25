package ab3d.data;

/**
 * The level graphics file ({@code <name>.graph.bin}).
 *
 * Layout, from newsource/levelformat and the pointer fix-up in loadlevel.s:
 *
 * <pre>
 *   0  (l) offset to door data
 *   4  (l) offset to lift data
 *   8  (l) offset to switch data
 *  12  (l) offset to the zone-graph offset table
 *  16  (l * numZones) offset of each zone record inside the level .bin
 * </pre>
 *
 * The zone-graph offset table itself holds <em>two</em> longs per zone: the
 * drawing stream for the zone's lower storey and the one for its upper storey,
 * which the {@code DOUPPER} flag in the polyloop dispatch selects between. Zones
 * without a second storey carry 0 there. lev1 confirms the stride: the table
 * runs from 760 to 2248, which is exactly 186 zones times 8 bytes.
 *
 * All offsets are relative to the start of this file, except the zone offsets
 * which point into the level geometry file.
 */
public final class LevelGraphics {

    private static final int ZONE_TABLE = 16;

    public final BinReader data;
    public final int doorDataOffset;
    public final int liftDataOffset;
    public final int switchDataOffset;
    public final int zoneGraphOffsetsOffset;

    private final int[] zoneOffsets;
    private final ZoneGraph[] lowerGraphs;
    private final ZoneGraph[] upperGraphs;

    public LevelGraphics(BinReader data) {
        this.data = data;
        this.doorDataOffset = data.s32(0);
        this.liftDataOffset = data.s32(4);
        this.switchDataOffset = data.s32(8);
        this.zoneGraphOffsetsOffset = data.s32(12);

        // The zone table runs from offset 16 up to the zone-graph offset table.
        int count = Math.max(0, (zoneGraphOffsetsOffset - ZONE_TABLE) / 4);
        this.zoneOffsets = new int[count];
        for (int i = 0; i < count; i++) {
            zoneOffsets[i] = data.s32(ZONE_TABLE + i * 4);
        }

        // Streams are packed back to back, so each one ends where the next
        // begins. Collecting every offset first gives an exact bound per stream
        // instead of letting a parse run on into its neighbour.
        java.util.TreeSet<Integer> starts = new java.util.TreeSet<>();
        for (int i = 0; i < count; i++) {
            for (int half = 0; half < 2; half++) {
                int o = data.s32(zoneGraphOffsetsOffset + i * 8 + half * 4);
                if (o > 0 && data.inRange(o, 4)) {
                    starts.add(o);
                }
            }
        }
        int areaEnd = Math.min(doorDataOffset > 0 ? doorDataOffset : data.size(), data.size());

        // Wall records come in two sizes. Whichever one is right makes the
        // streams land on their terminator; the wrong one drifts into the data
        // and stops on a byte that is not a tag at all.
        int chosen = ZoneGraph.WALL_RECORD_SHIPPED;
        int chosenTrailing = 10;
        int bestTerminated = -1;
        for (int size : new int[] { ZoneGraph.WALL_RECORD_SHIPPED, ZoneGraph.WALL_RECORD_DEV }) {
            for (int trailing : new int[] { 10, 12, 8, 14, 16 }) {
                int terminated = 0;
                for (int i = 0; i < count; i++) {
                    ZoneGraph g = readGraph(data.s32(zoneGraphOffsetsOffset + i * 8),
                            starts, areaEnd, size, trailing);
                    if (g != null && !g.truncated) {
                        terminated++;
                    }
                }
                if (terminated > bestTerminated) {
                    bestTerminated = terminated;
                    chosen = size;
                    chosenTrailing = trailing;
                }
            }
        }
        this.wallRecordSize = chosen;
        this.surfaceTrailing = chosenTrailing;

        this.lowerGraphs = new ZoneGraph[count];
        this.upperGraphs = new ZoneGraph[count];
        for (int i = 0; i < count; i++) {
            lowerGraphs[i] = readGraph(data.s32(zoneGraphOffsetsOffset + i * 8),
                    starts, areaEnd, chosen, chosenTrailing);
            upperGraphs[i] = readGraph(data.s32(zoneGraphOffsetsOffset + i * 8 + 4),
                    starts, areaEnd, chosen, chosenTrailing);
        }
    }

    /** Wall record size this level turned out to use. */
    public final int wallRecordSize;
    /** Trailing bytes after a surface record's corner list. */
    public final int surfaceTrailing;

    private ZoneGraph readGraph(int offset, java.util.TreeSet<Integer> starts,
                                int areaEnd, int wallSize, int surfaceTrailing) {
        if (offset <= 0 || !data.inRange(offset, 4)) {
            return null;
        }
        Integer next = starts.higher(offset);
        return new ZoneGraph(data, offset,
                next != null ? Math.min(next, areaEnd) : areaEnd, wallSize, surfaceTrailing);
    }

    public int numZones() {
        return zoneOffsets.length;
    }

    /** Byte offset of zone {@code i} inside the level geometry file. */
    public int zoneOffset(int i) {
        return zoneOffsets[i];
    }

    /** Drawing stream for the zone's lower storey, or null when it has none. */
    public ZoneGraph lowerGraph(int zone) {
        return lowerGraphs[zone];
    }

    /** Drawing stream for the zone's upper storey, or null when it has none. */
    public ZoneGraph upperGraph(int zone) {
        return upperGraphs[zone];
    }
}
