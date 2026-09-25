package ab3d.engine;

import ab3d.data.BinReader;
import ab3d.data.Level;
import ab3d.m68k.M68k;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code DoorRoutine}, transcribed from source/anims.
 *
 * A door is not a model: it is a zone whose roof height the routine rewrites
 * every frame, plus a list of wall records whose bottom edge follows it. Closed
 * means the roof has come down to the floor, so the zone has no volume at all --
 * which is why a level with the routine missing has doorways you cannot walk
 * through and cannot see either. The level file ships them closed.
 *
 * The record is: the two ends of the travel, the current height, the speed, a
 * pointer to the wall record carrying the door's own face, the zone, a condition
 * word, two trigger bytes, then the wall list. A bottom of 999 ends the list.
 */
public final class Doors {

    /** {@code cmp.w #999,d0}: the value that ends the list. */
    private static final int END = 999;

    /** One door, as the record holds it. */
    public static final class Door {
        public int at;              // offset of the record
        public int bottom, top;     // the two ends of the travel
        public int height;          // where it is now
        public int speed;
        public int facePtr;         // the wall record showing the door itself
        public int zone;
        public int conditions;
        /** The two trigger bytes: which mask opens it, and whether it re-closes. */
        public int trigger, closeMode;
        public final List<int[]> walls = new ArrayList<>();  // {line, wall, texture}
        /** True while the routine is driving it open. */
        public boolean opening;

        public boolean closed() {
            return height == bottom;
        }

        public boolean open() {
            return height == top;
        }
    }

    private final Level lv;
    /** {@code move.w #5,Samplenum / move.w #50,Noisevol}. */
    public Sound sound = Sound.SILENT;
    private final BinReader g;
    public final List<Door> doors = new ArrayList<>();

    public Doors(Level level) {
        this.lv = level;
        this.g = level.graphics.data;
        read();
    }

    private void read() {
        int at = lv.graphics.doorDataOffset;
        while (g.inRange(at, 18)) {
            int bottom = g.s16(at);
            if (bottom == END) {
                break;
            }
            Door d = new Door();
            d.at = at;
            d.bottom = bottom;
            d.top = g.s16(at + 2);
            d.height = g.s16(at + 4);
            d.speed = g.s16(at + 6);
            d.facePtr = g.s32(at + 8);
            d.zone = g.s16(at + 12);
            d.conditions = g.s16(at + 14);
            d.trigger = g.u8(at + 16);
            d.closeMode = g.u8(at + 17);

            int p = at + 18;                      // past the two trigger bytes
            while (g.inRange(p, 10) && g.s16(p) >= 0) {
                d.walls.add(new int[]{g.s16(p), g.s32(p + 2), g.s32(p + 6)});
                p += 10;
            }
            doors.add(d);
            at = p + 2;
            if (doors.size() > 256) {
                break;                            // malformed rather than endless
            }
        }
    }

    /**
     * Moves every door and writes the result back into the level data.
     *
     * @param frames  {@code TempFrames}, how many frames have passed
     * @param wanted  zones the caller wants open; the original reads a bit the
     *                player's control code leaves on the door's floor line, and
     *                that flag is not transcribed yet
     */
    /**
     * {@code tstdoortoopen}: which flag bits open a door of each kind.
     *
     * Kind 0 wants the action key as well as the touch, which is why a door that
     * looks like the others does nothing when you walk into it. Kind 4 matches
     * the {@code $8000} the routine writes back every frame, so it opens on any
     * touch at all. The shipped levels use only these two.
     */
    private static int openMask(int kind, boolean spaceTapped) {
        return switch (kind) {
            case 0 -> spaceTapped ? PLAYER1 : 0;      // door0, plus p2's bit
            case 1 -> 0x900;                          // door1: either player
            case 2 -> 0x400;                          // door2
            case 3 -> 0x200;                          // door3
            default -> RESET;                         // door4 and up
        };
    }

    /** {@code move.w #%100000000,wallflags}: the bit player one leaves. */
    public static final int PLAYER1 = 0x100;
    /** The bit {@code DoorRoutine} writes back every frame, so it always matches. */
    private static final int RESET = 0x8000;
    /** {@code move.w #-16,d7} and {@code move.w #4,d7}. */
    private static final int OPEN_SPEED = -16, CLOSE_SPEED = 4;

    /**
     * Moves every door and writes the result back into the level data.
     *
     * The height is speed-driven, not position-driven: touching one of the
     * door's floor lines sets its speed to the opening rate, and reaching the
     * open end sets it to the closing rate unless the second trigger byte says
     * to stay open. The speed lives in the record, so a door keeps moving after
     * the player has stopped touching it.
     *
     * @param frames {@code TempFrames}
     */
    public void update(int frames, boolean spaceTapped) {
        update(frames, spaceTapped, 0);
    }

    /**
     * @param conditions {@code Conditions}, the bits the switches have set
     */
    public void update(int frames, boolean spaceTapped, int conditions) {
        for (Door d : doors) {
            int d0 = d.bottom;
            int d1 = d.top;
            int d3 = d.height;
            int d2 = d.speed;

            // muls TempFrames,d2 / add.w d2,d3
            d3 = M68k.w(d3 + M68k.muls(d2, Math.max(1, frames)));
            d2 = d.speed;

            boolean closed = d0 <= d3;            // sle doorclosed
            if (d0 <= d3) {                       // bgt nolower
                d2 = 0;
            }
            boolean open = d1 >= d3;              // sge dooropen
            if (d1 >= d3) {                       // blt noraise
                d2 = 0;
                d3 = d1;
            }

            d.height = d3;
            g.setS16(d.at + 4, d3);               // move.w d3,(a0)+

            // and.w Conditions,d2 / cmp.w -2(a0),d2 / beq satisfied: every bit
            // the record asks for must already be set, or the door ignores its
            // trigger entirely. Nine doors in level_a wait on one, so skipping
            // this makes locked doors open on a touch.
            boolean satisfied = (d.conditions & conditions) == d.conditions;

            // The trigger mask, and the speed a match would set
            int mask;
            int newSpeed;
            if (open) {
                // tstdoortoclose
                mask = d.closeMode == 0 ? RESET : 0;
                newSpeed = CLOSE_SPEED;
            } else if (closed) {
                mask = openMask(d.trigger, spaceTapped);
                newSpeed = OPEN_SPEED;
            } else {
                mask = 0;                         // mid-travel: move.w #0,d1
                newSpeed = 0;
            }
            if (!satisfied) {
                mask = 0;                         // dothesimplething
            }

            // doorwalls: read the flags, reset them, and match
            boolean hit = false;
            for (int[] w : d.walls) {
                int line = w[0];
                if (line < 0 || line >= lv.floorLines.length) {
                    continue;
                }
                int flagAt = lv.floorLine(line).offset + 14;
                int flags = lv.data.s16(flagAt);
                // The unsatisfied path clears the word instead of writing the
                // always-matching bit: move.w #0,14(a4) against move.w #$8000
                lv.data.setS16(flagAt, satisfied ? RESET : 0);
                if ((flags & mask) != 0) {
                    hit = true;
                }
            }
            if (hit) {
                d2 = newSpeed;                    // move.w d7,(a5)
            }
            d.speed = d2;
            g.setS16(d.at + 6, d2);

            apply(d, d3);
        }
    }

    /**
     * {@code asr.w #2,d3 / muls #256,d3 / move.l d3,6(a1)} and the wall patches.
     *
     * The roof is the height shifted down two and scaled by 256, the door's own
     * face takes the height as a texture offset, and every wall in the list has
     * its bottom edge set to the same roof so the frame around the door follows
     * it down.
     */
    private void apply(Door d, int height) {
        int d3 = M68k.asrW(height, 2);
        int d0 = M68k.w(M68k.aslW(d3, 2));
        if (d.facePtr > 0) {
            g.setS16(d.facePtr + 2, d0);          // move.w d0,2(a1)
        }
        int roof = M68k.muls(d3, 256);
        if (d.zone >= 0 && d.zone < lv.zones.length) {
            lv.data.setS32(lv.zone(d.zone).offset + 6, roof);   // move.l d3,6(a1)
            lv.zone(d.zone).roofHeight = roof;
        }
        int texOff = M68k.w(-d0) & 255;           // neg.w d0 / and.w #255,d0
        for (int[] w : d.walls) {
            if (w[1] > 0) {
                g.setS32(w[1] + 10, w[2] + texOff);   // move.l a2,10(a1)
                g.setS32(w[1] + 24, roof);            // move.l d3,24(a1)
            }
        }
    }
}
