package ab3d.engine;

import ab3d.data.BulletAnims;
import ab3d.data.ColBox;
import ab3d.data.GunData;
import ab3d.data.Level;
import ab3d.m68k.M68k;

/**
 * {@code ItsABullet} and the two ways one gets made, from source/anims,
 * source/PLAYERSHOOT.s and source/aliencontrol.s.
 *
 * There are two pools of twenty records, one for the player's shots and one for
 * the enemies', and both are found the same way: walk until a record's zone word
 * is negative. A shot that runs out of pool simply does not happen, which is why
 * the rocket launcher can be fired faster than its rockets can fly.
 *
 * What a bullet is comes from the gun that fired it. The record at sixteen,
 * eighteen and twenty of {@code PLR1_GunData} holds its gravity, its flags and a
 * push added to its vertical speed, and the grenade launcher is the one that
 * uses all three: sixty, three and minus a thousand -- so a grenade is lobbed
 * upward, falls, and bounces, because bit nought of the flags means bounce and
 * bit one means halve the horizontal speed each time it does.
 *
 * {@code EnemyFlags} is what a bullet may hit, as a bit per type. The player's
 * carry the same mask the aim uses; an enemy's carry {@code %100000100000},
 * which is bits five and eleven -- the two players and nothing else. So enemy
 * fire cannot hit other enemies, and the same loop serves both.
 */
public final class Bullets {

    /** {@code move.w #19,d1}: twenty records in each pool. */
    public static final int POOL = 20;
    /** {@code cmp.l #10*128,d0}: how close to a surface counts as hitting it. */
    private static final int SURFACE = 10 * 128;
    /** {@code move.l #10*128,thingheight} and the steps a bullet is given. */
    private static final int THING_HEIGHT = 10 * 128;
    private static final int STEP_UP = 0, STEP_DOWN = 0x1000000;
    /** {@code move.w #%0000010000000000,wallflags}. */
    private static final int WALL_FLAGS = 0b0000010000000000;
    /** {@code cmp.l #10*256,d6}: gravity does not build past this. */
    private static final int MAX_FALL = 10 * 256;
    /** {@code cmp.w #20*128,d0}: how steeply a shot may be aimed. */
    private static final int MAX_RISE = 20 * 128;
    /** {@code add.w #40,d0}: slack on the swept test against a target. */
    private static final int SWEEP_SLACK = 40;
    /** {@code move.l #%100000100000,EnemyFlags(a5)}: the two players. */
    public static final int HITS_PLAYERS = 0b100000100000;

    /** {@code btst #0,shotflags+1(a0)} and {@code btst #1}. */
    private static final int FLAG_BOUNCE = 1, FLAG_SLOW = 2;

    private final Level lv;
    /** {@code HitNoises}: a sample and a volume, or -1 for silence. */
    public Sound sound = Sound.SILENT;
    private final MoveObject mover;
    private final ColBox boxes;
    /** The flight and burst pictures, and which shots blast. */
    private final BulletAnims anims;
    /** {@code CanItBeSeen}, which is what stops a blast going through walls. */
    private final AlienControl alien;

    /** Counters for checking. */
    public int alive, spawned, refused, hitWall, hitSurface, hitTarget, expired;
    public int blasts, blastHits, blastDamage, burstsDone;

    public Bullets(Level level, MoveObject mover, ColBox boxes,
                   BulletAnims anims, AlienControl alien) {
        this.lv = level;
        this.mover = mover;
        this.boxes = boxes;
        this.anims = anims;
        this.alien = alien;
    }

    /** {@code .findonefree}: a record whose zone word is negative, or -1. */
    private int free(int pool) {
        for (int i = 0; i < POOL; i++) {
            int base = pool + i * Obj.SIZE;
            if (!lv.data.inRange(base, Obj.SIZE)) {
                return -1;
            }
            if (lv.data.s16(base + Obj.ZONE) < 0) {
                return base;
            }
        }
        return -1;                                  // the pool is full: no shot
    }

    /**
     * {@code PLR1FIREBULLET}: the player's own shot.
     *
     * @param dirX  {@code tempxdir}, the sine of the way the player faces
     * @param rise  {@code bulyspd}, worked out from the target's height
     * @param mask  {@code EnemyFlags}, what it is allowed to hit
     */
    public boolean firePlayer(GunData guns, int gun, int x, int z, int y,
                              int zone, boolean inTop, int dirX, int dirZ,
                              int rise, int mask) {
        int base = free(lv.ptrPlayerShots);
        if (base < 0) {
            refused++;
            return false;
        }
        lv.data.setS16(base + Obj.SHOT_GRAV, guns.word(gun, GunData.SHOT_GRAV));
        lv.data.setS16(base + Obj.SHOT_FLAGS, guns.word(gun, GunData.SHOT_FLAGS));

        // the aim is clamped before the gun's own push is added, so a grenade
        // always leaves at a lob however flat the shot was taken
        int d0 = Math.max(-MAX_RISE, Math.min(MAX_RISE, rise));
        d0 = M68k.w(d0 + guns.word(gun, GunData.SHOT_RISE));

        lv.data.setU8(base + Obj.SHOT_SIZE, gun);
        lv.data.setU8(base + Obj.SHOT_POWER, guns.byteAt(gun, GunData.DAMAGE));

        int shift = guns.word(gun, GunData.SPEED) & 15;
        put(base, x, z, y, zone, inTop, dirX << shift, dirZ << shift, d0, mask);
        spawned++;
        return true;
    }

    /**
     * {@code FireAtPlayer1}: an enemy's shot, which leads its target.
     *
     * The aim is not at where the player is but at where they will be: their
     * own speed times the distance, divided by the bullet's, is added on first.
     * Then the vertical speed is whatever meets the player's height over that
     * distance, and the whole thing is scaled so it arrives rather than passing
     * through.
     */
    public boolean fireEnemy(GunData guns, int shotType, int power, int speed,
                             int shift, int x, int z, int y, int zone,
                             boolean inTop, int toX, int toZ, int toY) {
        int base = free(lv.ptrEnemyShots);
        if (base < 0) {
            refused++;
            return false;
        }
        lv.data.setS16(base + Obj.SHOT_GRAV, guns.word(shotType, GunData.SHOT_GRAV));
        lv.data.setS16(base + Obj.SHOT_FLAGS, guns.word(shotType, GunData.SHOT_FLAGS));
        lv.data.setU8(base + Obj.SHOT_SIZE, shotType);
        lv.data.setU8(base + Obj.SHOT_POWER, power);

        int dx = M68k.w(toX - x);
        int dz = M68k.w(toZ - z);
        int dist = Math.max(1, (int) Math.sqrt((long) dx * dx + (long) dz * dz));

        // jsr HeadTowards with Range nought: one frame's worth along the line
        int vx = M68k.muls(dx, speed) / dist;
        int vz = M68k.muls(dz, speed) / dist;

        // the vertical speed that meets the target over that distance
        int steps = Math.max(1, dist >> (shift & 15));
        int rise = (int) (((long) (toY - y) * 2) / steps);

        put(base, x, z, y, zone, inTop, vx << 16, vz << 16, rise, HITS_PLAYERS);
        spawned++;
        return true;
    }

    /** The fields both spawns set the same way. */
    private void put(int base, int x, int z, int y, int zone, boolean inTop,
                     int velX, int velZ, int rise, int mask) {
        int pt = lv.data.s16(base + Obj.POINT);
        if (pt >= 0 && pt < lv.objectPointX.length) {
            lv.objectPointX[pt] = x;
            lv.objectPointZ[pt] = z;
        }
        lv.data.setS32(base + Obj.SHOT_XVEL, velX);
        lv.data.setS32(base + Obj.SHOT_ZVEL, velZ);
        lv.data.setS16(base + Obj.SHOT_YVEL, rise);
        lv.data.setS32(base + Obj.ACC_YPOS, y);
        lv.data.setS16(base + Obj.HEIGHT, M68k.w(y >> 7));
        lv.data.setS16(base + Obj.SHOT_LIFE, 0);
        lv.data.setU8(base + Obj.SHOT_STATUS, 0);
        lv.data.setU8(base + Obj.SHOT_ANIM, 0);
        lv.data.setS32(base + Obj.ENEMY_FLAGS, mask);
        lv.data.setS16(base + Obj.ZONE, zone);
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, zone);
        lv.data.setU8(base + Obj.IN_TOP, inTop ? 0xff : 0);
        lv.data.setU8(base + Obj.WORRY, 0xff);       // st worry(a0)
        lv.data.setU8(base + Obj.TYPE, 2);
    }

    /** Where a record's point stands now, for a burst that starts before the move. */
    private int oldXOf(int base) {
        int pt = lv.data.s16(base + Obj.POINT);
        return pt >= 0 && pt < lv.objectPointX.length ? lv.objectPointX[pt] : 0;
    }

    private int oldZOf(int base) {
        int pt = lv.data.s16(base + Obj.POINT);
        return pt >= 0 && pt < lv.objectPointZ.length ? lv.objectPointZ[pt] : 0;
    }

    /**
     * {@code move.b #1,shotstatus(a0)}: it stops being a bullet and starts
     * being an explosion.
     *
     * The record stays in the pool and in the world, playing out its burst list
     * where it stopped, and only goes back when that list ends. A shot whose
     * {@code ExplosiveForce} is not nought also blasts everything it can see
     * from there, which for the rocket and the grenade is the point of them.
     */
    private void pop(int base, int x, int z) {
        lv.data.setU8(base + Obj.SHOT_STATUS, 1);
        // HitNoises is a longword a shot: the sample above the volume, and -1
        // where there is nothing to hear. Only the rocket and the grenade have
        // one, and it is the same boom the barrels make.
        int shotKind = lv.data.u8(base + Obj.SHOT_SIZE);
        if (shotKind == 2 || shotKind == 4) {
            sound.play(Sound.BOOM, 0, 0, 200, 4, false);
        }
        lv.data.setU8(base + Obj.SHOT_ANIM, 0);
        int shot = lv.data.u8(base + Obj.SHOT_SIZE);
        int f = anims.force(shot);
        if (f != 0) {
            computeBlast(base, f, x, z);
            blasts++;
        }
    }

    /**
     * {@code notpopping} the other way round: one step of the burst list.
     *
     * The step carries the size the sprite takes, the slot and frame it draws
     * from, and a height to add -- so an explosion grows and rises as it plays.
     * When the list runs out the record's zone goes to -1 and the pool has it
     * back.
     */
    private void burst(int base) {
        int shot = lv.data.u8(base + Obj.SHOT_SIZE);
        BulletAnims.Step[] list = anims.burst(shot);
        int at = lv.data.u8(base + Obj.SHOT_ANIM);
        if (at >= list.length) {
            burstsDone++;
            retire(base);
            return;                                  // cmp.w #-1,d2
        }
        lv.data.setS16(base + Obj.SPRITE_SIZE, anims.burstSize(shot));
        BulletAnims.Step step = list[at];
        lv.data.setU8(base + Obj.SHOT_ANIM, at + 1);
        lv.data.setS16(base + Obj.BRIGHT, step.size());   // move.w d2,6(a0)
        lv.data.setS16(base + Obj.SLOT, step.slot());
        lv.data.setS16(base + Obj.FRAME, step.frame());
        lv.data.setS16(base + Obj.HEIGHT,
                       M68k.w(lv.data.s16(base + Obj.HEIGHT) + step.rise()));
        lv.data.setS32(base + Obj.ACC_YPOS,
                       lv.data.s32(base + Obj.ACC_YPOS) + (step.rise() << 7));
    }

    /**
     * {@code ComputeBlast}: what an explosion does to everything it can see.
     *
     * The falloff is worked out from the distance: an eighth of it, less four,
     * clamped at nothing, and anything past thirty-one is out of range
     * altogether. What is left is turned round so that near is large, and the
     * force times that, shifted down five, is the damage -- capped at the force
     * itself, so standing on a rocket is no worse than standing beside it.
     *
     * The turning round is {@code neg.w d3 / add.w 32,d3}, and that second
     * operand has no {@code #}: as written it adds the word at absolute address
     * thirty-two rather than the number. Read literally the falloff would
     * vanish and every target in sight would take the full force, since the
     * result is capped anyway. Thirty-two is what the surrounding arithmetic is
     * built for -- it is the only value that makes the shift by five come out at
     * the force -- so that is what is here, and the oddity is recorded rather
     * than smoothed over.
     *
     * {@code CanItBeSeen} is what keeps a blast in its room. It is the same
     * room-list test the enemies use to notice the player, so a wall stops an
     * explosion exactly as it stops a line of sight.
     */
    private void computeBlast(int base, int force, int x, int z) {
        blastFrom(lv.data.s16(base + Obj.ZONE),
                  lv.data.u8(base + Obj.IN_TOP) != 0, x, z, force);
    }

    private void blastFrom(int zone, boolean inTop, int x, int z, int force) {
        for (int i = 0; i < lv.objects.size(); i++) {
            int at = lv.ptrObjects + i * Obj.SIZE;
            if (lv.data.s16(at + Obj.POINT) < 0) {
                break;
            }
            int theirZone = lv.data.s16(at + Obj.ZONE);
            if (theirZone < 0) {
                continue;
            }
            int type = lv.data.u8(at + Obj.TYPE);
            if (type > 31 || (BLAST_TARGETS & (1 << type)) == 0) {
                continue;                            // btst d1,d7
            }
            if (!alien.canItBeSeen(zone, theirZone, inTop,
                                   lv.data.u8(at + Obj.IN_TOP) != 0)) {
                continue;
            }
            int pt = lv.data.s16(at + Obj.POINT);
            if (pt < 0 || pt >= lv.objectPointX.length) {
                continue;
            }
            int dx = M68k.w(lv.objectPointX[pt] - x);
            int dz = M68k.w(lv.objectPointZ[pt] - z);
            int dist = (int) Math.sqrt((long) dx * dx + (long) dz * dz);

            int d3 = (dist >> 3) - 4;                // asr.w #3 / sub.w #4
            if (d3 < 0) {
                d3 = 0;
            }
            if (d3 > 31) {
                continue;                            // cmp.w #31,d3 / bgt
            }
            d3 = 32 - d3;                            // neg.w d3 / add.w 32,d3
            int d5 = Math.min(force, (force * d3) >> 5);
            int now = lv.data.u8(at + Obj.DAMAGE_TAKEN);
            lv.data.setU8(at + Obj.DAMAGE_TAKEN, Math.min(255, now + d5));
            blastDamage += d5;
            blastHits++;
        }
    }

    /**
     * {@code ComputeBlast} called from somewhere other than a shot.
     *
     * A barrel going off does the same thing a rocket does, and so does a
     * gas pipe; the blast does not care what set it off.
     */
    public void blastAt(int zone, boolean inTop, int x, int z, int force) {
        blastFrom(zone, inTop, x, z, force);
        blasts++;
    }

    /**
     * {@code ExplodeIntoBits}: the pieces a thing comes apart into.
     *
     * Up to twenty records are taken from the enemy pool, given a random
     * direction from the sine table and a speed shifted up by one to four, a
     * push upward, and gravity -- so they fly out, arc, and fall. They are type
     * two like any other shot, which is why the same routine flies them; what
     * makes them harmless is that their {@code EnemyFlags} is nought, and the
     * hit loop gives up at once on a shot that may hit nothing.
     *
     * @param count how many pieces, which the original takes from the damage
     *              that killed the thing, quartered and capped at seven
     */
    public int explodeIntoBits(int zone, boolean inTop, int x, int z, int y,
                               int count, Enemy.Rand rand,
                               ab3d.data.SineTable sine) {
        int made = 0;
        for (int i = 0; i <= Math.min(7, count); i++) {
            int base = free(lv.ptrEnemyShots);
            if (base < 0) {
                break;                               // the pool is full
            }
            int ang = rand.next() & 8190;
            int shift = (rand.next() & 3) + 1;
            int vx = sine.sinByte(ang) << shift;
            int vz = sine.cosByte(ang) << shift;
            // GetRand / and.w #1023,d0 / add.w #2*128,d0 / neg.w d0
            int rise = -((rand.next() & 1023) + 2 * 128);

            put(base, x, z, y + 6 * 128, zone, inTop, vx, vz, rise, 0);
            lv.data.setS16(base + Obj.SHOT_GRAV, 40);   // move.w #40,shotgrav
            lv.data.setS16(base + Obj.SHOT_FLAGS, 0);
            lv.data.setS16(base + Obj.SHOT_LIFE, -1);
            lv.data.setU8(base + Obj.SHOT_POWER, 0);
            // move.w d2,d0 / and.w #3,d0 / add.w #50,d0: one of four gib pictures
            lv.data.setU8(base + Obj.SHOT_SIZE, 50 + (rand.next() & 3));
            made++;
            bits++;
        }
        return made;
    }

    /** Pieces thrown by {@code ExplodeIntoBits}, for checking. */
    public int bits;

    /** {@code move.l #%1111111111110111100001,d7}: what a blast may reach. */
    private static final int BLAST_TARGETS = 0b1111111111110111100001;

    /** {@code move.w #-1,12(a0)}: the record goes back to the pool. */
    private void retire(int base) {
        lv.data.setS16(base + Obj.ZONE, -1);
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, -1);
        lv.data.setU8(base + Obj.SHOT_STATUS, 0);
    }

    /**
     * {@code ItsABullet}: one frame of one shot.
     *
     * The order is the original's and it matters. The roof and floor are tested
     * before the move, against where the shot already is; then the position is
     * advanced by the velocities and gravity; then {@code MoveObject} takes it
     * through the walls; and only then is the flight tested against the things
     * it might have passed through.
     */
    public void update(int base, int frames, GunData guns) {
        int zone = lv.data.s16(base + Obj.ZONE);
        if (zone < 0 || zone >= lv.zones.length) {
            lv.data.setS16(base + Obj.GRAPHIC_ROOM, zone);
            return;                                  // blt doneshot
        }
        alive++;

        // a shot that has hit something is playing out its burst
        if (lv.data.u8(base + Obj.SHOT_STATUS) != 0) {
            burst(base);
            return;
        }

        // .noworrylife, and the order of the two tests matters. A negative
        // shotlife means the shot never ages, and that is checked before the
        // gun's own lifetime -- which is what lets the pieces of an exploded
        // body use the same pool with a shotsize of fifty, well past the eight
        // gun records the table holds.
        int gun = lv.data.u8(base + Obj.SHOT_SIZE);
        int spent = lv.data.s16(base + Obj.SHOT_LIFE);
        if (spent >= 0) {                            // blt.s infinite
            int life = gun < GunData.GUNS ? guns.word(gun, GunData.LIFETIME) : -1;
            if (life >= 0) {
                if (life <= spent) {
                    expired++;
                    retire(base);
                    return;                          // st timeout
                }
                lv.data.setS16(base + Obj.SHOT_LIFE, M68k.w(spent + frames));
            }
        }

        boolean inTop = lv.data.u8(base + Obj.IN_TOP) != 0;
        int flags = lv.data.s16(base + Obj.SHOT_FLAGS);
        int acc = lv.data.s32(base + Obj.ACC_YPOS);

        int roof = inTop ? lv.zone(zone).upperRoofHeight : lv.zone(zone).roofHeight;
        int floor = inTop ? lv.zone(zone).upperFloorHeight : lv.zone(zone).floorHeight;

        // .nohitroof and .nohitfloor, both against the position it starts from
        if (roof - acc >= SURFACE || floor - acc <= SURFACE) {
            boolean roofSide = roof - acc >= SURFACE;
            if ((flags & FLAG_BOUNCE) == 0) {
                hitSurface++;
                pop(base, oldXOf(base), oldZOf(base));
                return;                              // .nobounce
            }
            lv.data.setS16(base + Obj.SHOT_YVEL,
                           M68k.w(-lv.data.s16(base + Obj.SHOT_YVEL)));
            lv.data.setS32(base + Obj.ACC_YPOS,
                           roofSide ? roof + SURFACE : floor - SURFACE);
            acc = lv.data.s32(base + Obj.ACC_YPOS);
            if ((flags & FLAG_SLOW) != 0) {
                lv.data.setS32(base + Obj.SHOT_XVEL,
                               lv.data.s32(base + Obj.SHOT_XVEL) >> 1);
                lv.data.setS32(base + Obj.SHOT_ZVEL,
                               lv.data.s32(base + Obj.SHOT_ZVEL) >> 1);
            }
        }

        int pt = lv.data.s16(base + Obj.POINT);
        if (pt < 0 || pt >= lv.objectPointX.length) {
            retire(base);
            return;
        }
        int oldX = lv.objectPointX[pt];
        int oldZ = lv.objectPointZ[pt];

        // the velocities are sixteen-sixteen, so the whole part of a frame's
        // travel is the high word of the product
        int newX = M68k.w(oldX + (int) (((long) lv.data.s32(base + Obj.SHOT_XVEL)
                                         * frames) >> 16));
        int newZ = M68k.w(oldZ + (int) (((long) lv.data.s32(base + Obj.SHOT_ZVEL)
                                         * frames) >> 16));

        int yvel = lv.data.s16(base + Obj.SHOT_YVEL);
        int grav = lv.data.s16(base + Obj.SHOT_GRAV);
        int dy = yvel * frames;
        if (grav != 0) {
            dy += grav * frames;
            lv.data.setS16(base + Obj.SHOT_YVEL,
                           M68k.w(Math.min(MAX_FALL, yvel + grav * frames)));
        }
        acc += dy;
        lv.data.setS32(base + Obj.ACC_YPOS, acc);
        lv.data.setS16(base + Obj.HEIGHT, M68k.w(acc >> 7));

        mover.move(zone, oldX, oldZ, newX, newZ, acc - 5 * 128, acc - 5 * 128,
                   0, THING_HEIGHT, STEP_UP, STEP_DOWN, WALL_FLAGS,
                   MoveObject.NO_STANDOFF);   // move.b #$ff,awayfromwall
        lv.objectPointX[pt] = mover.x;
        lv.objectPointZ[pt] = mover.z;
        lv.data.setS16(base + Obj.ZONE, mover.zone);
        lv.data.setS16(base + Obj.GRAPHIC_ROOM, mover.zone);

        if (mover.hitWall && (flags & FLAG_BOUNCE) == 0) {
            hitWall++;
            pop(base, mover.x, mover.z);
            return;
        }

        // .checkloop: what the flight passed through
        if (hitSomething(base, oldX, oldZ, mover.x, mover.z)) {
            hitTarget++;
            pop(base, mover.x, mover.z);
        }
    }

    /**
     * {@code .checkloop}: a swept test of the flight against every target.
     *
     * Three things have to hold. The height difference must be inside the
     * target's own box; the sideways distance from the line of flight must be
     * inside its width; and both ends of the flight must be within the flight's
     * own length plus forty of the target. The last is what stops a shot that
     * merely aims past something from hitting it.
     */
    private boolean hitSomething(int base, int oldX, int oldZ, int newX, int newZ) {
        int mask = lv.data.s32(base + Obj.ENEMY_FLAGS);
        if (mask == 0) {
            return false;   // tst.l EnemyFlags / beq: a gib hits nothing at all
        }
        int xdiff = M68k.w(newX - oldX);
        int zdiff = M68k.w(newZ - oldZ);
        int range = Math.max(1,
                (int) Math.sqrt((long) xdiff * xdiff + (long) zdiff * zdiff));
        long sqr = (long) (range + SWEEP_SLACK) * (range + SWEEP_SLACK);
        int myHeight = lv.data.s16(base + Obj.HEIGHT);
        int power = lv.data.u8(base + Obj.SHOT_POWER);

        for (int i = 0; i < lv.objects.size(); i++) {
            int at = lv.ptrObjects + i * Obj.SIZE;
            if (lv.data.s16(at + Obj.POINT) < 0) {
                break;                               // tst.w (a3) / blt
            }
            if (lv.data.s16(at + Obj.ZONE) < 0) {
                continue;
            }
            int type = lv.data.u8(at + Obj.TYPE);
            if (type > 31 || (mask & (1 << type)) == 0) {
                continue;                            // btst d1,d7
            }
            if (lv.data.u8(at + Obj.NUM_LIVES) == 0) {
                continue;
            }
            int dh = Math.abs(M68k.w(myHeight - lv.data.s16(at + Obj.HEIGHT)));
            if (dh > boxes.height(type)) {
                continue;                            // cmp.w 2(a6),d2 / bgt
            }
            int pt = lv.data.s16(at + Obj.POINT);
            if (pt < 0 || pt >= lv.objectPointX.length) {
                continue;
            }
            int tx = lv.objectPointX[pt], tz = lv.objectPointZ[pt];

            long across = (long) M68k.muls(M68k.w(tx - oldX), zdiff)
                        - M68k.muls(M68k.w(tz - oldZ), xdiff);
            if (Math.abs(across) / range > boxes.width(type)) {
                continue;
            }
            long fromStart = sq(M68k.w(tx - oldX)) + sq(M68k.w(tz - oldZ));
            long fromEnd = sq(M68k.w(tx - newX)) + sq(M68k.w(tz - newZ));
            if (fromStart > sqr || fromEnd > sqr) {
                continue;
            }

            int now = lv.data.u8(at + Obj.DAMAGE_TAKEN);
            lv.data.setU8(at + Obj.DAMAGE_TAKEN, Math.min(255, now + power));
            lv.data.setS16(at + Obj.IMPACT_X, lv.data.s16(base + Obj.SHOT_XVEL));
            lv.data.setS16(at + Obj.IMPACT_Z, lv.data.s16(base + Obj.SHOT_ZVEL));
            return true;
        }
        return false;
    }

    private static long sq(int v) {
        return (long) v * v;
    }
}
