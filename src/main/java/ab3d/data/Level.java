package ab3d.data;

import java.io.IOException;

/**
 * The level geometry file ({@code <name>.bin}).
 *
 * Two header layouts exist in the repository. newsource/loadlevel.s reads every
 * count and pointer at the offset newsource/levelformat documents <em>plus 6</em>,
 * giving a 54-byte header; lev1, lev2 and tstlev use that. The older editor
 * levels (jacklev, mikelev, oldlev, newlev, clev) use the documented offsets
 * with no shift and a 48-byte header. The two are told apart by trying both and
 * keeping the one whose pointers actually land inside the file -- the wrong
 * shift misreads them by orders of magnitude, so there is no ambiguity.
 */
public final class Level {

    // Offsets from newsource/levelformat, before the per-file shift is applied.
    private static final int H_START_X = 0;
    private static final int H_START_Z = 2;
    private static final int H_START_ZONE = 4;
    private static final int H_NUM_CONTROL_PTS = 6;
    private static final int H_NUM_POINTS = 8;
    private static final int H_NUM_ZONES = 10;
    private static final int H_NUM_FLOORLINES = 12;
    private static final int H_NUM_OBJECT_PTS = 14;
    private static final int H_PTR_POINTS = 16;
    private static final int H_PTR_FLOORLINES = 20;
    private static final int H_PTR_OBJECTS = 24;
    private static final int H_PTR_PLAYERSHOTS = 28;
    private static final int H_PTR_ENEMYSHOTS = 32;
    private static final int H_PTR_OBJECTPTS = 36;
    private static final int H_PTR_PLR1_OBJ = 40;
    private static final int H_PTR_PLR2_OBJ = 44;

    /** Shift applied to every field from NUM_CONTROL_PTS on: 6 or 0. */
    public final int headerShift;

    /** Which zone record layout this level turned out to use. */
    public Zone.Layout zoneLayout;

    public final String name;
    public final BinReader data;
    public final LevelGraphics graphics;
    public final BinReader clips;

    public final int startX, startZ, startZone;
    public final int numControlPoints, numPoints, numFloorLines, numObjectPoints;

    public final int ptrPoints, ptrFloorLines, ptrObjects;
    public final int ptrPlayerShots, ptrEnemyShots, ptrObjectPoints;
    public final int ptrPlr1Obj, ptrPlr2Obj;

    /** Point coordinates, interleaved x,z. */
    public final int[] pointX, pointZ;
    /** Per-point brightness, stored right after the point table. */
    public final int[] pointBright;
    /** The same for the upper storey, word 2 of each four-byte entry. */
    public final int[] pointBrightUpper;
    /** The same words untouched, so the animation byte survives to the frame. */
    public final int[] pointBrightRaw, pointBrightUpperRaw;
    /** Any animation selector seen in a high byte; zero means none is used. */
    public int pointBrightAnim;

    public final FloorLine[] floorLines;
    public final Zone[] zones;

    /** The level's object list, ended by a negative point index. */
    public final java.util.List<GameObject> objects = new java.util.ArrayList<>();

    /** Object positions, interleaved x,z, indexed by an object's point index. */
    public final int[] objectPointX, objectPointZ;

    public static Level load(GameData game, String name) throws IOException {
        GameData.LevelFiles files = game.levelFiles(name);
        return new Level(name,
                BinReader.of(files.bin()),
                new LevelGraphics(BinReader.of(files.graph())),
                BinReader.of(files.clips()));
    }

    public Level(String name, BinReader data, LevelGraphics graphics, BinReader clips) {
        this.name = name;
        this.data = data;
        this.graphics = graphics;
        this.clips = clips;

        this.headerShift = detectShift(data);
        int s = headerShift;

        this.startX = data.s16(H_START_X);
        this.startZ = data.s16(H_START_Z);
        this.startZone = data.s16(H_START_ZONE);

        this.numControlPoints = data.s16(H_NUM_CONTROL_PTS + s);
        this.numPoints = data.s16(H_NUM_POINTS + s);
        this.numFloorLines = data.s16(H_NUM_FLOORLINES + s);
        this.numObjectPoints = data.s16(H_NUM_OBJECT_PTS + s);

        this.ptrPoints = data.s32(H_PTR_POINTS + s);
        this.ptrFloorLines = data.s32(H_PTR_FLOORLINES + s);
        this.ptrObjects = data.s32(H_PTR_OBJECTS + s);
        this.ptrPlayerShots = data.s32(H_PTR_PLAYERSHOTS + s);
        this.ptrEnemyShots = data.s32(H_PTR_ENEMYSHOTS + s);
        this.ptrObjectPoints = data.s32(H_PTR_OBJECTPTS + s);
        this.ptrPlr1Obj = data.s32(H_PTR_PLR1_OBJ + s);
        this.ptrPlr2Obj = data.s32(H_PTR_PLR2_OBJ + s);

        this.pointX = new int[numPoints];
        this.pointZ = new int[numPoints];
        for (int i = 0; i < numPoints; i++) {
            pointX[i] = data.s16(ptrPoints + i * 4);
            pointZ[i] = data.s16(ptrPoints + i * 4 + 2);
        }

        // jg.s: lea 4(a2,numPoints*4),a2 / move.l a2,PointBrights.
        // Four bytes a point, not two: word 0 is the lower storey and word 2 the
        // upper, and the routine that copies them into CurrentPointBrights ends
        // with ext.w, so the value is a signed byte and the high byte selects an
        // animation rather than being part of the number.
        int ptrBrights = ptrPoints + 4 + numPoints * 4;
        this.pointBright = new int[numPoints];
        this.pointBrightUpper = new int[numPoints];
        this.pointBrightRaw = new int[numPoints];
        this.pointBrightUpperRaw = new int[numPoints];
        for (int i = 0; i < numPoints && data.inRange(ptrBrights + i * 4, 4); i++) {
            pointBrightRaw[i] = data.s16(ptrBrights + i * 4);
            pointBrightUpperRaw[i] = data.s16(ptrBrights + i * 4 + 2);
            pointBright[i] = (byte) data.s16(ptrBrights + i * 4);
            pointBrightUpper[i] = (byte) data.s16(ptrBrights + i * 4 + 2);
            pointBrightAnim |= (data.s16(ptrBrights + i * 4) >> 8) & 0xff;
            pointBrightAnim |= (data.s16(ptrBrights + i * 4 + 2) >> 8) & 0xff;
        }

        // The zone table in the graphics file says how many zones there are and
        // where each one starts.
        for (int i = 0; i < graphics.numZones(); i++) {
            int off = graphics.zoneOffset(i);
            if (!data.inRange(off, Zone.TO_LIST_OF_GRAPH + 8)) {
                throw new IllegalArgumentException("Level '" + name + "': zone " + i
                        + " points outside the file (offset " + off + " of " + data.size()
                        + "). This is an older editor level whose zone layout the loader"
                        + " does not decode yet.");
            }
        }

        // Two zone record layouts exist. Try both and keep whichever gives most
        // zones a bounding list that is both long enough to close a room and
        // entirely within the floor line array.
        Zone[] best = null;
        int bestScore = -1;
        for (Zone.Layout layout : new Zone.Layout[] { Zone.Layout.SHIPPED, Zone.Layout.DEV }) {
            Zone[] candidate = new Zone[graphics.numZones()];
            int border = Integer.MAX_VALUE;
            for (int i = 0; i < candidate.length; i++) {
                candidate[i] = new Zone(data, i, graphics.zoneOffset(i), layout);
                border = Math.min(border, candidate[i].exitListOffset);
            }
            int lines = Math.max(0, border - ptrFloorLines) / FloorLine.SIZE;
            int score = 0;
            for (Zone z : candidate) {
                boolean ok = z.exitLines.length >= 3;
                for (int e : z.exitLines) {
                    if (e >= lines) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                this.zoneLayout = layout;
            }
        }
        this.zones = best;

        // The floor line array runs from its pointer up to the first zone border
        // list, which sits just before the earliest zone record; integer division
        // absorbs the odd word of padding in between.
        int firstBorderList = Integer.MAX_VALUE;
        for (Zone z : zones) {
            firstBorderList = Math.min(firstBorderList, z.exitListOffset);
        }

        int floorLineBytes = Math.max(0, firstBorderList - ptrFloorLines);
        int count = floorLineBytes / FloorLine.SIZE;
        // Portals come in pairs, so the encoding that makes crossings reciprocal
        // is the right one.
        FloorLine[] bestLines = null;
        int bestPaired = -1;
        for (FloorLine.Encoding enc : FloorLine.Encoding.values()) {
            FloorLine[] candidate = new FloorLine[count];
            for (int i = 0; i < count; i++) {
                candidate[i] = new FloorLine(data, ptrFloorLines + i * FloorLine.SIZE, enc);
            }
            int paired = 0;
            for (Zone z : zones) {
                for (int e : z.exitLines) {
                    if (e >= count) {
                        continue;
                    }
                    FloorLine fl = candidate[e];
                    if (fl.isSolid() || fl.toZone >= zones.length) {
                        continue;
                    }
                    for (int e2 : zones[fl.toZone].exitLines) {
                        if (e2 < count && candidate[e2].toZone == z.index) {
                            paired++;
                            break;
                        }
                    }
                }
            }
            if (paired > bestPaired) {
                bestPaired = paired;
                bestLines = candidate;
                this.floorLineEncoding = enc;
            }
        }
        this.floorLines = bestLines;

        // Object points are 8-byte entries starting right after the header
        int objPts = Math.max(0, numObjectPoints + 1);
        this.objectPointX = new int[objPts];
        this.objectPointZ = new int[objPts];
        for (int i = 0; i < objPts && data.inRange(ptrObjectPoints + i * 8, 8); i++) {
            objectPointX[i] = data.s32(ptrObjectPoints + i * 8) >> 16;
            objectPointZ[i] = data.s32(ptrObjectPoints + i * 8 + 4) >> 16;
        }

        for (int i = 0; data.inRange(ptrObjects + i * GameObject.SIZE, GameObject.SIZE); i++) {
            GameObject o = new GameObject(data, i, ptrObjects + i * GameObject.SIZE);
            if (o.pointIndex < 0) {
                break;
            }
            objects.add(o);
        }
        assignClips();
        assignObjectZones();
    }

    /** Word index in {@link #clips} where the connect table starts. */
    public int connectTableAt;

    /**
     * {@code assignclips}, transcribed from source/master.s.
     *
     * The clip file is one list per graph entry, each ended by a {@code -2}
     * marker, laid out in zone order and then graph-entry order. The word at
     * offset 2 of an entry is a flag in the file -- negative for an entry with
     * no list -- and the loader overwrites every other one with the position it
     * has reached. Whatever the walk has not consumed when the last zone is done
     * is the connect table, which {@code NEWsetlclip} and {@code NEWsetrclip}
     * then index by floor line.
     */
    private void assignClips() {
        int at = 0;                                   // moveq #0,d0, in words
        for (Zone zone : zones) {                     // dbra d7,assignclips
            for (int i = 0; i < zone.graphics.size(); i++) {
                if (zone.graphics.get(i).clipIndex() < 0) {
                    continue;                         // tst.w 2(a3) / blt thisonenull
                }
                zone.clipAt[i] = at;                  // move.w d1,2(a3)
                while (clips.inRange(at * 2, 2) && clips.s16(at * 2) != -2) {
                    at++;                             // findnextclip
                }
                at++;                                 // addq.l #2,d0
            }
        }
        connectTableAt = at;                          // lea (a2,d0.l),a2
    }

    /**
     * Fills in the zone of objects the files leave at -1.
     *
     * The control code copies the word at offset 12 into {@code GraphicRoom}
     * whenever an object updates, so that word is the answer and no geometry is
     * needed. The inside-every-bounding-line test is kept only for a record that
     * gives neither; on the shipped levels it agreed with offset 12 every time,
     * which is why it was safe to rely on before and is redundant now.
     */
    private void assignObjectZones() {
        for (GameObject o : objects) {
            // Offset 12 wins outright. In a shipped level the GraphicRoom word
            // at offset 26 holds only -1 or 0 -- two values across the whole
            // list -- while offset 12 spreads across dozens of zones, and the
            // control code overwrites the first from the second as soon as an
            // object updates. Trusting offset 26 pins about half the objects to
            // zone 0, where they appear only when that one zone is in view.
            if (o.inPlayZone >= 0 && o.inPlayZone < zones.length) {
                o.zone = o.inPlayZone;      // move.w 12(a0),GraphicRoom(a0)
                continue;
            }
            if (o.zone >= 0 && o.zone < zones.length) {
                continue;
            }
            if (o.pointIndex < 0 || o.pointIndex >= objectPointX.length) {
                continue;
            }
            int x = objectPointX[o.pointIndex];
            int z = objectPointZ[o.pointIndex];
            for (Zone zone : zones) {
                boolean inside = zone.exitLines.length > 0;
                for (int e : zone.exitLines) {
                    if (e >= floorLines.length || floorLines[e].side(x, z) < 0) {
                        inside = false;
                        break;
                    }
                }
                if (inside) {
                    o.zone = zone.index;
                    break;
                }
            }
        }
    }

    /** Which floor line zone encoding this level turned out to use. */
    public FloorLine.Encoding floorLineEncoding;

    /** Picks the header shift whose pointers land inside the file. */
    private static int detectShift(BinReader d) {
        for (int shift : new int[] { 6, 0 }) {
            if (plausible(d, shift)) {
                return shift;
            }
        }
        throw new IllegalArgumentException("Unrecognised level header layout");
    }

    private static boolean plausible(BinReader d, int shift) {
        int size = d.size();
        int[] pointers = {
            d.s32(H_PTR_POINTS + shift), d.s32(H_PTR_FLOORLINES + shift),
            d.s32(H_PTR_OBJECTS + shift), d.s32(H_PTR_OBJECTPTS + shift),
            d.s32(H_PTR_PLR1_OBJ + shift), d.s32(H_PTR_PLR2_OBJ + shift),
        };
        for (int p : pointers) {
            if (p <= 0 || p >= size) {
                return false;
            }
        }
        int numPoints = d.s16(H_NUM_POINTS + shift);
        return numPoints > 0 && d.inRange(d.s32(H_PTR_POINTS + shift) + numPoints * 4, 0);
    }

    public Zone zone(int index) {
        return zones[index];
    }

    public FloorLine floorLine(int index) {
        return floorLines[index];
    }
}
