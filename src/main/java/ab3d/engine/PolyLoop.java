package ab3d.engine;

import ab3d.data.BinReader;

/**
 * {@code dothisroom} / {@code polyloop}, transcribed from source/master.s.
 *
 * The zone's drawing stream is a run of tagged records, and each routine the
 * dispatch calls advances {@code a0} by exactly what it reads. That is the point
 * of transcribing it rather than parsing ahead: a parser has to be told how long
 * a record is, while the dispatch derives it from the reads themselves.
 *
 * Record sizes, taken from the routines rather than guessed:
 * <ul>
 *   <li>a wall is 28 bytes, the fields {@code itsawalldraw} reads;</li>
 *   <li>a floor, roof or water surface is {@code 2n + 14}, where n is the word
 *       at offset 2. Both paths of {@code itsafloordraw} agree on that: the
 *       early-out skips {@code 2 + 2 + 2n + 10}, and the drawing path consumes
 *       the corner list with {@code dbra} over n, steps one more word at
 *       {@code pastsides}, then reads scale, tile and brightness;</li>
 *   <li>an object is 2 bytes, the one word {@code ObjDraw} reads.</li>
 * </ul>
 */
public final class PolyLoop {

    // Type tags, in the order the dispatch compares them
    public static final int T_WALL = 0;
    public static final int T_FLOOR = 1;
    public static final int T_ROOF = 2;
    public static final int T_SET_CLIP = 3;
    public static final int T_OBJECT = 4;
    public static final int T_ARC = 5;
    public static final int T_LIGHT_BEAM = 6;
    public static final int T_WATER = 7;
    public static final int T_CHUNKY_FLOOR = 8;   // 8 and 9
    public static final int T_BUMPY_FLOOR = 10;   // 10 and 11
    public static final int T_BACKDROP = 12;
    public static final int T_SEE_WALL = 13;

    /** Bytes in a wall record, from the reads in {@code itsawalldraw}. */
    public static final int WALL_BYTES = 28;

    /** What a run of the dispatch did, for checking the transcription. */
    public static final class Result {
        public int walls, surfaces, objects, setClips, backdrops, others;
        /** Offset just past the terminator, or where the walk gave up. */
        public int end;
        /** True when the stream ended on its negative type word. */
        public boolean terminated;
        /** The tag that stopped the walk, when it was not a terminator. */
        public int badTag = Integer.MIN_VALUE;
    }

    /** Anything the dispatch can hand a record to. */
    public interface Sink {
        default void wall(BinReader g, int at, boolean seeThrough) { }

        default void surface(BinReader g, int at, int type) { }

        default void object(BinReader g, int at) { }
    }

    private PolyLoop() {
    }

    /**
     * Walks one zone's stream from {@code offset}, which points at the zone
     * number word that {@code dothisroom} reads first.
     */
    public static Result run(BinReader g, int offset, int limit, Sink sink) {
        Result r = new Result();
        int a0 = offset + 2;                 // move.w (a0)+,d0 : the zone number

        while (a0 + 2 <= limit) {
            int d0 = g.s16(a0);              // polyloop: move.w (a0)+,d0
            a0 += 2;
            if (d0 < 0) {                    // blt jumpoutofloop
                r.terminated = true;
                break;
            }
            switch (d0) {
                case T_WALL, T_SEE_WALL -> {
                    if (a0 + WALL_BYTES > limit) {
                        r.badTag = d0;
                        a0 = limit;
                        break;
                    }
                    sink.wall(g, a0, d0 == T_SEE_WALL);
                    a0 += WALL_BYTES;
                    r.walls++;
                }
                case T_FLOOR, T_ROOF, T_WATER,
                     8, 9, 10, 11 -> {
                    if (a0 + 4 > limit) {
                        r.badTag = d0;
                        a0 = limit;
                        break;
                    }
                    int n = g.s16(a0 + 2);   // the word itsafloordraw calls "sides"
                    int size = 2 * n + 14;
                    if (n < 0 || a0 + size > limit) {
                        r.badTag = d0;
                        a0 = limit;
                        break;
                    }
                    sink.surface(g, a0, d0);
                    a0 += size;
                    r.surfaces++;
                }
                case T_OBJECT -> {           // ObjDraw reads one word
                    sink.object(g, a0);
                    a0 += 2;
                    r.objects++;
                }
                case T_SET_CLIP -> r.setClips++;      // itsasetclip: bra polyloop
                case T_BACKDROP -> r.backdrops++;     // putinbackdrop reads nothing
                case T_ARC, T_LIGHT_BEAM -> {
                    // CurveDraw and LightDraw are not reached by the shipped data;
                    // stop rather than advance by a size we have not derived.
                    r.badTag = d0;
                    a0 = limit;
                }
                default -> {
                    r.badTag = d0;
                    a0 = limit;
                }
            }
        }
        r.end = a0;
        return r;
    }
}
