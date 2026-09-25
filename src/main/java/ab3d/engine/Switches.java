package ab3d.engine;

import ab3d.data.BinReader;
import ab3d.data.Level;
import ab3d.m68k.M68k;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code SwitchRoutine}, transcribed from source/anims.
 *
 * Eight switches a level, fourteen bytes each, and each one owns a bit of
 * {@code Conditions} -- the bit four above its own index, so the eight of them
 * cover bits four to eleven. That is what the locked doors are waiting on.
 *
 * A switch is pressed by standing within sixty units of it with the action key,
 * where "it" is the midpoint of two consecutive level points. Pressing toggles
 * the bit and the graphic together: the wall record it names has its texture set
 * to eleven and one bit of its tile flipped, which is how the lever is seen up
 * or down.
 *
 * Some switches let go on their own. When the record's auto byte is set, a
 * counter runs down by four times the frames elapsed, and on reaching zero the
 * bit is cleared and the graphic returns -- so those doors stay open only while
 * the timer lasts.
 *
 * Bits zero to three are not reachable from here. Doors in the shipped levels
 * ask for those too, and they must come from somewhere else, most likely the
 * keys the pickup sheet carries.
 */
public final class Switches {

    /** {@code move.w #7,d0}: eight of them, walked with a dbra. */
    public static final int COUNT = 8;
    /** {@code adda.w #14,a0}. */
    private static final int RECORD = 14;
    /** {@code cmp.l #60*60,d4}: how close the player must stand. */
    private static final int REACH = 60;
    /** {@code addq #4,d3}: the first bit of Conditions a switch owns. */
    public static final int FIRST_BIT = 4;
    /** {@code move.w #11,4(a3)}: the texture a pressed switch takes. */
    private static final int SWITCH_TEXTURE = 11;
    /** {@code and.w #%00000111100,d3} and {@code or.w #2,d3}. */
    private static final int TILE_KEEP = 0x3c, TILE_ON = 2;

    /** One switch, as the record holds it. */
    public static final class Switch {
        public int at;
        /** Negative in the first word means there is no switch here. */
        public int present;
        /** Whether it lets go on its own, and the counter that does it. */
        public boolean auto;
        public int timer;
        /** The point pair it sits between. */
        public int point;
        /** The graphics record whose tile and texture it changes. */
        public int facePtr;
        /** Whether it is on. */
        public boolean on;
        /** Which bit of {@code Conditions} it owns. */
        public int bit;
    }

    private final Level lv;
    /** {@code move.w #10,Samplenum / move.w #50,Noisevol}. */
    public Sound sound = Sound.SILENT;
    private final BinReader g;
    public final List<Switch> switches = new ArrayList<>();

    public Switches(Level level) {
        this.lv = level;
        this.g = level.graphics.data;
        int at = lv.graphics.switchDataOffset;
        for (int i = 0; i < COUNT; i++, at += RECORD) {
            if (!g.inRange(at, RECORD)) {
                break;
            }
            Switch sw = new Switch();
            sw.at = at;
            sw.present = g.s16(at);
            sw.auto = g.u8(at + 2) != 0;
            sw.timer = g.u8(at + 3);
            sw.point = g.s16(at + 4);
            sw.facePtr = g.s32(at + 6);
            sw.on = g.u8(at + 10) != 0;
            sw.bit = FIRST_BIT + i;
            switches.add(sw);
        }
    }

    /**
     * Runs every switch for one frame.
     *
     * @param conditions the current {@code Conditions}
     * @return what it becomes
     */
    public int update(int conditions, int frames, int px, int pz, boolean spaceTapped) {
        for (Switch sw : switches) {
            if (sw.present < 0) {
                continue;                          // blt .NotCloseEnough
            }

            if (spaceTapped && within(sw, px, pz)) {
                // not.b 10(a0): the press toggles the state, the bit and the tile
                sw.on = !sw.on;
                paint(sw);
                sound.play(Sound.SWITCH, 0, 0, 50, 0xf8, false);
                conditions ^= 1 << sw.bit;         // bchg d3,d4
                sw.timer = 0;                      // move.b #0,3(a0)
                g.setS16(sw.at + 10, sw.on ? 0xff : 0);
                continue;
            }

            // The auto-release, at the top of the routine
            if (!sw.auto || !sw.on) {
                continue;                          // tst.b 2(a0) / tst.b 10(a0)
            }
            int step = M68k.w(Math.max(1, frames) * 4);   // add.w d1,d1 twice
            sw.timer = (sw.timer - step) & 0xff;   // sub.b d1,3(a0)
            g.setS16(sw.at + 2, (sw.auto ? 0x100 : 0) | (sw.timer & 0xff));
            if (sw.timer != 0) {
                continue;                          // bne nobutt
            }
            sw.on = false;                         // move.b #0,10(a0)
            paint(sw);
            conditions &= ~(1 << sw.bit);          // bclr d3,d4
            g.setS16(sw.at + 10, 0);
        }
        return conditions;
    }

    /**
     * {@code cmp.l #60*60,d4}: the player against the switch's own position.
     *
     * The switch sits at the midpoint of two consecutive points, and the test is
     * on the squared distance so no root is taken.
     */
    private boolean within(Switch sw, int px, int pz) {
        int i = sw.point;
        if (i < 0 || i + 1 >= lv.numPoints) {
            return false;
        }
        int mx = M68k.asrW(M68k.w(lv.pointX[i] + lv.pointX[i + 1]), 1);
        int mz = M68k.asrW(M68k.w(lv.pointZ[i] + lv.pointZ[i + 1]), 1);
        long dx = M68k.w(mx - px);
        long dz = M68k.w(mz - pz);
        return dx * dx + dz * dz < (long) REACH * REACH;
    }

    /** The lever's own graphic: texture eleven, and one bit of the tile. */
    private void paint(Switch sw) {
        if (sw.facePtr <= 0 || !g.inRange(sw.facePtr, 6)) {
            return;
        }
        g.setS16(sw.facePtr + 4, SWITCH_TEXTURE);
        int tile = g.s16(sw.facePtr) & TILE_KEEP;
        g.setS16(sw.facePtr, sw.on ? tile | TILE_ON : tile);
    }
}
