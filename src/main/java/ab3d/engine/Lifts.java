package ab3d.engine;

import ab3d.data.BinReader;
import ab3d.data.FloorLine;
import ab3d.data.Level;
import ab3d.m68k.M68k;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code LiftRoutine}, transcribed from source/anims.
 *
 * A lift is built exactly like a door -- same record, same speed-driven height,
 * same flag on a floor line to trigger it -- and differs in four ways that all
 * follow from one: it moves the <em>floor</em> rather than the roof.
 *
 * So it writes {@code ToZoneFloor} instead of {@code ToZoneRoof}, its walls take
 * the height at their top edge rather than their bottom, it clamps at the bottom
 * of its travel as well as the top (a door only needs the top), and it notices
 * whether the player is standing on it: that turns the trigger mask into one
 * that always matches, which is how pressing the action key while aboard moves
 * the lift rather than merely calling it.
 */
public final class Lifts {

    /** {@code cmp.w #999,d0}: the value that ends the list. */
    private static final int END = 999;
    /** {@code move.w #-4,d7} and {@code move.w #4,d7}. */
    private static final int RAISE_SPEED = -4, LOWER_SPEED = 4;
    /** The bit the routine writes back every frame, so it always matches. */
    private static final int RESET = 0x8000;

    /** One lift, as the record holds it. */
    public static final class Lift {
        public int at;
        public int bottom, top;
        public int height;
        public int speed;
        public int facePtr;
        public int zone;
        public int conditions;
        /** The two trigger bytes: raising and lowering are chosen separately. */
        public int raiseKind, lowerKind;
        public final List<int[]> walls = new ArrayList<>();

        public boolean atBottom() {
            return height == bottom;
        }

        public boolean atTop() {
            return height == top;
        }
    }

    private final Level lv;
    /** {@code move.w #5,Samplenum / move.w #50,Noisevol}. */
    public Sound sound = Sound.SILENT;
    private final BinReader g;
    public final List<Lift> lifts = new ArrayList<>();

    public Lifts(Level level) {
        this.lv = level;
        this.g = level.graphics.data;
        read();
    }

    private void read() {
        int at = lv.graphics.liftDataOffset;
        while (g.inRange(at, 18)) {
            int bottom = g.s16(at);
            if (bottom == END) {
                break;
            }
            Lift l = new Lift();
            l.at = at;
            l.bottom = bottom;
            l.top = g.s16(at + 2);
            l.height = g.s16(at + 4);
            l.speed = g.s16(at + 6);
            l.facePtr = g.s32(at + 8);
            l.zone = g.s16(at + 12);
            l.conditions = g.s16(at + 14);
            l.raiseKind = g.u8(at + 16);
            l.lowerKind = g.u8(at + 17);

            int p = at + 18;
            while (g.inRange(p, 10) && g.s16(p) >= 0) {
                l.walls.add(new int[]{g.s16(p), g.s32(p + 2), g.s32(p + 6)});
                p += 10;
            }
            lifts.add(l);
            at = p + 2;
            if (lifts.size() > 64) {
                break;
            }
        }
    }

    /**
     * Moves every lift by one frame's worth.
     *
     * @param frames      {@code TempFrames}
     * @param viewerZone  which zone the player is in, for {@code PLR1_stoodonlift}
     * @param spaceTapped {@code p1_spctap}
     */
    public void update(int frames, int viewerZone, boolean spaceTapped) {
        update(frames, viewerZone, spaceTapped, 0);
    }

    /** @param conditions {@code Conditions}, as the door routine tests it */
    public void update(int frames, int viewerZone, boolean spaceTapped,
                       int conditions) {
        for (Lift l : lifts) {
            int d0 = l.bottom;
            int d1 = l.top;
            int d3 = M68k.w(l.height + M68k.muls(l.speed, Math.max(1, frames)));
            int d2 = l.speed;

            // cmp.w d3,d0 / sle liftatbot / bgt .nolower: a lift clamps here too
            boolean atBottom = d0 <= d3;
            if (d0 <= d3) {
                d2 = 0;
                d3 = d0;                          // move.w d0,d3
            }
            boolean atTop = d1 >= d3;
            if (d1 >= d3) {
                d2 = 0;
                d3 = d1;
            }

            l.height = d3;
            g.setS16(l.at + 4, d3);

            boolean aboard = l.zone == viewerZone;   // seq PLR1_stoodonlift
            boolean satisfied = (l.conditions & conditions) == l.conditions;

            int mask;
            int newSpeed;
            if (atTop) {
                mask = triggerMask(l.lowerKind, spaceTapped, aboard);
                newSpeed = LOWER_SPEED;
            } else if (atBottom) {
                mask = triggerMask(l.raiseKind, spaceTapped, aboard);
                newSpeed = RAISE_SPEED;
            } else {
                mask = 0;                         // mid-travel: move.w #0,d1
                newSpeed = 0;
            }
            if (!satisfied) {
                mask = 0;
            }

            boolean hit = false;
            for (int[] w : l.walls) {
                int line = w[0];
                if (line < 0 || line >= lv.floorLines.length) {
                    continue;
                }
                int flagAt = lv.floorLine(line).offset + 14;
                int flags = lv.data.s16(flagAt);
                lv.data.setS16(flagAt, satisfied ? RESET : 0);
                if ((flags & mask) != 0) {
                    hit = true;
                }
            }
            if (hit) {
                d2 = newSpeed;
            }
            l.speed = d2;
            g.setS16(l.at + 6, d2);

            apply(l, d3);
        }
    }

    /**
     * {@code rlift0} and {@code lift0}: which flag bits move a lift of each kind.
     *
     * Standing on it replaces the mask with the one the routine writes back every
     * frame, so the test always succeeds -- the player aboard needs only to press
     * the key, while calling it from outside means touching one of its lines.
     */
    private static int triggerMask(int kind, boolean spaceTapped, boolean aboard) {
        if (kind == 0) {
            if (!spaceTapped) {
                return 0;
            }
            return aboard ? RESET : Doors.PLAYER1;
        }
        return kind >= 4 ? RESET : 0x900;
    }

    /**
     * {@code asr.w #2,d3 / muls #256,d3 / move.l d3,2(a1)}.
     *
     * Offset 2 is {@code ToZoneFloor}; the door routine writes offset 6 instead.
     * The lift's walls take the height at offset 20 of their record, which is the
     * top edge, where a door's take offset 24, the bottom.
     */
    private void apply(Lift l, int height) {
        int d3 = M68k.asrW(height, 2);
        int d0 = M68k.w(M68k.aslW(d3, 2));
        if (l.facePtr > 0) {
            g.setS16(l.facePtr + 2, d0);
        }
        int floor = M68k.muls(d3, 256);
        if (l.zone >= 0 && l.zone < lv.zones.length) {
            lv.data.setS32(lv.zone(l.zone).offset + 2, floor);
            lv.zone(l.zone).floorHeight = floor;
        }
        int texOff = M68k.w(-d0) & 255;
        for (int[] w : l.walls) {
            if (w[1] > 0) {
                g.setS32(w[1] + 10, w[2] + texOff);
                g.setS32(w[1] + 20, floor);       // move.l d3,20(a1)
            }
        }
    }
}
