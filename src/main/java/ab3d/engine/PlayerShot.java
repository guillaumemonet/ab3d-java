package ab3d.engine;

import ab3d.data.ColBox;
import ab3d.data.GameObject;
import ab3d.data.GunData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.m68k.M68k;

/**
 * {@code Player1Shot} and {@code CalcPLR1InLine}, from source/PLAYERSHOOT.s and
 * source/jg.s.
 *
 * The file is included from {@code anims} rather than from the main, with
 * {@code include "PLAYERSHOOT.s"} in capitals, and {@code objmoveanim} calls it
 * before everything else -- before the switches, the doors, the lifts and the
 * object handler. It is easy to miss on a first reading of the include tree and
 * conclude that this build cannot shoot at all.
 *
 * Aiming is not done by pointing. {@code CalcPLR1InLine} works out, for every
 * object, whether it is in front of the player and within its own half-width of
 * the line of sight, and the shot then takes the nearest of those -- so the
 * player aims by turning until something is in the corridor, and the height
 * difference is what the bullet's vertical speed is set from.
 *
 * What is here is the decision and its cost: whether the gun may fire, what it
 * is aimed at, the delay before the next shot, the ammunition it spends and the
 * animation it starts. What is not here is the shot itself. A gun is either
 * instant -- {@code FIREBULLETS} rolls against the distance and calls
 * {@code PLR1HITINSTANT} -- or it makes a bullet that flies, which
 * {@code PLR1FIREBULLET} puts into {@code PlayerShotData} for {@code ItsABullet}
 * to move. Both end at an enemy's {@code damagetaken}, and no enemy is ported
 * yet, so neither would have anything to act on.
 */
public final class PlayerShot {

    /** {@code move.l #%1111111111110111000001,d7}: which types may be shot at. */
    private static final int TARGETABLE = 0b1111111111110111000001;
    /** {@code add.l #20*128,tempyoff}: the gun sits this far above the eye. */
    public static final int MUZZLE_HEIGHT = 20 * 128;
    /** {@code add.l #18*256,d5}. */
    private static final int AIM_RISE = 18 * 256;
    /** {@code divs #44,d2}: how far off vertically still counts, per unit. */
    private static final int AIM_SLOPE = 44;

    /** {@code numlives EQU 18} and the type byte above it. */
    private static final int NUM_LIVES = 18, TYPE = 16, FLAGS = 17;
    private static final int IN_PLAY_ZONE = 12, HEIGHT = 4;

    private final Level lv;
    /** {@code move.b 3(a6),Samplenum+1}: each gun has its own noise. */
    public Sound sound = Sound.SILENT;
    private final SineTable sine;
    private final ColBox boxes;
    private final GunData guns;

    /** {@code PLR1_ObsInLine} and {@code PLR1_ObjDists}. */
    public final boolean[] inLine;
    public final int[] dists;

    /** {@code PLR1_TimeToShoot}. */
    public int timeToShoot;
    /** {@code PLR1_GunFrame}: set to the animation's length when a shot goes. */
    public int gunFrame;
    /** Which object the last shot was aimed at, or -1. */
    public int lastTarget = -1;
    /** Shots fired and shots refused for want of ammunition, for checking. */
    public int fired, dryFired;

    public PlayerShot(Level level, SineTable sine, ColBox boxes, GunData guns) {
        this.lv = level;
        this.sine = sine;
        this.boxes = boxes;
        this.guns = guns;
        this.inLine = new boolean[level.objects.size()];
        this.dists = new int[level.objects.size()];
    }

    /**
     * {@code CalcPLR1InLine}, which jg.s runs once a frame before the controls.
     *
     * Each object's position is turned into the player's own frame: one axis
     * along the line of sight, the other across it. Anything at or behind the
     * player is out, and anything further across than its own half-width is out.
     * The along-axis distance is kept, and that is what the shot sorts by.
     */
    public void calcInLine(int px, int pz, int sinval, int cosval) {
        for (int i = 0; i < lv.objects.size(); i++) {
            int base = lv.ptrObjects + i * GameObject.SIZE;
            int pt = lv.data.s16(base);
            if (pt < 0 || pt >= lv.objectPointX.length
                    || lv.data.s16(base + IN_PLAY_ZONE) < 0) {
                inLine[i] = false;                  // tst.w 12(a4) / blt
                dists[i] = 0;
                continue;
            }
            int d0 = M68k.w(lv.objectPointX[pt] - px);
            int d1 = M68k.w(lv.objectPointZ[pt] - pz);

            // across the line of sight, doubled and made positive
            long d2 = (long) M68k.muls(d0, cosval) - M68k.muls(d1, sinval);
            d2 += d2;
            if (d2 < 0) {
                d2 = -d2;
            }
            int across = (int) (d2 >> 16);          // swap d2

            // along it
            long along = ((long) M68k.muls(d0, sinval) + M68k.muls(d1, cosval)) << 2;
            int depth = (short) (along >> 16);      // swap d1

            boolean ok = depth > 0;                 // tst.w d1 / ble
            if (ok) {
                int half = M68k.asrW(across, 1);
                ok = half <= boxes.width(lv.data.u8(base + TYPE));
            }
            inLine[i] = ok;
            dists[i] = depth;
        }
    }

    /**
     * {@code Player1Shot}, as far as the shot itself.
     *
     * @param fire    {@code p1_fire}, held
     * @param clicked {@code p1_clicked}, this frame only
     * @param frames  {@code TempFrames}
     * @return true when a shot went
     */
    /** The shots that fly, and where the player stands for them. */
    public Bullets bullets;
    public int playerZone;
    public boolean playerInTop;
    /** {@code tempxdir} and {@code tempzdir}: the way the player faces. */
    public int dirX, dirZ;

    public boolean fire(int gun, boolean fire, boolean clicked, int frames,
                        int px, int pz, int playerY, int playerHeight,
                        int maxFrame) {
        lastTarget = -1;
        lastHits = 0;

        if (timeToShoot != 0) {
            timeToShoot = Math.max(0, timeToShoot - frames);
            return false;                           // PLR1_nofire
        }
        if (gun < 0 || gun >= GunData.GUNS) {
            return false;
        }
        // tst.w 12(a6): nought means the gun waits for a click, not a hold
        boolean wants = guns.word(gun, GunData.HOLD) == 0 ? clicked : fire;
        if (!wants) {
            return false;
        }

        // findclosestinline: the nearest thing in the corridor that can be shot
        int best = 0x7fff;
        int target = -1;
        long targetYDiff = 0;
        for (int i = 0; i < lv.objects.size(); i++) {
            int base = lv.ptrObjects + i * GameObject.SIZE;
            if (lv.data.s16(base) < 0) {
                break;                              // tst.w (a0) / blt outofline
            }
            if (!inLine[i]) {
                continue;
            }
            if ((lv.data.u8(base + FLAGS) & 1) == 0) {
                continue;                           // btst #0,17(a0)
            }
            if (lv.data.s16(base + IN_PLAY_ZONE) < 0) {
                continue;
            }
            int type = lv.data.u8(base + TYPE);
            if (type > 21 || (TARGETABLE & (1 << type)) == 0) {
                continue;                           // btst d6,d7
            }
            if (lv.data.u8(base + NUM_LIVES) == 0) {
                continue;                           // tst.b numlives(a0)
            }
            int dist = dists[i];
            long dy = ((long) lv.data.s16(base + HEIGHT) << 7) - playerY;
            long rise = Math.abs(dy) / AIM_SLOPE;
            if (rise > dist) {
                continue;                           // cmp.w d6,d2 / bgt
            }
            if (dist >= best) {
                continue;                           // cmp.w d6,d1 / blt
            }
            best = dist;
            target = i;
            targetYDiff = dy;
        }

        // the ammunition, which is spent whether or not anything was lined up
        int have = guns.unsigned(gun, GunData.AMMO);
        int perShot = guns.byteAt(gun, GunData.PER_SHOT);
        if (have < perShot) {
            dryFired++;
            // move.b #12,Samplenum+1 / move.w #300,Noisevol: the empty click
            sound.play(Sound.NO_AMMO, 0, 0, 300, 0xfb, false);
            return false;
        }

        aimRise = target < 0 ? 0 : verticalSpeed(best, targetYDiff, playerHeight,
                                                 guns.word(gun, GunData.SPEED));
        // move.b 3(a6),Samplenum+1 / move.w #300,Noisevol
        sound.play(guns.byteAt(gun, GunData.NOISE), 0, 0, 300, 0xfb, false);
        timeToShoot = guns.word(gun, GunData.DELAY);   // move.w 8(a6),PLR1_TimeToShoot
        gunFrame = maxFrame;                        // move.b MaxFrame,PLR1_GunFrame
        guns.setWord(gun, GunData.AMMO, have - perShot);
        lastTarget = target;
        fired++;

        // tst.b 5(a6): an instant gun does its damage now, a visible one makes a
        // bullet that flies. FIREBULLETS rolls once per pellet against the
        // distance -- the further away, the more of them miss -- and each hit
        // does the gun's damage byte.
        if (guns.byteAt(gun, GunData.INSTANT) == 0) {
            // PLR1FIREBULLET: a shot that flies, whether or not anything was
            // lined up -- an unaimed rocket still goes where the player looks
            if (bullets != null) {
                bullets.firePlayer(guns, gun, px, pz, playerY + MUZZLE_HEIGHT,
                                   playerZone, playerInTop, dirX, dirZ,
                                   aimRise, TARGETABLE);
            }
        } else if (target >= 0) {
            int pellets = guns.word(gun, GunData.BULLET);
            int hit = 0;
            for (int i = 0; i < pellets; i++) {
                if (hits(target, px, pz)) {
                    hit++;
                }
            }
            if (hit > 0) {
                hitTarget(target, guns.byteAt(gun, GunData.DAMAGE) * hit);
            }
            pelletsFired += pellets;
            pelletsHit += hit;
            lastHits = hit;
        }

        return true;
    }

    /** The bullet's vertical speed, for when a flying shot is ported. */
    public int aimRise;
    /** Pellets thrown and pellets that landed, for checking the spread. */
    public int pelletsFired, pelletsHit;
    /** How many of the last shot's pellets landed on {@link #lastTarget}. */
    public int lastHits;
    /** A fixed sequence, so a run can be repeated when checking. */
    private int rand = 0x1234567;

    /**
     * {@code FIREBULLETS}: one pellet against the squared distance.
     *
     * A random number under fifteen bits is doubled and compared against the
     * squared distance shifted down six. So the chance of a hit falls off with
     * the square of the range, and at close quarters every pellet lands.
     */
    private boolean hits(int target, int px, int pz) {
        int pt = lv.data.s16(lv.ptrObjects + target * GameObject.SIZE);
        if (pt < 0 || pt >= lv.objectPointX.length) {
            return false;
        }
        long dx = M68k.w(lv.objectPointX[pt] - px);
        long dz = M68k.w(lv.objectPointZ[pt] - pz);
        long d1 = (dx * dx + dz * dz) >> 6;
        rand = rand * 1103515245 + 12345;
        long d0 = ((rand >>> 16) & 0x7fff) * 2L;
        return d0 > d1;                             // cmp.l d1,d0 / bgt .hitplr
    }

    /** {@code move.b 6(a6),d0 / add.b d0,damagetaken(a5)}. */
    private void hitTarget(int target, int damage) {
        int at = lv.ptrObjects + target * GameObject.SIZE + Obj.DAMAGE_TAKEN;
        lv.data.setU8(at, Math.min(255, lv.data.u8(at) + damage));
        damageDealt += damage;
    }

    /** Total damage handed out, for checking. */
    public int damageDealt;

    /**
     * {@code divs d1,d5}: how fast the bullet must rise to meet the target.
     *
     * The distance is shifted right by the bullet's speed rather than divided by
     * it, so the speed field is a shift count, and a distance that shifts down
     * to nothing is treated as one.
     */
    private static int verticalSpeed(int dist, long yDiff, int playerHeight,
                                     int speed) {
        long d5 = yDiff - playerHeight + AIM_RISE;
        int d1 = M68k.asrW(dist, speed & 15);
        if (d1 <= 0) {
            d1 = 1;                                 // moveq #1,d1
        }
        return (int) (d5 / d1);
    }

    /** {@code sub.w TempFrames,d1 ... sub.b #1,PLR1_GunFrame}, after the draw. */
    public void ageGunFrame(int frames) {
        int d1 = gunFrame - frames;
        if (d1 <= 0) {
            gunFrame = 0;
            return;
        }
        gunFrame = d1 - 1;                          // the extra one, below .nn
    }

    /** The sine table, kept so the caller need not pass it twice. */
    public SineTable sine() {
        return sine;
    }
}
