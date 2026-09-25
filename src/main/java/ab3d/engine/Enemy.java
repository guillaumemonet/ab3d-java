package ab3d.engine;

import ab3d.data.Level;
import ab3d.m68k.M68k;

/**
 * The enemy routines, from the files source/aliencontrol.s pulls in.
 *
 * Those files are near-copies of one another. Running a diff over
 * NormalAlien.s and MutantMarine.s -- nine hundred and fifty lines each --
 * turns up about thirty lines that genuinely differ, and all of them are
 * numbers: how tall the thing is, how far above its floor it stands, which
 * graphic slot it draws from, the frames it dies through, how close it comes
 * before attacking, and whether it circles or walks straight in. So one routine
 * with a table of those numbers says what eleven files say, and says it once.
 *
 * The shape is the same for all of them. Each has three states and the top of
 * the routine chooses: dead and playing out its dying frames, attacking because
 * it can see the player, or prowling. Prowling is not a search -- it walks in
 * whatever direction it faces until it hits something or a timer runs out, then
 * picks a new direction at random. The line-of-sight test at the end of the
 * prowl is what turns it into a threat, and the same bit is what lets the player
 * shoot it: {@code Player1Shot} skips anything that has not noticed the player.
 */
public final class Enemy {

    /**
     * The numbers one kind of enemy differs by.
     *
     * @param slot      the graphic slot, which the original adds as the high
     *                  word of the longword at eight
     * @param dying     the frames it dies through, counted down
     * @param range     {@code move.w #n,Range}: how close it comes
     * @param circles   whether {@code RunAround} is called, so it arrives at an
     *                  angle rather than head-on
     * @param bite      damage at touching distance, or nought for none
     * @param shoot     damage at a distance, or nought for none
     * @param projectile whether its ranged attack is a bullet that flies rather
     *                  than an instant hit -- {@code jsr FireAtPlayer1} into
     *                  {@code NastyShotData}, which needs {@code ItsABullet}
     * @param falls     whether it dies by dropping to the floor and bursting
     *                  instead of playing a list of frames
     * @param turns     whether a new direction is a turn from the present one
     *                  rather than a fresh angle
     */
    public record Kind(String name, int type, int spriteSize, int extLen,
                       int thingHeight, int standOff, int deadOff, int slot,
                       int[] dying, int range, boolean circles,
                       int bite, int biteGap, int shoot,
                       boolean projectile, boolean falls, boolean turns,
                       int shotType, int shotSpeed, int shotShift) {
    }

    /** {@code ItsANasty}: the red scurrying alien, forty-two of them placed. */
    public static final Kind NASTY = new Kind(
            "nasty", 0, 0x1f1f, 80, 80 * 128, 40, 64, 0,
            run(11, 33, 15, 32), 160, true, 2, 20, 0, false, false, false, 0, 0, 0);

    /** {@code ItsAMutantMarine}: seventy-seven of them, and it shoots back. */
    public static final Kind MARINE = new Kind(
            "marine", 12, 0x1f1f, 80, 128 * 128, 64, 64, 10,
            run(6, 18, 10, 17, 10, 16), 80, false, 0, 0, 4,
            false, false, false, 0, 0, 0);

    /**
     * {@code ItsAToughMarine}: the same again from a different graphic slot.
     *
     * Its shot is not an instant hit. It sets {@code SHOTTYPE} to six and
     * {@code SHOTPOWER} to seven and calls {@code FireAtPlayer1}, which puts a
     * bullet into the shot pool for {@code ItsABullet} to fly -- so the power is
     * carried on the bullet, not applied here.
     */
    public static final Kind TOUGH_MARINE = new Kind(
            "tough", 18, 0x1f1f, 80, 128 * 128, 64, 64, 16,
            run(6, 18, 10, 17, 10, 16), 80, false, 0, 0, 7,
            true, false, false, 6, 32, 4);

    /** {@code ItsAFlameMarine}: two damage rather than four, and slot seventeen. */
    public static final Kind FLAME_MARINE = new Kind(
            "flame", 19, 0x1f1f, 80, 128 * 128, 64, 64, 17,
            run(6, 18, 10, 17, 10, 16), 80, false, 0, 0, 2,
            false, false, false, 0, 0, 0);

    /**
     * {@code ItsAFlyingScalyBall}: the one that is built differently.
     *
     * It has no list of dying frames. Killed, it falls -- sixteen units a frame
     * -- until it reaches the floor, and only then bursts. And a new direction
     * for it is a turn of up to a quarter either way from the one it is on
     * rather than a fresh angle, which is what makes it wheel about instead of
     * darting. Its shot is a bullet like the tough marine's.
     */
    public static final Kind FLYING = new Kind(
            "flying", 8, 0x1f1f, 160, 96 * 128, 64, 64, 4,
            new int[0], 120, false, 0, 0, 5, true, true, true, 0, 16, 3);

    public static final Kind[] KINDS = {NASTY, MARINE, TOUGH_MARINE,
                                        FLAME_MARINE, FLYING};

    /** {@code dcb.w n,v} runs, in the order the source writes them. */
    private static int[] run(int... pairs) {
        int total = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            total += pairs[i];
        }
        int[] a = new int[total];
        int at = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            for (int k = 0; k < pairs[i]; k++) {
                a[at++] = pairs[i + 1];
            }
        }
        return a;
    }

    /** {@code move.w #50,ObjTimer(a0)}: how long one prowl direction lasts. */
    private static final int WALK_TIME = 50;
    /** {@code move.w #25,FourthTimer(a0)}. */
    private static final int ATTACK_WAIT = 25;
    public static final int STEP_UP = 20 * 256, STEP_DOWN = 20 * 256;
    /** {@code move.w #%1000000000,wallflags}. */
    public static final int WALL_FLAGS = 0b1000000000;
    /** {@code move.b #1,awayfromwall}: twice the player's standoff. */
    public static final int AWAY_FROM_WALL = 1;
    /** {@code cmp.w #20,FourthTimer(a0) / bgt .cantshoot}. */
    private static final int SHOOT_AT = 20;

    private final Level lv;
    private final AlienControl alien;
    private final MoveObject mover;
    private final Rand rand;
    /** The screams, the hisses and the shots. */
    public Sound sound = Sound.SILENT;
    /** The shot pool, for the kinds that fire something that flies. */
    public Bullets bullets;
    public ab3d.data.GunData guns;
    public ab3d.data.SineTable sine;
    private int lastPlayerX, lastPlayerZ, lastPlayerY;

    /** {@code asl.w #4,d0 / add.w d0,4(a0)}: how fast a dead flyer drops. */
    private static final int FALL_SPEED = 16;

    /** Counters for checking. */
    public int prowled, killed, sawPlayer, charged, bites, shots, unported;
    /**
     * {@code add.b #n,damagetaken(a2)} onto {@code PLR1_Obj}.
     *
     * The player is an object like any other -- {@code move.b #5,16(a0)} gives
     * their record the type byte for player one -- and an enemy hurts them by
     * writing into it. {@code USEPLR1} reads it back at the top of the next
     * frame and takes it off {@code PLR1_energy}.
     */
    public int playerDamage;

    /**
     * {@code alan}: a walk cycle shared by every enemy on the screen.
     *
     * Thirty-two longwords, eight each of nought to three, advanced one a frame
     * by the main loop. Every enemy adds the same step to its own side, which is
     * why a room full of them moves in time.
     */
    private int walk;

    public Enemy(Level level, AlienControl alien, MoveObject mover, Rand rand) {
        this.lv = level;
        this.alien = alien;
        this.mover = mover;
        this.rand = rand;
    }

    /** {@code move.l (a0)+,alframe}: one step of the shared cycle. */
    public void tickWalk() {
        walk = (walk + 1) & 31;
    }

    private int walkFrame() {
        return walk >> 3;                           // dcb.l 8,0 / 8,1 / 8,2 / 8,3
    }

    /** Which kind a type byte names, or null when it has no routine here. */
    public static Kind kindOf(int type) {
        for (Kind k : KINDS) {
            if (k.type() == type) {
                return k;
            }
        }
        return null;
    }

    /** One frame of one enemy. */
    public void update(Kind k, int base, int frames, int px, int pz, int py,
                       int playerZone, boolean playerInTop,
                       int sinval, int cosval) {
        lastPlayerX = px;
        lastPlayerZ = pz;
        lastPlayerY = py;
        lv.data.setS16(base + Obj.SPRITE_SIZE, k.spriteSize());

        // worry is counted down but its top bit kept, so a thing stays awake a
        // little after it leaves view rather than freezing on the spot
        int w = lv.data.u8(base + Obj.WORRY);
        lv.data.setU8(base + Obj.WORRY, (w & 128) + Math.max(0, (w & 127) - 1));

        int zone = lv.data.s16(base + Obj.ZONE);
        if (zone < 0 || zone >= lv.zones.length) {
            lv.data.setS16(base + Obj.GRAPHIC_ROOM, zone);
            return;                                 // .notthisone
        }
        boolean inTop = lv.data.u8(base + Obj.IN_TOP) != 0;

        if (lv.data.u8(base + Obj.NUM_LIVES) == 0) {
            dying(k, base, frames, zone, inTop);
            return;
        }

        // .notdying: seeing the player and having waited long enough is an attack
        boolean sees = (lv.data.u8(base + Obj.FLAGS) & Obj.SEES_PLAYER1) != 0;
        int third = lv.data.s16(base + Obj.THIRD_TIMER);
        if (sees) {
            if (third <= 0) {
                attack(k, base, frames, zone, inTop, px, pz, py, sinval, cosval);
                return;
            }
            lv.data.setS16(base + Obj.THIRD_TIMER, M68k.w(third - frames));
        } else {
            // .cantseeplayer: a fresh wait of between twenty and eighty-three
            lv.data.setS16(base + Obj.THIRD_TIMER,
                           ((rand.next() >>> 4) & 63) + 20);
        }
        lv.data.setS16(base + Obj.FOURTH_TIMER, ATTACK_WAIT);

        prowl(k, base, frames, zone, inTop, px, pz, py, playerZone, playerInTop);
    }

    /**
     * The dying frames.
     *
     * {@code ThirdTimer} counts down to nothing and indexes the animation, so it
     * runs backwards through the list, and the body settles lower than it stood.
     */
    private void dying(Kind k, int base, int frames, int zone, boolean inTop) {
        if (k.falls()) {
            // it drops until it meets the floor and then bursts, which is where
            // ExplodeIntoBits would go; without that it simply lies there
            int floorAt = floorOf(zone, inTop) - k.standOff();
            int h = lv.data.s16(base + Obj.HEIGHT) + frames * FALL_SPEED;
            lv.data.setS16(base + Obj.HEIGHT, M68k.w(Math.min(h, floorAt)));
            lv.data.setS16(base + Obj.GRAPHIC_ROOM, lv.data.s16(base + Obj.ZONE));
            return;
        }
        int d1 = Math.max(0, lv.data.s16(base + Obj.THIRD_TIMER) - frames);
        lv.data.setS16(base + Obj.THIRD_TIMER, d1);
        lv.data.setS16(base + Obj.FRAME,
                       k.dying()[Math.min(d1, k.dying().length - 1)]);
        lv.data.setU8(base + Obj.NUM_LIVES, 0);
        settle(base, zone, inTop, k.deadOff());
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, lv.data.s16(base + Obj.ZONE));
    }

    /** {@code .waitandsee} onward: walk, take what has been done, and look. */
    private void prowl(Kind k, int base, int frames, int zone, boolean inTop,
                       int px, int pz, int py, int playerZone,
                       boolean playerInTop) {
        int pt = lv.data.s16(base + Obj.POINT);
        if (pt < 0 || pt >= lv.objectPointX.length) {
            return;
        }
        int oldX = lv.objectPointX[pt];
        int oldZ = lv.objectPointZ[pt];
        int facing = lv.data.s16(base + Obj.FACING) & 8190;

        drawAs(k, base, alien.viewpointToDraw(oldX, oldZ, facing, px, pz));

        // muls TempFrames,d2: a step is its top speed times the frames elapsed
        int speed = M68k.muls(lv.data.s16(base + Obj.MAX_SPEED), frames);
        long step = alien.goInDirection(oldX, oldZ, facing, speed);

        // move.b ObjInTop(a0),StoodInTop, and back again after the move
        mover.enterStorey(inTop);
        mover.move(zone, oldX, oldZ, AlienControl.newX(step),
                   AlienControl.newZ(step), py, py, k.extLen(), k.thingHeight(),
                   STEP_UP, STEP_DOWN, WALL_FLAGS, AWAY_FROM_WALL);
        lv.objectPointX[pt] = mover.x;
        lv.objectPointZ[pt] = mover.z;
        lv.data.setS16(base + Obj.ZONE, mover.zone);
        lv.data.setU8(base + Obj.IN_TOP, mover.stoodInTop ? 0xff : 0);
        inTop = mover.stoodInTop;
        zone = mover.zone;
        prowled++;

        if (mover.hitWall) {
            lv.data.setS16(base + Obj.OBJ_TIMER, -1);
        }
        settle(base, zone, inTop, k.standOff());

        if (takeDamage(base, zone)) {
            return;                                 // .noexplode, then rts
        }

        // .keepsamedir: the walk timer, and a new direction when it runs out
        int timer = M68k.w(lv.data.s16(base + Obj.OBJ_TIMER) - frames);
        if (timer < 0) {
            int want;
            if (k.turns()) {
                // GetRand / lsr #4 / and #255 / sub #128 / add d0,d0: a turn of
                // up to a quarter either way, not a fresh angle
                int turn = (((rand.next() >>> 4) & 255) - 128) * 2;
                want = M68k.w(lv.data.s16(base + Obj.FACING) + turn) & 8190;
            } else {
                want = rand.next() & 8190;
            }
            lv.data.setS16(base + Obj.FACING, want);
            lv.data.setS16(base + Obj.OBJ_TIMER, WALK_TIME);
        } else {
            lv.data.setS16(base + Obj.OBJ_TIMER, timer);
        }

        boolean sees = alien.canItBeSeen(zone, playerZone, inTop, playerInTop);
        lv.data.setU8(base + Obj.FLAGS, sees ? Obj.SEES_PLAYER1 : 0);
        if (sees) {
            sawPlayer++;
        }
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, zone);
    }

    /**
     * The attack: close on the player, then bite or shoot.
     *
     * The alien circles -- {@code RunAround} pushes the point it heads for
     * sideways, so it arrives at an angle and on the side the player is not
     * facing -- and stops at a hundred and sixty units to bite. The marine walks
     * straight in, stops at eighty, and shoots from wherever it is: a roll
     * against the squared distance, four damage on a hit, and then a cooldown of
     * between fifty and three hundred and five frames before it may attack
     * again.
     */
    private void attack(Kind k, int base, int frames, int zone, boolean inTop,
                        int px, int pz, int py, int sinval, int cosval) {
        int pt = lv.data.s16(base + Obj.POINT);
        if (pt < 0 || pt >= lv.objectPointX.length) {
            return;
        }
        int oldX = lv.objectPointX[pt];
        int oldZ = lv.objectPointZ[pt];
        charged++;

        drawAs(k, base, alien.viewpointToDraw(
                oldX, oldZ, lv.data.s16(base + Obj.FACING) & 8190, px, pz));

        int aimX = px, aimZ = pz;
        if (k.circles()) {
            long aim = alien.runAround(oldX, oldZ, px, pz, oldX, oldZ, px, pz,
                                       sinval, cosval);
            aimX = AlienControl.newX(aim);
            aimZ = AlienControl.newZ(aim);
        }
        int speed = M68k.muls(lv.data.s16(base + Obj.MAX_SPEED), frames);
        AlienControl.Approach a = alien.headTowardsAng(oldX, oldZ, aimX, aimZ,
                                                       speed, k.range());

        mover.enterStorey(inTop);
        mover.move(zone, oldX, oldZ, a.x(), a.z(), py, py, k.extLen(),
                   k.thingHeight(), STEP_UP, STEP_DOWN, WALL_FLAGS,
                   AWAY_FROM_WALL);
        lv.objectPointX[pt] = mover.x;
        lv.objectPointZ[pt] = mover.z;
        lv.data.setS16(base + Obj.ZONE, mover.zone);
        lv.data.setS16(base + Obj.FACING, a.facing());   // move.w AngRet,Facing
        lv.data.setU8(base + Obj.IN_TOP, mover.stoodInTop ? 0xff : 0);
        inTop = mover.stoodInTop;
        zone = mover.zone;
        settle(base, zone, inTop, k.standOff());

        int wait = lv.data.s16(base + Obj.FOURTH_TIMER);
        if (k.bite() != 0 && a.gotThere()) {
            // .OKtomunch: within range, and the bite timer run out
            if (wait <= 0) {
                lv.data.setS16(base + Obj.FOURTH_TIMER, k.biteGap());
                playerDamage += k.bite();
                bites++;
            } else {
                lv.data.setS16(base + Obj.FOURTH_TIMER, M68k.w(wait - frames));
            }
        } else if (k.shoot() != 0) {
            lv.data.setS16(base + Obj.FOURTH_TIMER, M68k.w(wait - frames));
        }

        if (takeDamage(base, zone)) {
            return;
        }

        if (k.shoot() != 0 && lv.data.s16(base + Obj.FOURTH_TIMER) <= SHOOT_AT) {
            shootAt(k, base, a.dist());
        }
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, zone);
    }

    /**
     * The marine's shot: a roll against the squared distance.
     *
     * The same shape as the player's own {@code FIREBULLETS} -- a random number
     * under fifteen bits, shifted up two, against the squared distance shifted
     * down six -- so the further away it stands the more often it misses. On a
     * miss the original fires a visible tracer instead; either way the cooldown
     * that follows is the same.
     */
    private void shootAt(Kind k, int base, int dist) {
        lv.data.setS16(base + Obj.THIRD_TIMER, (rand.next() & 255) + 50);
        lv.data.setS16(base + Obj.BRIGHT, -100);    // move.w #-100,2(a0)
        shots++;
        // move.w #3,Samplenum / move.w #200,Noisevol
        sound.play(Sound.ENEMY_SHOT, 0, 0, 200, 2, false);
        if (k.projectile()) {
            // jsr FireAtPlayer1: a bullet into NastyShotData, aimed ahead of
            // where the player is. SHOTTYPE names the gun record it takes its
            // gravity and flags from, SHOTPOWER what it does on arrival.
            if (bullets == null || guns == null) {
                unported++;
                return;
            }
            int pt = lv.data.s16(base + Obj.POINT);
            if (pt < 0 || pt >= lv.objectPointX.length) {
                return;
            }
            bullets.fireEnemy(guns, k.shotType(), k.shoot(), k.shotSpeed(),
                              k.shotShift(), lv.objectPointX[pt],
                              lv.objectPointZ[pt],
                              lv.data.s16(base + Obj.HEIGHT) << 7,
                              lv.data.s16(base + Obj.ZONE),
                              lv.data.u8(base + Obj.IN_TOP) != 0,
                              lastPlayerX, lastPlayerZ, lastPlayerY);
            return;
        }
        long d1 = ((long) dist * dist) >> 6;
        long d0 = (rand.next() & 0x7fff) << 2;
        if (d0 > d1) {
            playerDamage += k.shoot();              // add.b #4,damagetaken(a1)
        }
    }

    /**
     * {@code asl.l #2,d0 / add.l alframe,d0 / move.l d0,8(a0)}.
     *
     * One longword holds the graphic slot in its high word and the frame in its
     * low one, so the side times four, plus the shared walk step, plus the slot,
     * is a single write.
     */
    private void drawAs(Kind k, int base, int side) {
        lv.data.setS32(base + Obj.SLOT,
                       (k.slot() << 16) | ((side << 2) + walkFrame()));
    }

    /**
     * {@code damagetaken}, which the shot adds to and this takes off the lives.
     *
     * @return true when it has just died, which ends the frame for it
     */
    private boolean takeDamage(int base, int zone) {
        int hurt = lv.data.u8(base + Obj.DAMAGE_TAKEN);
        if (hurt == 0) {
            return false;
        }
        int lives = lv.data.u8(base + Obj.NUM_LIVES) - hurt;
        lv.data.setU8(base + Obj.DAMAGE_TAKEN, 0);
        if (lives <= 0) {
            lv.data.setU8(base + Obj.NUM_LIVES, 0);
            lv.data.setS16(base + Obj.THIRD_TIMER, 25);
            killed++;
            // move.w #14,Samplenum / move.w #400,Noisevol when it comes apart,
            // and its own scream when it merely dies
            sound.play(hurt > 1 ? Sound.SPLAT_POP : Sound.SCREAM,
                       0, 0, hurt > 1 ? 400 : 200, 1, false);
            // cmp.b #1,d2 / ble .noexplode: a scratch that happens to be fatal
            // leaves a body, anything harder than that comes apart
            if (hurt > 1 && bullets != null && sine != null) {
                int pt = lv.data.s16(base + Obj.POINT);
                if (pt >= 0 && pt < lv.objectPointX.length) {
                    bullets.explodeIntoBits(zone,
                            lv.data.u8(base + Obj.IN_TOP) != 0,
                            lv.objectPointX[pt], lv.objectPointZ[pt],
                            lv.data.s16(base + Obj.HEIGHT) << 7,
                            hurt >> 2, rand, sine);
                }
            }
            lv.data.setS16(base + Obj.GRAPHIC_ROOM, zone);
            return true;
        }
        lv.data.setU8(base + Obj.NUM_LIVES, lives);
        return false;
    }

    /** {@code asr.l #7,d0 / sub.w #n,d0 / move.w d0,4(a0)}. */
    private void settle(int base, int zone, boolean inTop, int off) {
        if (zone < 0 || zone >= lv.zones.length) {
            return;
        }
        lv.data.setS16(base + Obj.HEIGHT, M68k.w(floorOf(zone, inTop) - off));
    }

    /** {@code move.l ToZoneFloor(a1),d0 / asr.l #7,d0}, or the upper storey's. */
    private int floorOf(int zone, boolean inTop) {
        if (zone < 0 || zone >= lv.zones.length) {
            return 0;
        }
        return (inTop ? lv.zone(zone).upperFloorHeight
                      : lv.zone(zone).floorHeight) >> 7;
    }

    /**
     * {@code GetRand}, which the enemies lean on for every choice they make.
     *
     * The original's is seeded from the video beam position, so it cannot be
     * reproduced exactly and there is nothing to be faithful to. What matters is
     * only that it is cheap and does not repeat, so this is a plain linear
     * congruential generator with a fixed seed -- which has the side benefit
     * that a run can be repeated when checking.
     */
    public static final class Rand {
        private int state = 0x2f6e2b1;

        public int next() {
            state = state * 1103515245 + 12345;
            return (state >>> 16) & 0xffff;
        }
    }
}
