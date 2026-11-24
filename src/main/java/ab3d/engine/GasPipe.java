package ab3d.engine;

import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.m68k.M68k;

/**
 * {@code ItsAGasPipe}, from source/anims: thirty-four of them, from the sixth
 * level on.
 *
 * A pipe is not an enemy. It never looks for the player and cannot be hurt --
 * nothing in the routine reads {@code damagetaken} -- and it does not move. It
 * runs three timers against each other and spits flame along its facing:
 *
 * <ul>
 * <li>{@code ThirdTimer} is the rest between bursts. While it is above nought
 *     it counts down and holds the other two at five and ten.</li>
 * <li>{@code FourthTimer} then counts ten frames between puffs.</li>
 * <li>{@code SecTimer} counts the puffs, five to a burst. When it runs out
 *     {@code ThirdTimer} is reloaded from {@code ObjTimer}, which the level
 *     file sets per pipe -- so each pipe keeps its own rhythm, and a corridor
 *     of them can be timed to be run.</li>
 * </ul>
 *
 * The whoosh is played on the puff that leaves four in the count, the second
 * of the burst; every puff is a shot.
 */
public final class GasPipe {

    /** The dispatch value: {@code cmp.b #20,d0 / beq JUMPGASPIPE}. */
    public static final int TYPE = 20;
    /** {@code move.w #5,SecTimer(a0)}: puffs in a burst. */
    public static final int PUFFS = 5;
    /** {@code move.w #10,FourthTimer(a0)}: frames between puffs. */
    public static final int PUFF_GAP = 10;
    /** {@code cmp.w #4,SecTimer(a0) / bne.s .nowhoosh}. */
    private static final int WHOOSH_AT = 4;

    private final Level lv;
    private final Bullets bullets;
    private final SineTable sine;
    public Sound sound = Sound.SILENT;

    /** Puffs let out and bursts begun, for checking. */
    public int puffs, bursts;

    public GasPipe(Level level, Bullets bullets, SineTable sine) {
        this.lv = level;
        this.bullets = bullets;
        this.sine = sine;
    }

    /** One frame of one pipe. */
    public void update(int base, int frames) {
        lv.data.setU8(base + Obj.WORRY, 0);           // clr.b worry(a0)

        int third = lv.data.s16(base + Obj.THIRD_TIMER);
        if (third > 0) {                              // ble.s maybeflame
            lv.data.setS16(base + Obj.THIRD_TIMER, M68k.w(third - frames));
            lv.data.setS16(base + Obj.SEC_TIMER, PUFFS);
            lv.data.setS16(base + Obj.FOURTH_TIMER, PUFF_GAP);
            return;
        }

        // maybeflame: sub.w d0,FourthTimer(a0) / blt.s yesflame / rts
        int fourth = M68k.w(lv.data.s16(base + Obj.FOURTH_TIMER) - frames);
        lv.data.setS16(base + Obj.FOURTH_TIMER, fourth);
        if (fourth >= 0) {
            return;
        }

        // yesflame
        lv.data.setS16(base + Obj.FOURTH_TIMER, PUFF_GAP);
        int sec = M68k.w(lv.data.s16(base + Obj.SEC_TIMER) - 1);
        lv.data.setS16(base + Obj.SEC_TIMER, sec);
        if (sec <= 0) {                               // bgt.s notdoneflame
            lv.data.setS16(base + Obj.THIRD_TIMER,
                           lv.data.s16(base + Obj.OBJ_TIMER));
            bursts++;
        }

        if (sec == WHOOSH_AT) {
            // move.w #200,Noisevol / move.w #22,Samplenum / clr.b notifplaying
            sound.play(Sound.FLAME, 0, 0, 200, lv.data.u8(base + 1), false);
        }

        int pt = lv.data.s16(base + Obj.POINT);
        int zone = lv.data.s16(base + Obj.ZONE);
        if (bullets == null || sine == null || pt < 0
                || pt >= lv.objectPointX.length || zone < 0) {
            return;
        }
        int facing = lv.data.s16(base + Obj.FACING) & 8190;
        if (bullets.fireFlame(lv.objectPointX[pt], lv.objectPointZ[pt],
                              lv.data.s16(base + Obj.HEIGHT), zone,
                              lv.data.u8(base + Obj.IN_TOP) != 0,
                              sine.sinByte(facing), sine.cosByte(facing))) {
            puffs++;
        }
    }
}
