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
                       int shotType, int shotSpeed, int shotShift,
                       Traits traits) {

        /** The five kinds the first levels place, which need no traits. */
        Kind(String name, int type, int spriteSize, int extLen,
             int thingHeight, int standOff, int deadOff, int slot,
             int[] dying, int range, boolean circles,
             int bite, int biteGap, int shoot,
             boolean projectile, boolean falls, boolean turns,
             int shotType, int shotSpeed, int shotShift) {
            this(name, type, spriteSize, extLen, thingHeight, standOff, deadOff,
                 slot, dying, range, circles, bite, biteGap, shoot, projectile,
                 falls, turns, shotType, shotSpeed, shotShift, Traits.NONE);
        }
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

    /** What becomes of a thing once its lives run out. */
    public enum Death {
        /** {@code move.w #25,ThirdTimer(a0)}: it plays its dying frames and lies there. */
        BODY,
        /** {@code move.w #-1,12(a0)} whatever hit it: it comes apart and is gone. */
        VANISH,
        /**
         * {@code move.b #4,16(a0) / move.b #%1000,17(a0)}: it turns into a key.
         *
         * The record is rewritten in place -- type four, granting condition
         * bit three, drawn as frame three of the keys sheet at sixteen by
         * sixteen -- so from the next frame {@code ItsAKey} looks after it.
         */
        KEY
    }

    /**
     * What the files written after the first five differ by.
     *
     * halfworm.s, tree.s and bigredthing.s are copies of one another -- fewer
     * than three hundred lines apart in nine hundred -- but they are copies of
     * MutantMarine.s at a further remove, and some of what changed is how they
     * behave rather than a number. All three stand their ground to fire:
     * {@code move.w oldx,(a1)} where the marine writes {@code newx}, so they turn
     * to face the player and stay where they are. Two fire in bursts, counting
     * {@code ThirdTimer} below nought a shot at a time. All three wear armour:
     * {@code asr.b #2,d2} takes a quarter of every hit.
     *
     * @param scale       {@code move.w #n,6(a0)}: the width and height the
     *                    sprite is scaled to, rewritten every frame, or -1
     * @param armour      the shift taken off the damage, nought for none
     * @param armourFloor whether a hit that the shift takes to nothing still
     *                    counts as one -- {@code bne .notovercoat / moveq #1}
     *                    -- rather than being shrugged off
     * @param death       what it leaves
     * @param goneAt      damage at or past this leaves no body
     * @param pieces      how many bits it comes apart into, or -1 for the
     *                    quarter of the damage the marines use
     * @param attackWait  {@code move.w #n,FourthTimer(a0)} while prowling
     * @param walkTime    {@code move.w #n,ObjTimer(a0)}: how long one prowl
     *                    direction lasts
     * @param holdsGround whether it fires from where it stands
     * @param burst       shots in a burst, nought for the marine's one at a time
     * @param coolShift   {@code lsr.w #n,d0} before the mask, for the wait
     *                    after a burst
     * @param coolMask    and the mask
     * @param coolBase    and what is added
     * @param frontFrame  the attack frame when it faces the player square on
     *                    -- {@code asl.l #2,d0 / bne.s .notflfl / move.l #16,d0}
     *                    -- or -1 to keep the side
     * @param fireFrame   {@code move.l #$d0011,8(a0)}: the frame of the shot
     * @param shotOffMult {@code SHOTOFFMULT}: how far to one side the shot
     *                    leaves from, in 256ths of a frame's travel
     * @param shotYOff    {@code SHOTYOFF}
     * @param spawnsEye   whether its attack is an eyeball rather than a shot
     * @param scream      {@code screamsound}
     * @param walkBase    the frame the walk cycle adds to, when it ignores the
     *                    side it is seen from; -1 when it does not
     */
    public record Traits(int scale, int armour, boolean armourFloor,
                         Death death, int goneAt, int pieces,
                         int attackWait, int walkTime, boolean holdsGround,
                         int burst, int coolShift, int coolMask, int coolBase,
                         int frontFrame, int fireFrame,
                         int shotOffMult, int shotYOff, boolean spawnsEye,
                         int scream, int walkBase) {

        /** What the five files of the first levels do. */
        public static final Traits NONE = new Traits(
                -1, 0, false, Death.BODY, 40, -1, 25, 50, false,
                0, 0, 255, 50, -1, -1, 0, 0, false, Sound.SCREAM, -1);
    }

    /**
     * {@code ItsAHalfWorm}: forty-nine of them, from the sixth level on.
     *
     * It turns to face the player and stays put, then fires four plasma bolts
     * thirty frames apart from a point to its left -- {@code SHOTOFFMULT} of
     * seven hundred -- and waits a hundred frames or more before the next four.
     * Its armour takes a quarter of every hit, and the test that would let a
     * hard enough hit leave no body compares the quartered damage against
     * eighty, which it never reaches: a worm always leaves its body.
     */
    public static final Kind WORM = new Kind(
            "worm", 13, 44 * 256 + 49, 80, 200 * 128, 100, 100, 13,
            run(6, 20, 10, 19, 10, 18), 80, false, 0, 0, 10,
            true, false, false, 5, 16, 3,
            new Traits(90 * 256 + 100, 2, true, Death.BODY, 80, -1, 30, 50,
                       true, 4, 4, 255, 100, 16, 17, 700, -10 * 128, false,
                       Sound.WORM_PAIN, -1));

    /**
     * {@code ItsABigRedThing}: the one at the end of the last level.
     *
     * Twice the reach of anything else, four hundred and fifty-six units tall,
     * and it fires rockets two at a time from its right. Killed, it bursts and
     * leaves a key behind in its own record: that key is condition bit three,
     * which is what opens the way out of the level.
     */
    public static final Kind BIG_RED = new Kind(
            "wellhard", 14, 63 * 256 + 63, 160, 456 * 128, 256, 128, 14,
            new int[0], 80, false, 0, 0, 10,
            true, false, false, 2, 32, 4,
            new Traits(0xfefe, 2, true, Death.KEY, 256, 7, 70, 150,
                       true, 2, 0, 127, 100, 16, 17, -700, -10 * 128, false,
                       Sound.WORM_PAIN, -1));

    /**
     * {@code ItsATree}: it does not shoot, it sows.
     *
     * Where the worm would fire, the tree takes a free record from
     * {@code OtherNastyData} and starts an eyeball in it, then waits three
     * hundred frames or more. Its armour has no floor: anything under four
     * damage does nothing at all, not even a cry. Killed, it is gone.
     */
    public static final Kind TREE = new Kind(
            "tree", 16, 31 * 256 + 31, 80, 200 * 128, 100, 64, 15,
            new int[0], 80, false, 0, 0, 0,
            false, false, false, 0, 0, 0,
            new Traits(128 * 256 + 128, 2, false, Death.VANISH, 0, 7, 30, 50,
                       true, 0, 0, 127, 300, 16, 17, 0, 0, true,
                       Sound.WORM_PAIN, -1));

    /**
     * {@code ItsAEyeBall}: what a tree sows, and nothing places directly.
     *
     * EyeBall.s is FlyingScalyBall.s drawn from the tree's sheet: frames
     * eighteen onward with the walk cycle added and no sides at all, a range of
     * eighty rather than a hundred and twenty, and no dying fall -- whatever
     * kills it, it bursts into ten pieces and is gone.
     */
    public static final Kind EYE = new Kind(
            "eyeball", 17, 0x0f1f, 160, 96 * 128, 64, 64, 15,
            new int[0], 80, false, 0, 0, 5, true, false, true, 0, 16, 3,
            new Traits(0x1020, 0, false, Death.VANISH, 0, 9, 25, 50, false,
                       0, 0, 255, 50, -1, -1, 0, 0, false, Sound.LOW_SCREAM,
                       18));

    public static final Kind[] KINDS = {NASTY, MARINE, TOUGH_MARINE,
                                        FLAME_MARINE, FLYING, WORM, BIG_RED,
                                        TREE, EYE};

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
        routine(k, base, frames, px, pz, py, playerZone, playerInTop, sinval,
                cosval);
        // move.w #80,d0 / jsr FindCloseRoom: on the way out of nearly every
        // path of halfworm.s and FlyingScalyBall.s, and of none of the others
        if (closeRoom != null && (k == WORM || k == FLYING)
                && lv.data.s16(base + Obj.ZONE) >= 0) {
            lv.data.setS16(base + Obj.GRAPHIC_ROOM,
                           closeRoom.find(base, CLOSE_ROOM, k.extLen(), px, pz));
        }
    }

    /** Things bumped into instead of moving, for checking. */
    public int bumped;
    /** {@code Collision}, which keeps things out of each other. */
    public Collision collision;

    /** {@code move.l #%100000,CollideFlags}: player one alone. */
    private static final int PLAYER1_ONLY = 0b100000;
    /** {@code move.l #%11111111110111000001,CollideFlags}, the alien's second ask. */
    private static final int NASTY_ATTACK = 0b11111111110111000001;

    /** {@code move.l #n,CollideFlags} before the prowl's move, file by file. */
    private static int prowlMask(Kind k) {
        if (k == NASTY) {
            return 0b111111110111100001;
        }
        if (k == WORM) {
            return 0b1111111110111100001;
        }
        if (k == TREE) {
            return 0b11011111110111100001;
        }
        if (k == BIG_RED) {
            return 0b110111100001;                 // a move.w, so a word's worth
        }
        return 0b11111111110111100001;             // the marines, the flyers
    }

    /**
     * {@code move.w 4(a0),d0 / sub.w #n,d0 / ext.l d0 / asl.l #7,d0 /
     * move.l d0,newy}: the top of the thing, which is what it moves and
     * collides with. It was the player's height here once, so every enemy
     * took its steps as if it stood where the player does.
     */
    private int topOf(Kind k, int base) {
        int lift = k == BIG_RED ? 200 : k.standOff();   // sub.w #200 for that one
        return M68k.w(lv.data.s16(base + Obj.HEIGHT) - lift) << 7;
    }

    /** {@code move.w #80,d0} before {@code FindCloseRoom}. */
    private static final int CLOSE_ROOM = 80;
    /** Which room a wide sprite is drawn in. */
    public CloseRoom closeRoom;

    private void routine(Kind k, int base, int frames, int px, int pz, int py,
                         int playerZone, boolean playerInTop,
                         int sinval, int cosval) {
        lastPlayerX = px;
        lastPlayerZ = pz;
        lastPlayerY = py;
        lv.data.setS16(base + Obj.SPRITE_SIZE, k.spriteSize());
        Traits t = k.traits();
        if (t.scale() >= 0) {
            lv.data.setS16(base + Obj.SCALE, t.scale());   // move.w #n,6(a0)
        }

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
                if (t.holdsGround()) {
                    standAndFire(k, base, frames, zone, inTop, px, pz, py,
                                 playerZone, playerInTop);
                } else {
                    attack(k, base, frames, zone, inTop, px, pz, py, sinval,
                           cosval);
                }
                return;
            }
            third = M68k.w(third - frames);
            // bge .waitandsee / move.w #0,ThirdTimer(a0): the later files
            // clamp the wait at nought, so a burst always starts from the top
            if (third < 0 && t != Traits.NONE) {
                third = 0;
            }
            lv.data.setS16(base + Obj.THIRD_TIMER, third);
        } else {
            // .cantseeplayer: a fresh wait of between twenty and eighty-three
            lv.data.setS16(base + Obj.THIRD_TIMER,
                           ((rand.next() >>> 4) & 63) + 20);
        }
        lv.data.setS16(base + Obj.FOURTH_TIMER, t.attackWait());

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
        if (k.dying().length == 0) {
            // the tree and the big red thing never get here -- one is gone and
            // the other is a key by the time its lives read nought -- but
            // their files still count a frame up to nine and stop
            settle(base, zone, inTop, k.deadOff());
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

        int top = topOf(k, base);
        int nx = AlienControl.newX(step), nz = AlienControl.newZ(step);
        // move.l #n,CollideFlags / jsr Collision / tst.b hitwall / beq .canmove:
        // bumping into something is the same as a wall -- stay, and choose a
        // new direction
        if (collision != null && collision.blocked(pt, k.type(), inTop, top,
                k.thingHeight(), oldX, oldZ, nx, nz, prowlMask(k))) {
            lv.data.setS16(base + Obj.OBJ_TIMER, -1);
            bumped++;
        } else {
            // move.b ObjInTop(a0),StoodInTop, and back again after the move
            mover.enterStorey(inTop);
            mover.move(zone, oldX, oldZ, nx, nz, top, top, k.extLen(),
                       k.thingHeight(), STEP_UP, STEP_DOWN, WALL_FLAGS,
                       AWAY_FROM_WALL);
            lv.objectPointX[pt] = mover.x;
            lv.objectPointZ[pt] = mover.z;
            lv.data.setS16(base + Obj.ZONE, mover.zone);
            lv.data.setU8(base + Obj.IN_TOP, mover.stoodInTop ? 0xff : 0);
            inTop = mover.stoodInTop;
            zone = mover.zone;
            if (mover.hitWall) {
                lv.data.setS16(base + Obj.OBJ_TIMER, -1);
            }
        }
        prowled++;
        settle(base, zone, inTop, k.standOff());

        if (takeDamage(k, base, zone)) {
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
            lv.data.setS16(base + Obj.OBJ_TIMER, k.traits().walkTime());
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
        int top = topOf(k, base);
        boolean there = a.gotThere();
        boolean stay = false;
        if (collision != null && k == NASTY) {
            // move.l #%100000,CollideFlags: the player first, and touching them
            // is arriving -- st GotThere, and it bites from where it stands
            if (collision.blocked(pt, k.type(), inTop, top, k.thingHeight(),
                                  oldX, oldZ, a.x(), a.z(), PLAYER1_ONLY)) {
                stay = true;
                there = true;
                bumped++;
            } else if (collision.blocked(pt, k.type(), inTop, top,
                    k.thingHeight(), oldX, oldZ, a.x(), a.z(), NASTY_ATTACK)) {
                stay = true;
                bumped++;
            }
        }
        if (!stay) {
            mover.enterStorey(inTop);
            mover.move(zone, oldX, oldZ, a.x(), a.z(), top, top, k.extLen(),
                       k.thingHeight(), STEP_UP, STEP_DOWN, WALL_FLAGS,
                       AWAY_FROM_WALL);
            lv.objectPointX[pt] = mover.x;
            lv.objectPointZ[pt] = mover.z;
            lv.data.setS16(base + Obj.ZONE, mover.zone);
            lv.data.setU8(base + Obj.IN_TOP, mover.stoodInTop ? 0xff : 0);
            inTop = mover.stoodInTop;
            zone = mover.zone;
        }
        lv.data.setS16(base + Obj.FACING, a.facing());   // move.w AngRet,Facing
        settle(base, zone, inTop, k.standOff());

        int wait = lv.data.s16(base + Obj.FOURTH_TIMER);
        if (k.bite() != 0 && there) {
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

        if (takeDamage(k, base, zone)) {
            return;
        }

        if (k.shoot() != 0 && lv.data.s16(base + Obj.FOURTH_TIMER) <= SHOOT_AT) {
            shootAt(k, base, a.dist());
        }
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, zone);
    }

    /** {@code cmp.w #20,FourthTimer(a0) / bge .cantshoot}, and what it is reset to. */
    private static final int FIRE_BELOW = 20, FIRE_GAP = 30;
    /** {@code move.w #50,ThirdTimer(a0)} when the attack timer runs right out. */
    private static final int GIVE_UP = 50;

    /**
     * {@code HalfWormAttack} and its two copies: turn, stand, fire.
     *
     * The thing turns to face the player but stays where it is. Every thirty
     * frames it fires -- or, for the tree, sows an eyeball -- and each shot of
     * a burst counts {@code ThirdTimer} one further below nought, which keeps
     * the routine choosing this branch. When the count passes the burst's
     * length the timer is set to a long positive wait and the thing goes back
     * to prowling until it runs out.
     *
     * The original does call {@code MoveObject} here, towards the player as
     * the marine would, and then writes the old position back -- but takes the
     * zone from where the move ended. That would leave the thing standing in
     * one room while listed in the next, so this keeps both the position and
     * the zone it had.
     */
    private void standAndFire(Kind k, int base, int frames, int zone,
                              boolean inTop, int px, int pz, int py,
                              int playerZone, boolean playerInTop) {
        Traits t = k.traits();
        lastPlayerX = px;
        lastPlayerZ = pz;
        lastPlayerY = py;
        // sub.w d0,FourthTimer(a0) / bgt.s .oktoshoot / move.w #50,ThirdTimer
        int wait = M68k.w(lv.data.s16(base + Obj.FOURTH_TIMER) - frames);
        lv.data.setS16(base + Obj.FOURTH_TIMER, wait);
        if (wait <= 0) {
            lv.data.setS16(base + Obj.THIRD_TIMER, GIVE_UP);
        }
        int pt = lv.data.s16(base + Obj.POINT);
        if (pt < 0 || pt >= lv.objectPointX.length) {
            return;
        }
        int x = lv.objectPointX[pt];
        int z = lv.objectPointZ[pt];
        charged++;

        // bsr ViewpointToDraw / asl.l #2,d0 / bne.s .notflfl / move.l #16,d0:
        // square on, it shows the frame it fires from instead of its front
        int side = alien.viewpointToDraw(x, z,
                lv.data.s16(base + Obj.FACING) & 8190, px, pz);
        int frame = side << 2;
        if (frame == 0 && t.frontFrame() >= 0) {
            frame = t.frontFrame();
        }
        lv.data.setS32(base + Obj.SLOT, (k.slot() << 16) | frame);

        int speed = M68k.muls(lv.data.s16(base + Obj.MAX_SPEED), frames);
        AlienControl.Approach a = alien.headTowardsAng(x, z, px, pz, speed,
                                                       k.range());
        lv.data.setS16(base + Obj.FACING, a.facing());  // move.w AngRet,Facing
        settle(base, zone, inTop, k.standOff());

        if (takeDamage(k, base, zone)) {
            return;
        }

        if (lv.data.s16(base + Obj.FOURTH_TIMER) < FIRE_BELOW) {
            lv.data.setS16(base + Obj.FOURTH_TIMER, FIRE_GAP);
            int third;
            if (t.spawnsEye()) {
                third = (rand.next() & t.coolMask()) + t.coolBase();
            } else {
                // sub.w #1,d0 / cmp.w #-3,d0 / bge.s .noreset
                third = lv.data.s16(base + Obj.THIRD_TIMER) - 1;
                if (third < -(t.burst() - 1)) {
                    third = ((rand.next() >>> t.coolShift()) & t.coolMask())
                            + t.coolBase();
                }
            }
            lv.data.setS16(base + Obj.THIRD_TIMER, third);
            lv.data.setS32(base + Obj.SLOT, (k.slot() << 16) | t.fireFrame());
            if (t.spawnsEye()) {
                spawnEye(base);
            } else {
                lv.data.setS16(base + Obj.BRIGHT, -100);   // move.w #-100,2(a0)
                shots++;
                fire(k, base);
            }
        }

        // .cantshoot: a hiss now and then, three hundred frames or more apart
        hiss(base, frames, Sound.HISS);

        boolean sees = alien.canItBeSeen(zone, playerZone, inTop, playerInTop);
        lv.data.setU8(base + Obj.FLAGS, sees ? Obj.SEES_PLAYER1 : 0);
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, zone);
    }

    /**
     * {@code sub.w d0,SecTimer(a0) / bge.s .nohiss}: the noise a thing makes
     * to itself, then a wait of three hundred to five hundred and fifty-five.
     */
    private void hiss(int base, int frames, int sample) {
        int sec = M68k.w(lv.data.s16(base + Obj.SEC_TIMER) - frames);
        if (sec >= 0) {
            lv.data.setS16(base + Obj.SEC_TIMER, sec);
            return;
        }
        sound.play(sample, 0, 0, 100, lv.data.u8(base + 1), false);
        lv.data.setS16(base + Obj.SEC_TIMER, ((rand.next() >>> 6) & 255) + 300);
    }

    /**
     * {@code jsr FireAtPlayer1}, with what the caller loaded into
     * {@code SHOTTYPE}, {@code SHOTPOWER}, {@code SHOTSPEED},
     * {@code SHOTSHIFT}, {@code SHOTOFFMULT} and {@code SHOTYOFF}.
     *
     * {@code move.w #9,Samplenum} before the call: the later files fire with
     * the baddie gun, which {@code FireAtPlayer1} plays at a hundred.
     */
    private void fire(Kind k, int base) {
        sound.play(Sound.BADDIE_GUN, 0, 0, 100, lv.data.u8(base + 1), false);
        if (bullets == null || guns == null) {
            unported++;
            return;
        }
        int pt = lv.data.s16(base + Obj.POINT);
        if (pt < 0 || pt >= lv.objectPointX.length) {
            return;
        }
        Traits t = k.traits();
        bullets.fireEnemy(guns, k.shotType(), k.shoot(), k.shotSpeed(),
                          k.shotShift(), t.shotOffMult(),
                          lv.objectPointX[pt], lv.objectPointZ[pt],
                          (lv.data.s16(base + Obj.HEIGHT) << 7) + t.shotYOff(),
                          lv.data.s16(base + Obj.ZONE),
                          lv.data.u8(base + Obj.IN_TOP) != 0,
                          lastPlayerX, lastPlayerZ, lastPlayerY);
    }

    /**
     * The tree's {@code .findonefree} in {@code OtherNastyData}.
     *
     * The third pool of twenty records, straight after the enemy shots --
     * jg.s finds it with {@code add.l #64*20,a2} and the level header does not
     * name it. The record is made an eyeball where the tree stands, facing the
     * way it faces, with ten lives, a top speed of five and all three timers at
     * a hundred. Its storey byte is not written, so it keeps whatever the
     * record last held; and with no free record there is simply no eyeball.
     */
    public static final int EYE_POOL = 20;

    private void spawnEye(int tree) {
        int pool = lv.ptrEnemyShots + Obj.SIZE * Bullets.POOL;
        for (int i = 0; i < EYE_POOL; i++) {
            int at = pool + i * Obj.SIZE;
            if (!lv.data.inRange(at, Obj.SIZE)) {
                return;
            }
            if (lv.data.s16(at + Obj.ZONE) >= 0) {
                continue;
            }
            lv.data.setS16(at + Obj.FOURTH_TIMER, 100);
            lv.data.setS16(at + Obj.THIRD_TIMER, 100);
            lv.data.setS16(at + Obj.OBJ_TIMER, 100);
            lv.data.setS16(at + Obj.MAX_SPEED, 5);
            lv.data.setU8(at + Obj.TYPE, EYE.type());
            lv.data.setS16(at + Obj.HEIGHT, lv.data.s16(tree + Obj.HEIGHT));
            lv.data.setU8(at + Obj.NUM_LIVES, 10);
            lv.data.setU8(at + Obj.DAMAGE_TAKEN, 0);
            lv.data.setS16(at + Obj.FACING, lv.data.s16(tree + Obj.FACING));
            int from = lv.data.s16(tree + Obj.POINT);
            int to = lv.data.s16(at + Obj.POINT);
            if (from >= 0 && from < lv.objectPointX.length
                    && to >= 0 && to < lv.objectPointX.length) {
                lv.objectPointX[to] = lv.objectPointX[from];
                lv.objectPointZ[to] = lv.objectPointZ[from];
            }
            lv.data.setS16(at + Obj.ZONE, lv.data.s16(tree + Obj.ZONE));
            lv.data.setU8(at + Obj.WORRY, 0xff);       // st worry(a2)
            eyesSown++;
            return;
        }
    }

    /** Eyeballs a tree has started, for checking. */
    public int eyesSown;

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
                              k.shotShift(), 0, lv.objectPointX[pt],
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
        int base4 = k.traits().walkBase();
        // move.l alframe,d0 / add.l #$F0012,d0: the eyeball has no sides, only
        // the walk cycle added to frame eighteen
        int frame = base4 >= 0 ? base4 + walkFrame() : (side << 2) + walkFrame();
        lv.data.setS32(base + Obj.SLOT, (k.slot() << 16) | frame);
    }

    /**
     * {@code damagetaken}, which the shot adds to and this takes off the lives.
     *
     * @return true when it has just died, which ends the frame for it
     */
    private boolean takeDamage(Kind k, int base, int zone) {
        int raw = lv.data.u8(base + Obj.DAMAGE_TAKEN);
        if (raw == 0) {
            return false;
        }
        Traits t = k.traits();
        int hurt = raw;
        if (t.armour() > 0) {
            // asr.b #2,d2: a signed byte shift, so a hit of a hundred and
            // twenty-eight or more would come out negative -- no gun does that
            hurt = ((byte) raw) >> t.armour();
            if (hurt == 0) {
                if (!t.armourFloor()) {
                    // beq .noscream, and damagetaken is not cleared: hits too
                    // small to count add up until together they do
                    return false;
                }
                hurt = 1;                           // moveq #1,d2
            }
        }
        int lives = lv.data.u8(base + Obj.NUM_LIVES) - hurt;
        if (t != Traits.NONE) {
            lives = (byte) lives;                   // sub.b d2,numlives / bgt
        }
        if (lives > 0) {
            // clr.b damagetaken(a0) is on this path alone. What is dying keeps
            // the byte, and nothing reads it again -- the dying frames never ask.
            lv.data.setU8(base + Obj.DAMAGE_TAKEN, 0);
            lv.data.setU8(base + Obj.NUM_LIVES, lives);
            if (t != Traits.NONE) {
                // .notdeadyet: the later files cry out at every hit
                sound.play(t.scream(), 0, 0, 200, 1, false);
            }
            return false;
        }

        lv.data.setU8(base + Obj.NUM_LIVES, 0);
        killed++;
        // cmp.b #1,d2 / ble .noexplode: a scratch that happens to be fatal
        // leaves a body, anything harder than that comes apart. The tree, the
        // eyeball and the big red thing have the test commented out and always
        // come apart.
        boolean explode = t.death() != Death.BODY || hurt > 1;
        // move.w #14,Samplenum / move.w #400,Noisevol when it comes apart --
        // three hundred for the worm -- and its own scream when it merely dies
        int boom = k == WORM ? 300 : 400;
        sound.play(explode ? Sound.SPLAT_POP : t.scream(),
                   0, 0, explode ? boom : 200, 1, false);
        if (explode && bullets != null && sine != null) {
            int pt = lv.data.s16(base + Obj.POINT);
            if (pt >= 0 && pt < lv.objectPointX.length) {
                // asr.w #2,d2 / tst.w d2 / bgt .ko / moveq #1,d2: a quarter of
                // the damage in pieces, and never fewer than one -- or the fixed
                // count the later files write instead, move.w #7,d2
                int pieces = t.pieces() >= 0 ? t.pieces() : Math.max(1, hurt >> 2);
                bullets.explodeIntoBits(zone,
                        lv.data.u8(base + Obj.IN_TOP) != 0,
                        lv.objectPointX[pt], lv.objectPointZ[pt],
                        lv.data.s16(base + Obj.HEIGHT) << 7,
                        pieces, rand, sine);
            }
        }
        switch (t.death()) {
            case KEY -> {
                becomeKey(base);
                return true;
            }
            case VANISH -> {
                lv.data.setS16(base + Obj.ZONE, -1);    // move.w #-1,12(a0)
                lv.data.setS16(base + Obj.GRAPHIC_ROOM, -1);
                return true;
            }
            default -> { }
        }
        // cmp.b #40,d2 / blt .noexplode: enough force and there is no body left
        // to lie there. move.w #-1,12(a0) puts the record out of every zone,
        // which is how this game removes a thing. The worm's file compares
        // against eighty after its armour has quartered the hit, which nothing
        // reaches: a worm always leaves its body.
        if (hurt >= t.goneAt()) {
            lv.data.setS16(base + Obj.ZONE, -1);
            lv.data.setS16(base + Obj.GRAPHIC_ROOM, -1);
            return true;
        }
        // .noexplode: twenty-five frames of dying to play out
        lv.data.setS16(base + Obj.THIRD_TIMER, 25);
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, zone);
        return true;
    }

    /**
     * {@code move.b #4,16(a0)} and the four writes after it: the record that
     * was an enemy is now a key.
     *
     * Type four, granting {@code %1000} -- condition bit three -- drawn as
     * {@code $50003}, frame three of slot five, which is the keys sheet, at
     * {@code $2020} in the world and {@code $1010} on the sheet. Nothing else
     * changes, so the key lies where the thing fell and {@code ItsAKey} drops it
     * to the floor of that zone on its first frame.
     */
    public static final int KEY_GRANT = 0b1000;

    private void becomeKey(int base) {
        becomeKey(lv, base);
        becameKeys++;
    }

    /** The same five writes, which robot.s makes too. */
    static void becomeKey(Level lv, int base) {
        lv.data.setU8(base + Obj.TYPE, ObjectHandler.TYPE_KEY);
        lv.data.setU8(base + Obj.FLAGS, KEY_GRANT);
        lv.data.setS32(base + Obj.SLOT, 0x50003);
        lv.data.setS16(base + Obj.SCALE, 0x2020);
        lv.data.setS16(base + Obj.SPRITE_SIZE, 0x1010);
    }

    /** Things that turned into a key, for checking. */
    public int becameKeys;

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
