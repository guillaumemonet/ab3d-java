package ab3d.engine;

import ab3d.data.GameObject;
import ab3d.data.GunData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.m68k.M68k;

/**
 * {@code ObjectHandler}, transcribed from source/anims, together with the loop
 * in source/jg.s that decides which objects it bothers with.
 *
 * Two things happen a frame. First jg.s builds a bitmap of the zones the
 * viewer's own zone lists as visible and sets the {@code worry} byte of every
 * object standing in one of them; then {@code ObjectHandler} walks the list,
 * copies each record's zone into {@code GraphicRoom}, and for the objects that
 * are worried about dispatches on byte 16 -- the type. Nothing far away is ever
 * looked at, which is why the game can afford a per-object test this direct.
 *
 * Only the key branch is here. The other types are enemies and the rest of the
 * pickups, and each is its own routine; the dispatch below names them so what
 * is missing is visible rather than silently folded into "nothing happens".
 */
public final class ObjectHandler {

    /** {@code tst.b worry(a0)}: offset of the byte jg.s arms. */
    private static final int WORRY = 62;
    /** {@code ObjInTop(a0)}. */
    private static final int OBJ_IN_TOP = 63;
    /** {@code move.w 12(a0),GraphicRoom(a0)}. */
    private static final int IN_PLAY_ZONE = 12, GRAPHIC_ROOM = 26;
    /** The type byte and, for a key, the condition bits it grants. */
    private static final int TYPE = 16, GRANTS = 17;

    /** The dispatch values {@code ObjectHandler} branches on. */
    public static final int TYPE_NASTY = 0, TYPE_MEDIKIT = 1, TYPE_GUN = 3,
                            TYPE_KEY = 4, TYPE_AMMO = 9;
    /** {@code HealFactor EQU 18} and {@code AmmoType EQU 18}: the same word. */
    private static final int EXTRA = 18;
    /** {@code cmp.w #127,PLR1_energy}. */
    public static final int ENERGY_MAX = 127;
    /** {@code move.l #100*100,d2 / jsr CheckHit}: squared, so no root is taken. */
    private static final int REACH = 100;
    /** {@code move.w #$0f0f,14(a0)}: the sprite size a key always has. */
    private static final int KEY_SIZE = 0x0f0f;

    private final Level lv;
    /** The pickups all make a noise of their own on the way out. */
    public Sound sound = Sound.SILENT;
    /** {@code PLR1_GunData}, which the ammunition and gun pickups write into. */
    public GunData guns;
    /** {@code PLR1_energy}, which the medikits raise. */
    public int energy = ENERGY_MAX;
    /** The enemy routines, and the table of what each kind differs by. */
    public Enemy enemies;
    /** The shots in flight, which are objects of type two. */
    public Bullets bullets;
    /** {@code ItsABarrel}. */
    public Barrel barrel;
    public ab3d.data.GunData gunData;
    /** {@code TempFrames}, which the enemies move and count timers by. */
    public int frames = 1;
    /** Where the player is, for the enemies that look for them. */
    public int playerY;
    /** {@code PLR1_sinval} and {@code PLR1_cosval}, for the ones that circle. */
    public int sinval, cosval;

    /** Keys taken since the level began, for checking. */
    public int keysTaken;
    /** And the rest, for checking. */
    public int medikitsTaken, gunsTaken, clipsTaken;
    /** Which bits the last pickup granted, for checking. */
    public int lastGrant;

    public ObjectHandler(Level level) {
        this.lv = level;
    }

    /**
     * The jg.s loop: {@code or.b #127,worry(a0)} for everything in view.
     *
     * The bitmap is a byte per eight zones and the test is {@code btst}, but a
     * set of zone numbers says the same thing. Note that the byte is only ever
     * set here -- a branch that has finished with an object clears it itself, so
     * one that no-one handles stays armed until it leaves the view.
     */
    public void arm(Zone here) {
        boolean[] awake = new boolean[lv.zones.length];
        for (Zone.GraphEntry e : here.graphics) {
            int z = e.graphNumber();
            if (z >= 0 && z < awake.length) {
                awake[z] = true;                       // bset d1,(a1,d0.w)
            }
        }
        for (int i = 0; i < lv.objects.size(); i++) {
            int base = lv.ptrObjects + i * GameObject.SIZE;
            if (lv.data.s16(base) < 0) {
                break;                                 // move.w (a0),d0 / blt
            }
            int zone = lv.data.s16(base + IN_PLAY_ZONE);
            if (zone < 0 || zone >= awake.length || !awake[zone]) {
                continue;                              // blt .doallobs / beq
            }
            lv.data.setU8(base + WORRY, lv.data.u8(base + WORRY) | 127);
        }
    }

    /**
     * {@code Objectloop}: one pass over the object list.
     *
     * @param conditions the current {@code Conditions}
     * @return what it becomes
     */
    public int update(int playerZone, int playerX, int playerZ,
                      boolean playerInTop, int conditions) {
        // the two shot pools are not in the object list, so they are walked
        // separately -- ObjectHandler reaches them because the level's records
        // run on past the placed objects
        flyPool(lv.ptrPlayerShots);
        flyPool(lv.ptrEnemyShots);
        for (int i = 0; i < lv.objects.size(); i++) {
            GameObject o = lv.objects.get(i);
            int base = lv.ptrObjects + i * GameObject.SIZE;
            if (lv.data.s16(base) < 0) {
                break;                                 // tst.w (a0) / blt
            }
            int zone = lv.data.s16(base + IN_PLAY_ZONE);
            lv.data.setS16(base + GRAPHIC_ROOM, zone);  // every object, every frame
            o.zone = zone;
            if (worry(base) == 0) {
                continue;                              // dontworryyourprettyhead
            }
            int type = lv.data.s8(base + TYPE);
            if (type == 2 && bullets != null && gunData != null) {
                bullets.update(base, frames, gunData);   // JUMPBULLET
                continue;
            }
            if (type == Barrel.TYPE && barrel != null) {
                barrel.update(base, playerZone, playerInTop);   // JUMPBARREL
                continue;
            }
            Enemy.Kind kind = Enemy.kindOf(type);
            if (kind != null && enemies != null) {
                enemies.update(kind, base, frames, playerX, playerZ, playerY,
                               playerZone, playerInTop, sinval, cosval);
                continue;
            }
            switch (type) {
                case TYPE_KEY -> conditions = key(o, base, playerZone, playerX,
                        playerZ, playerInTop, conditions);
                case TYPE_MEDIKIT -> medikit(o, base, playerZone, playerX,
                        playerZ, playerInTop);
                case TYPE_GUN -> gun(o, base, playerZone, playerX, playerZ,
                        playerInTop);
                case TYPE_AMMO -> ammo(o, base, playerZone, playerX, playerZ,
                        playerInTop);
                default -> { }
            }
            // Of the rest, the levels only ever place the flying nasty, the
            // barrel, the marine, the tough marine and the flame marine. The
            // robot, the worm, the big red thing, the tree, the eyeball and the
            // gas pipe have routines of their own and are never put anywhere.
        }
        return conditions;
    }

    /** One pool of twenty shot records. */
    private void flyPool(int pool) {
        if (bullets == null || gunData == null) {
            return;
        }
        for (int i = 0; i < Bullets.POOL; i++) {
            int base = pool + i * GameObject.SIZE;
            if (!lv.data.inRange(base, GameObject.SIZE)) {
                return;
            }
            if (lv.data.s16(base + Obj.ZONE) >= 0) {
                bullets.update(base, frames, gunData);
            }
        }
    }

    private int worry(int base) {
        return lv.data.u8(base + WORRY);
    }

    /**
     * The test the four pickups share: same storey, same zone, within reach.
     *
     * Each of them writes this out again with its own labels, and each does the
     * same four things in the same order -- clear {@code worry}, copy the zone
     * into {@code GraphicRoom}, drop the object to the floor of its own zone,
     * then {@code CheckHit} against a hundred squared. The only real difference
     * is how far above the floor they sit: a key takes the floor as it is, and
     * the others {@code sub.w #32,d0}.
     *
     * @param drop what to take off the floor height, which is the one number
     *             that differs between them
     */
    private boolean inReach(int base, int playerZone, int px, int pz,
                            boolean playerInTop, int drop) {
        lv.data.setU8(base + WORRY, 0);                // clr.b worry(a0)
        lv.data.setS16(base + GRAPHIC_ROOM, lv.data.s16(base + IN_PLAY_ZONE));

        int top = lv.data.u8(base + OBJ_IN_TOP);
        if (((top != 0 ? 1 : 0) ^ (playerInTop ? 1 : 0)) != 0) {
            return false;                              // eor.b d1,d0 / bne
        }
        int zone = lv.data.s16(base + IN_PLAY_ZONE);
        if (zone < 0 || zone >= lv.zones.length) {
            return false;
        }
        // move.l ToZoneFloor(a1),d0 -- or ToUpperFloor when the object is up
        int floor = top != 0 ? lv.zone(zone).upperFloorHeight
                             : lv.zone(zone).floorHeight;
        lv.data.setS16(base + 4, M68k.w((floor >> 7) - drop));

        if (zone != playerZone) {
            return false;                              // cmp.w 12(a0),d7 / bne
        }
        int pt = lv.data.s16(base);
        if (pt < 0 || pt >= lv.objectPointX.length) {
            return false;
        }
        long dx = M68k.w(lv.objectPointX[pt] - px);
        long dz = M68k.w(lv.objectPointZ[pt] - pz);
        return dx * dx + dz * dz < (long) REACH * REACH;   // slt hitwall
    }

    /** {@code move.w #-1,12(a0)} and the same into {@code GraphicRoom}. */
    private void retire(GameObject o, int base) {
        lv.data.setS16(base + IN_PLAY_ZONE, -1);
        lv.data.setS16(base + GRAPHIC_ROOM, -1);
        o.zone = -1;
    }

    /**
     * {@code ItsAMediKit}.
     *
     * {@code HealFactor} is the word at eighteen, added to the energy and
     * clamped at a hundred and twenty-seven. The routine gives up before
     * anything else when the player is already at full, so a medikit walked over
     * at full health stays where it is.
     */
    private void medikit(GameObject o, int base, int playerZone, int px, int pz,
                         boolean playerInTop) {
        if (energy >= ENERGY_MAX) {
            return;                                    // cmp.w #127 / bge
        }
        if (!inReach(base, playerZone, px, pz, playerInTop, 32)) {
            return;
        }
        retire(o, base);
        sound.play(Sound.COLLECT, 0, 0, 50, lv.data.u8(base + 1), false);
        energy = Math.min(ENERGY_MAX, energy + lv.data.s16(base + EXTRA));
        medikitsTaken++;
    }

    /**
     * {@code ItsABigGun}.
     *
     * Byte seventeen names the gun, but against {@code PLR1_GunData+32} rather
     * than the table's start -- so the byte is one less than the slot, and a
     * pickup marked nought is the plasma gun. It sets {@code gotgun} and adds
     * that slot's entry of {@code AmmoInGuns}.
     */
    private void gun(GameObject o, int base, int playerZone, int px, int pz,
                     boolean playerInTop) {
        if (guns == null || !inReach(base, playerZone, px, pz, playerInTop, 32)) {
            return;
        }
        int which = lv.data.u8(base + 17);             // move.b 17(a0),d0
        int slot = which + 1;                          // against PLR1_GunData+32
        if (slot < 0 || slot >= GunData.GUNS
                || which >= GunData.AMMO_IN_GUNS.length) {
            return;
        }
        guns.setByte(slot, GunData.GOT_GUN, 0xff);     // st 7(a1,d0.w*8)
        // move.w (a2,d1.w*2),d1: AmmoInGuns is read with the byte, not the slot
        int add = GunData.AMMO_IN_GUNS[which];
        guns.setWord(slot, GunData.AMMO,
                     M68k.w(guns.unsigned(slot, GunData.AMMO) + add));
        retire(o, base);
        sound.play(Sound.COLLECT, 0, 0, 50, lv.data.u8(base + 1), false);
        gunsTaken++;
    }

    /**
     * {@code ItsAnAmmoClip}.
     *
     * {@code AmmoType} is the word at eighteen -- the same place a medikit keeps
     * its healing -- and names the gun. What it adds is that gun's own
     * {@code ammoinclip} byte shifted up three, and the check is made before
     * the pickup rather than after: a gun already at eighty clips' worth leaves
     * the box on the floor.
     */
    private void ammo(GameObject o, int base, int playerZone, int px, int pz,
                      boolean playerInTop) {
        if (guns == null || !inReach(base, playerZone, px, pz, playerInTop, 32)) {
            return;
        }
        int slot = lv.data.s16(base + EXTRA);
        if (slot < 0 || slot >= GunData.GUNS) {
            return;
        }
        if (guns.unsigned(slot, GunData.AMMO) >= GunData.AMMO_MAX) {
            return;                                    // cmp.w #80*8 / bge
        }
        retire(o, base);
        // move.w #11,Samplenum: the ammunition clip has its own noise
        sound.play(Sound.RELOAD, 0, 0, 50, lv.data.u8(base + 1), false);
        int add = guns.byteAt(slot, GunData.CLIP) << 3;
        guns.setWord(slot, GunData.AMMO,
                     M68k.w(guns.unsigned(slot, GunData.AMMO) + add));
        clipsTaken++;
    }

    /**
     * {@code ItsAKey}.
     *
     * The routine opens with a test on {@code NASTY}: when it is clear the key
     * is removed and grants nothing, because a linked game is handed all its
     * conditions at the start instead. Reading which way that falls takes two
     * files. CONTROLLOOP.s clears the flag just before {@code PLAYTHEGAME} in
     * what is plainly a debugging line -- it sits alone between two banners of
     * asterisks -- but {@code PLAYTHEGAME} itself calls {@code SETPLAYERS}, and
     * that does {@code st NASTY} on the one-player path and clears it only for
     * the master and slave. The later write wins, so single play always takes
     * the branch below and the flag is not carried here.
     *
     * It then puts the key on the floor of its own zone -- which matters because
     * that floor may be a lift -- and, if the player is in the same zone and on
     * the same storey and within a hundred units, retires it and ORs byte 17
     * into the <em>low</em> byte of {@code Conditions}. That is the range no
     * switch can reach: switches own bits four to eleven, keys own bits zero to
     * three.
     */
    private int key(GameObject o, int base, int playerZone, int px, int pz,
                    boolean playerInTop, int conditions) {
        lv.data.setS16(base + 14, KEY_SIZE);           // move.w #$0f0f,14(a0)
        lv.data.setS16(base + GRAPHIC_ROOM, lv.data.s16(base + IN_PLAY_ZONE));
        lv.data.setU8(base + WORRY, 0);                // clr.b worry(a0)

        int top = lv.data.u8(base + OBJ_IN_TOP);
        if (((top != 0 ? 1 : 0) ^ (playerInTop ? 1 : 0)) != 0) {
            return conditions;                          // eor.b d1,d0 / bne
        }

        int zone = lv.data.s16(base + IN_PLAY_ZONE);
        if (zone < 0 || zone >= lv.zones.length) {
            return conditions;
        }
        // move.l 2(a1),d0 / asr.l #7,d0 / sub.w #16,d0 / move.w d0,4(a0)
        int height = M68k.w((lv.zone(zone).floorHeight >> 7) - 16);
        lv.data.setS16(base + 4, height);

        if (zone != playerZone) {
            return conditions;                          // cmp.w 12(a0),d7 / bne
        }
        int pt = lv.data.s16(base);
        if (pt < 0 || pt >= lv.objectPointX.length) {
            return conditions;
        }
        // CheckHit: the squared distance against 100*100
        long dx = M68k.w(lv.objectPointX[pt] - px);
        long dz = M68k.w(lv.objectPointZ[pt] - pz);
        if (dx * dx + dz * dz >= (long) REACH * REACH) {
            return conditions;                          // slt hitwall / beq
        }

        lv.data.setS16(base + IN_PLAY_ZONE, -1);        // move.w #-1,12(a0)
        lv.data.setS16(base + GRAPHIC_ROOM, -1);
        o.zone = -1;
        // move.w #50,Noisevol / move.w #4,Samplenum
        sound.play(Sound.COLLECT, 0, 0, 50, lv.data.u8(base + 1), false);
        int grant = lv.data.u8(base + GRANTS);
        lastGrant = grant;
        keysTaken++;
        return conditions | grant;                      // or.b d0,Conditions+1
    }
}
