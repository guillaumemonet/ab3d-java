package ab3d.engine;

import ab3d.data.Level;
import ab3d.m68k.M68k;

/**
 * {@code ItsABarrel}, from source/anims: thirteen of them across the levels.
 *
 * It is the simplest thing in the object list. It does not move, does not look
 * for anyone, and has exactly two states, told apart by its graphic slot: while
 * that is anything but eight it is a barrel standing on the floor taking damage,
 * and once it is eight it is an explosion counting to eight and then gone.
 *
 * Killing one calls {@code ComputeBlast} with a force of forty -- the same as a
 * grenade -- so a barrel beside a group of enemies is a weapon, and one beside
 * the player is a trap. The blast is stopped by walls like any other, because it
 * goes through the same room-list test.
 *
 * It sets its sight bit the way the enemies do, which has no effect on the
 * barrel itself but does decide whether the player may shoot it:
 * {@code Player1Shot} skips anything whose bit is clear.
 */
public final class Barrel {

    /** {@code cmp.b #10,d0 / beq JUMPBARREL}. */
    public static final int TYPE = 10;
    /** {@code cmp.w #8,8(a0)}: the slot that means it is already going off. */
    private static final int BURST_SLOT = 8;
    /** {@code cmp.w #8,d0}: how many frames the burst lasts. */
    private static final int BURST_FRAMES = 8;
    /** {@code add.w #$404,6(a0)}: the explosion grows each frame. */
    private static final int GROW = 0x404;
    /** {@code move.w #40,d0 / jsr ComputeBlast}. */
    public static final int FORCE = 40;
    /** {@code move.w #$1f1f,14(a0)} standing, {@code #$2020} bursting. */
    private static final int SIZE = 0x1f1f, BURST_SIZE = 0x2020;
    /** {@code sub.w #60,d0}: how far above its floor it sits. */
    private static final int STAND_OFF = 60;

    private final Level lv;
    /** {@code move.w #15,Samplenum / move.w #300,Noisevol}. */
    public Sound sound = Sound.SILENT;
    private final AlienControl alien;
    private final Bullets bullets;

    /** Counters for checking. */
    public int burst, blasted;

    public Barrel(Level level, AlienControl alien, Bullets bullets) {
        this.lv = level;
        this.alien = alien;
        this.bullets = bullets;
    }

    /** One frame of one barrel. */
    public void update(int base, int playerZone, boolean playerInTop) {
        lv.data.setU8(base + Obj.WORRY, 0);          // clr.b worry(a0)
        int zone = lv.data.s16(base + Obj.ZONE);
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, zone);

        if (lv.data.s16(base + Obj.SLOT) == BURST_SLOT) {
            // it is already going off: grow, count, and then be gone
            lv.data.setS16(base + Obj.BRIGHT,
                           M68k.w(lv.data.s16(base + Obj.BRIGHT) + GROW));
            int d0 = lv.data.s16(base + Obj.FRAME) + 1;
            if (d0 == BURST_FRAMES) {
                lv.data.setS16(base + Obj.ZONE, -1);
                lv.data.setS16(base + Obj.GRAPHIC_ROOM, -1);
                return;
            }
            lv.data.setS16(base + Obj.FRAME, d0);
            return;
        }

        lv.data.setS16(base + Obj.SPRITE_SIZE, SIZE);
        if (zone < 0 || zone >= lv.zones.length) {
            return;
        }
        boolean inTop = lv.data.u8(base + Obj.IN_TOP) != 0;
        int floor = inTop ? lv.zone(zone).upperFloorHeight
                          : lv.zone(zone).floorHeight;
        lv.data.setS16(base + Obj.HEIGHT, M68k.w((floor >> 7) - STAND_OFF));

        int hurt = lv.data.u8(base + Obj.DAMAGE_TAKEN);
        if (hurt != 0) {
            lv.data.setU8(base + Obj.DAMAGE_TAKEN, 0);
            int lives = lv.data.u8(base + Obj.NUM_LIVES) - hurt;
            if (lives <= 0) {
                lv.data.setU8(base + Obj.NUM_LIVES, 0);
                int pt = lv.data.s16(base + Obj.POINT);
                if (pt >= 0 && pt < lv.objectPointX.length && bullets != null) {
                    bullets.blastAt(zone, inTop, lv.objectPointX[pt],
                                    lv.objectPointZ[pt], FORCE);
                    blasted++;
                }
                sound.play(Sound.BOOM, 0, 0, 300, 3, false);
                lv.data.setS16(base + Obj.SLOT, BURST_SLOT);
                lv.data.setS16(base + Obj.FRAME, 0);
                lv.data.setS16(base + Obj.SPRITE_SIZE, BURST_SIZE);
                lv.data.setS16(base + Obj.BRIGHT, -30);
                burst++;
                return;
            }
            lv.data.setU8(base + Obj.NUM_LIVES, lives);
        }

        // the sight bit, which is what lets the player shoot it
        boolean sees = alien.canItBeSeen(zone, playerZone, inTop, playerInTop);
        lv.data.setU8(base + Obj.FLAGS, sees ? Obj.SEES_PLAYER1 : 0);
    }
}
