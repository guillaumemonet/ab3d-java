package ab3d.engine;

import ab3d.data.GunData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.m68k.M68k;

/**
 * {@code ItsARobot}, from source/robot.s: one of them, in the eighth level.
 *
 * It shares nothing with the other enemy files but their outline. It is a
 * model, not a sprite -- {@code $ff} in its scale byte -- so it has no sides to
 * pick between, only a facing the model turns by and a frame of its walk.
 * And it does not walk in whatever direction it faces: it has a heading it
 * wants, and steers towards it.
 *
 * <ul>
 * <li>The steering is a cross product. The sine of the angle between where it
 *     faces and where it wants to go decides which way it turns, by a hundred
 *     and twenty a frame while it prowls and two hundred and forty while it
 *     attacks; inside about five degrees it does not turn at all.</li>
 * <li>The dot product decides whether it walks. Further round than sixty
 *     degrees it stands and turns on the spot, twice as fast -- and if the
 *     cross product said not to turn, which only happens when the heading is
 *     straight behind it, it turns anyway.</li>
 * <li>While attacking it walks straight at the player whatever it faces,
 *     stopping three hundred short, but fires only when facing them: two
 *     grenades fifty frames apart, then a wait of a hundred and fifty or
 *     more.</li>
 * <li>Its armour takes four bits off every hit, so anything under sixteen
 *     does nothing at once -- but the byte is not cleared when that happens,
 *     so small hits add up until together they get through, as they do for
 *     the tree. Killed, it blows up -- {@code ComputeBlast} at a hundred and
 *     twenty while prowling and four hundred while attacking -- and leaves a
 *     key, the same as the big red thing.</li>
 * </ul>
 *
 * The routine takes two thousand and forty-eight off its facing when it
 * starts and puts it back at the end: the model is built a quarter turn round
 * from the way the steering counts.
 */
public final class Robot {

    /** {@code cmp.b #6,d0 / beq JUMPROBOT}. */
    public static final int TYPE = 6;
    /** {@code move.w #10,maxspd(a0)}: written every frame, whatever the level says. */
    private static final int TOP_SPEED = 10;
    /** {@code cmp.w #43,d0}: the shared walk counter, shown at half rate. */
    private static final int ANIM_LENGTH = 43;
    /** {@code move.w #160,extlen} and the rest of what it moves with. */
    private static final int EXT_LEN = 160, THING_HEIGHT = 160 * 128;
    private static final int STEP = 20 * 256, AWAY_FROM_WALL = 2;
    /** {@code sub.w #120,d0}: how far above its floor it stands. */
    private static final int STAND_OFF = 120;
    /** {@code sub.w #40*128,d0}: where its feet are for the step tests. */
    private static final int FEET = 40 * 128;
    /** {@code move.w #300,Range}. */
    private static final int RANGE = 300;
    /** {@code move.w #120,d3} and {@code #240}: the turn a frame. */
    private static final int PROWL_TURN = 120, ATTACK_TURN = 240;
    /** {@code cmp.l #$20000000,d1}: a dot product of a half, sixty degrees. */
    private static final int WALK_DOT = 0x20000000;
    /** {@code asr.b #4,d2}. */
    private static final int ARMOUR = 4;
    /** {@code move.w #120,d0 / bsr ComputeBlast} prowling, {@code #400} attacking. */
    private static final int PROWL_BLAST = 120, ATTACK_BLAST = 400;
    /** {@code move.b #4,SHOTTYPE}: the grenade. */
    private static final int SHOT_TYPE = 4, SHOT_POWER = 10, SHOT_SPEED = 16,
                              SHOT_SHIFT = 3;

    private final Level lv;
    private final AlienControl alien;
    private final MoveObject mover;
    private final SineTable sine;
    private final Enemy.Rand rand;
    public Bullets bullets;
    public GunData guns;
    public Sound sound = Sound.SILENT;

    /** {@code move.w #100,d0} before {@code FindCloseRoom}. */
    private static final int CLOSE_ROOM = 100;
    /** Which room the model is drawn in. */
    public CloseRoom closeRoom;

    /** {@code ROBFRAME}: one counter for every robot, which is to say for one. */
    private int robFrame;

    /** Counters for checking. */
    public int prowled, attacked, shots, blasts, turnsOnSpot;

    public Robot(Level level, AlienControl alien, MoveObject mover,
                 SineTable sine, Enemy.Rand rand) {
        this.lv = level;
        this.alien = alien;
        this.mover = mover;
        this.sine = sine;
        this.rand = rand;
    }

    /** One frame of the robot. */
    public void update(int base, int frames, int px, int pz, int py,
                       int playerZone, boolean playerInTop) {
        // sub.w #2048,d0 / and.w #8190,d0: into the steering's own frame
        int facing = M68k.w(lv.data.s16(base + Obj.FACING) - 2048) & 8190;
        lv.data.setS16(base + Obj.FACING, facing);
        // sub.b #1,worry(a0): plain, unlike the others' kept top bit
        lv.data.setU8(base + Obj.WORRY, (lv.data.u8(base + Obj.WORRY) - 1) & 0xff);

        int zone = lv.data.s16(base + Obj.ZONE);
        if (zone < 0 || zone >= lv.zones.length) {
            return;                                    // .notthisone
        }
        // move.w #100,d0 / jsr FindCloseRoom / clr.b exitfirst: the robot asks
        // once, at the top, and nothing after writes GraphicRoom again
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, closeRoom == null ? zone
                : closeRoom.find(base, CLOSE_ROOM, EXT_LEN, px, pz));
        if (lv.data.u8(base + Obj.NUM_LIVES) == 0) {
            // .onfloordead: move.b #-1,16(a0) -- out of the dispatch for good.
            // Nothing reaches it: the robot is a key before its lives read nought.
            lv.data.setU8(base + Obj.TYPE, 0xff);
            return;
        }

        lv.data.setS16(base + Obj.MAX_SPEED, TOP_SPEED);
        robFrame += frames;
        if (robFrame >= ANIM_LENGTH) {
            robFrame = 0;
        }
        lv.data.setS16(base + Obj.FRAME, robFrame >> 1);   // asr.w #1,d0

        boolean sees = (lv.data.u8(base + Obj.FLAGS) & Obj.SEES_PLAYER1) != 0;
        if (sees) {
            int third = lv.data.s16(base + Obj.THIRD_TIMER);
            if (third <= 0) {
                attack(base, frames, zone, px, pz, py, playerZone, playerInTop);
                return;
            }
            third = M68k.w(third - frames);
            lv.data.setS16(base + Obj.THIRD_TIMER, Math.max(0, third));
        } else {
            lv.data.setS16(base + Obj.THIRD_TIMER, ((rand.next() >>> 4) & 63) + 20);
        }
        lv.data.setS16(base + Obj.FOURTH_TIMER, 30);

        // .waitandsee: a new heading when the timer runs out -- or, if the
        // player is in sight by then, the attack after all
        int timer = M68k.w(lv.data.s16(base + Obj.OBJ_TIMER) - frames);
        lv.data.setS16(base + Obj.OBJ_TIMER, timer);
        if (timer <= 0) {
            if (sees) {
                attack(base, frames, zone, px, pz, py, playerZone, playerInTop);
                return;
            }
            lv.data.setS16(base + Obj.CURR_CPT, rand.next() & 8190);
            lv.data.setS16(base + Obj.OBJ_TIMER, (rand.next() & 63) + 100);
        }
        prowled++;

        int want = lv.data.s16(base + Obj.CURR_CPT) & 8190;
        facing = steer(base, facing, sine.sinByte(want), sine.cosByte(want),
                       PROWL_TURN);

        int pt = lv.data.s16(base + Obj.POINT);
        if (pt < 0 || pt >= lv.objectPointX.length) {
            return;
        }
        int oldX = lv.objectPointX[pt], oldZ = lv.objectPointZ[pt];
        int speed = M68k.muls(lv.data.s16(base + Obj.MAX_SPEED), frames);
        long step = alien.goInDirection(oldX, oldZ, facing, speed);
        zone = moveTo(base, zone, oldX, oldZ, AlienControl.newX(step),
                      AlienControl.newZ(step));
        if (mover.hitWall) {
            lv.data.setS16(base + Obj.OBJ_TIMER, -1);
        }

        if (hurt(base, zone, PROWL_BLAST) == KEY) {
            return;
        }

        // move.b #1,17(a0) when it sees, and nothing when it does not: the
        // prowl never clears the flag, only the attack does
        if (alien.canItBeSeen(zone, playerZone,
                              lv.data.u8(base + Obj.IN_TOP) != 0, playerInTop)) {
            lv.data.setU8(base + Obj.FLAGS, Obj.SEES_PLAYER1);
        }
        lv.data.setS16(base + Obj.FACING, (facing + 2048) & 8190);
    }

    /** {@code MUSTPLR1}. */
    private void attack(int base, int frames, int zone, int px, int pz, int py,
                        int playerZone, boolean playerInTop) {
        attacked++;
        int facing = lv.data.s16(base + Obj.FACING);
        lv.data.setS16(base + Obj.FOURTH_TIMER,
                       M68k.w(lv.data.s16(base + Obj.FOURTH_TIMER) - frames));
        int pt = lv.data.s16(base + Obj.POINT);
        if (pt < 0 || pt >= lv.objectPointX.length) {
            return;
        }
        int oldX = lv.objectPointX[pt], oldZ = lv.objectPointZ[pt];
        int speed = M68k.muls(lv.data.s16(base + Obj.MAX_SPEED), frames);
        AlienControl.Approach a = alien.headTowardsAng(oldX, oldZ, px, pz, speed,
                                                       RANGE);
        // SinRet and CosRet, which HeadTowardsAng leaves behind: the line to the
        // player as a unit vector of the sine table's own size
        int xdiff = M68k.w(px - oldX), zdiff = M68k.w(pz - oldZ);
        int d = a.dist() + 1;
        int sinRet = (int) (((long) xdiff << 16) / 2 / d);
        int cosRet = (int) (((long) zdiff << 16) / 2 / d);

        zone = moveTo(base, zone, oldX, oldZ, a.x(), a.z());
        facing = steer(base, facing, sinRet, cosRet, ATTACK_TURN);
        // sgt.s canshootgun: only facing the player, within sixty degrees
        boolean canShoot = lastDot > WALK_DOT;

        int got = hurt(base, zone, ATTACK_BLAST);
        if (got == KEY) {
            return;
        }
        if (got == NONE && canShoot
                && lv.data.s16(base + Obj.FOURTH_TIMER) < 20) {
            lv.data.setS16(base + Obj.FOURTH_TIMER, 50);
            int third = lv.data.s16(base + Obj.THIRD_TIMER) - 1;
            if (third < -1) {                          // cmp.w #-1,d0 / bge
                third = ((rand.next() >>> 4) & 127) + 150;
            }
            lv.data.setS16(base + Obj.THIRD_TIMER, third);
            lv.data.setS16(base + Obj.BRIGHT, -100);
            fire(base, px, pz, py);
        }

        // .cantshoot: move.b #0,17(a0), then look again
        boolean sees = alien.canItBeSeen(zone, playerZone,
                lv.data.u8(base + Obj.IN_TOP) != 0, playerInTop);
        lv.data.setU8(base + Obj.FLAGS, sees ? Obj.SEES_PLAYER1 : 0);
        lv.data.setS16(base + Obj.FACING, (facing + 2048) & 8190);
    }

    /** The dot product the last {@link #steer} worked out. */
    private int lastDot;

    /**
     * {@code .turnlefty} / {@code .turnrighty} and {@code .canwalk}.
     *
     * @return the new facing, {@code and.w #8191} -- odd values and all, which
     *         is what the file writes
     */
    private int steer(int base, int facing, int sinT, int cosT, int turn) {
        // move.w 120(a2),d6 / swap / clr.w / asr.l #1: about five degrees, as
        // the same fixed point the products come out in
        int d6 = (sine.sinByte(120) << 16) >> 1;
        int sf = sine.sinByte(facing), cf = sine.cosByte(facing);
        int d4 = M68k.muls(sf, cosT) - M68k.muls(cf, sinT);   // sub.l d5,d4
        int d3 = 0;
        if (d4 < 0) {
            if (d6 <= -d4) {
                d3 = turn;                                    // .turnrighty
            }
        } else if (d6 <= d4) {
            d3 = -turn;                                       // .turnlefty
        }
        lastDot = M68k.muls(cf, cosT) + M68k.muls(sf, sinT);
        if (lastDot <= WALK_DOT) {
            // move.w #0,maxspd(a0): too far round to walk, so turn on the spot
            lv.data.setS16(base + Obj.MAX_SPEED, 0);
            d3 += d3;
            if (d3 == 0) {
                d3 = turn * 2;
            }
            turnsOnSpot++;
        }
        return (facing + d3) & 8191;
    }

    /** {@code GoInDirection} or {@code HeadTowardsAng}, then {@code MoveObject}. */
    private int moveTo(int base, int zone, int oldX, int oldZ, int newX, int newZ) {
        int pt = lv.data.s16(base + Obj.POINT);
        int feet = (lv.data.s16(base + Obj.HEIGHT) << 7) - FEET;
        boolean inTop = lv.data.u8(base + Obj.IN_TOP) != 0;
        mover.enterStorey(inTop);
        mover.move(zone, oldX, oldZ, newX, newZ, feet, feet, EXT_LEN,
                   THING_HEIGHT, STEP, STEP, Enemy.WALL_FLAGS, AWAY_FROM_WALL);
        lv.objectPointX[pt] = mover.x;
        lv.objectPointZ[pt] = mover.z;
        lv.data.setS16(base + Obj.ZONE, mover.zone);
        lv.data.setU8(base + Obj.IN_TOP, mover.stoodInTop ? 0xff : 0);
        int z = mover.zone;
        if (z >= 0 && z < lv.zones.length) {
            int floor = mover.stoodInTop ? lv.zone(z).upperFloorHeight
                                         : lv.zone(z).floorHeight;
            lv.data.setS16(base + Obj.HEIGHT, M68k.w((floor >> 7) - STAND_OFF));
        }
        return z;
    }

    private static final int NONE = 0, HURT = 1, KEY = 2;

    /**
     * {@code damagetaken}, through four bits of armour.
     *
     * @return {@link #NONE} when nothing got through, {@link #HURT} when it
     *         cried out, {@link #KEY} when it blew up and is a key now
     */
    private int hurt(int base, int zone, int blast) {
        int d2 = ((byte) lv.data.u8(base + Obj.DAMAGE_TAKEN)) >> ARMOUR;
        if (d2 == 0) {
            return NONE;                               // beq .noscream
        }
        int lives = (byte) (lv.data.u8(base + Obj.NUM_LIVES) - d2);
        lv.data.setU8(base + Obj.NUM_LIVES, lives & 0xff);
        if (lives > 0) {
            lv.data.setU8(base + Obj.DAMAGE_TAKEN, 0);
            sound.play(Sound.LOW_SCREAM, 0, 0, 200, lv.data.u8(base + 1), false);
            return HURT;
        }
        sound.play(Sound.BOOM, 0, 0, 400, lv.data.u8(base + 1), false);
        int pt = lv.data.s16(base + Obj.POINT);
        if (bullets != null && pt >= 0 && pt < lv.objectPointX.length) {
            bullets.blastAt(zone, lv.data.u8(base + Obj.IN_TOP) != 0,
                            lv.objectPointX[pt], lv.objectPointZ[pt], blast);
            blasts++;
        }
        Enemy.becomeKey(lv, base);
        return KEY;
    }

    /** {@code jsr FireAtPlayer1} with a grenade: lobbed, and it bounces. */
    private void fire(int base, int px, int pz, int py) {
        shots++;
        sound.play(Sound.BADDIE_GUN, 0, 0, 100, lv.data.u8(base + 1), false);
        int pt = lv.data.s16(base + Obj.POINT);
        if (bullets == null || guns == null || pt < 0
                || pt >= lv.objectPointX.length) {
            return;
        }
        bullets.fireEnemy(guns, SHOT_TYPE, SHOT_POWER, SHOT_SPEED, SHOT_SHIFT, 0,
                          lv.objectPointX[pt], lv.objectPointZ[pt],
                          lv.data.s16(base + Obj.HEIGHT) << 7,
                          lv.data.s16(base + Obj.ZONE),
                          lv.data.u8(base + Obj.IN_TOP) != 0, px, pz, py);
    }
}
