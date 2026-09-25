package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.GunData;
import ab3d.data.Level;
import ab3d.engine.Bullets;
import ab3d.engine.Enemy;
import ab3d.engine.Frame68k;
import ab3d.engine.Obj;
import ab3d.game.Player;

/**
 * Do the three guns that fire a bullet rather than a hit actually kill?
 *
 * The plasma gun, the rocket launcher and the grenade launcher have
 * {@code VISIBLE/INSTANT} clear, so nothing they do happens at the moment of
 * firing: a record is taken from the pool, given velocities, and then has to
 * fly, clear the walls, and reach the thing it was aimed at. Every one of those
 * steps can silently do nothing, and the only end-to-end proof is that the
 * target's lives go down.
 *
 * The grenade is the interesting one. Its gun record carries sixty of gravity,
 * three of flags and minus a thousand of rise, so it is lobbed, falls, and
 * bounces off the floor -- and it has a lifetime of a hundred where the others
 * have none, so it must reach its target before that runs out.
 */
public final class BulletCheck {

    private static final String[] NAMES = {
        "pulse rifle", "plasma gun", "rocket launcher", "flame thrower",
        "grenade launcher", "(none)", "(none)", "shotgun",
    };

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String name = args.length > 0 ? args[0] : "level_b";

        // the grenade is lobbed, so how far away the target stands decides
        // whether the arc comes down on it -- worth sweeping rather than
        // reading one distance and concluding it never connects
        if (args.length > 1 && args[1].equals("sweep")) {
            for (int away : new int[]{60, 100, 150, 200, 300, 400, 600}) {
                sweep(game, name, 4, away);
            }
            return;
        }
        if (args.length > 1 && args[1].equals("enemy")) {
            enemyShot(game, name);
            return;
        }
        for (int gun : new int[]{1, 2, 4, 0, 7}) {
            Level lv = Level.load(game, name);
            Frame68k frame = new Frame68k(lv, game);
            Player p = new Player(lv);

            int victim = -1;
            for (int i = 0; i < lv.objects.size(); i++) {
                int base = lv.ptrObjects + i * GameObject.SIZE;
                if (Enemy.kindOf(lv.data.u8(base + Obj.TYPE)) != null
                        && lv.data.s16(base + Obj.ZONE) >= 0
                        && lv.data.u8(base + Obj.NUM_LIVES) > 0) {
                    victim = i;
                    break;
                }
            }
            if (victim < 0) {
                System.out.println("no enemy in " + name);
                return;
            }
            int base = lv.ptrObjects + victim * GameObject.SIZE;
            int zone = lv.data.s16(base + Obj.ZONE);
            int pt = lv.data.s16(base + Obj.POINT);
            int vx = lv.objectPointX[pt], vz = lv.objectPointZ[pt];
            // where a player standing on that floor would have their eye: the
            // muzzle is twenty units below it, and launching a shot underneath
            // the floor makes it hit the ground on its first frame
            int standY = lv.zone(zone).floorHeight - Player.EYE_HEIGHT;
            int lives = lv.data.u8(base + Obj.NUM_LIVES);

            frame.gunSelected = gun;
            frame.gunData.setByte(gun, GunData.GOT_GUN, 0xff);
            frame.gunData.setWord(gun, GunData.AMMO, GunData.AMMO_MAX);

            int shots = 0;
            for (int t = 0; t < 400 && lv.data.u8(base + Obj.NUM_LIVES) > 0; t++) {
                if (frame.firePlayer(true, t == 0, 1, vx - 200, vz, standY,
                                     Player.EYE_HEIGHT,
                                     angleTo(vx - 200, vz, vx, vz), zone, false)) {
                    shots++;
                }
                frame.armObjects(zone);
                frame.updateObjects(zone, vx - 200, vz, standY, false, 1, 0);
            }
            System.out.printf("%-18s damage %2d, life %4d, grav %3d, flags %d, "
                              + "rise %5d%n", NAMES[gun],
                              frame.gunData.byteAt(gun, GunData.DAMAGE),
                              frame.gunData.word(gun, GunData.LIFETIME),
                              frame.gunData.word(gun, GunData.SHOT_GRAV),
                              frame.gunData.word(gun, GunData.SHOT_FLAGS),
                              frame.gunData.word(gun, GunData.SHOT_RISE));
            System.out.printf("   %d shots from 200 away: %d spawned, %d refused "
                              + "(pool full), %d hit a wall, %d a floor or roof, "
                              + "%d a target, %d ran out of life%n",
                              shots, frame.bullets.spawned, frame.bullets.refused,
                              frame.bullets.hitWall, frame.bullets.hitSurface,
                              frame.bullets.hitTarget, frame.bullets.expired);
            // the burst plays out after the shot lands, so keep the world
            // turning past the kill or it is never seen to finish
            StringBuilder seen = new StringBuilder();
            for (int t = 0; t < 30; t++) {
                frame.updateObjects(zone, vx - 200, vz, standY, false, 1, 0);
                for (int j = 0; j < Bullets.POOL; j++) {
                    int b = lv.ptrPlayerShots + j * GameObject.SIZE;
                    if (lv.data.s16(b + Obj.ZONE) >= 0
                            && lv.data.u8(b + Obj.SHOT_STATUS) != 0) {
                        seen.append(lv.data.s16(b + Obj.SLOT)).append(':')
                            .append(lv.data.s16(b + Obj.FRAME)).append(' ');
                        break;
                    }
                }
            }
            if (seen.length() > 0) {
                System.out.printf("   burst frames: %s%n", seen);
            }
            System.out.printf("   %d bursts played out, %d blasts hitting %d "
                              + "things for %d damage%n", frame.bullets.burstsDone,
                              frame.bullets.blasts, frame.bullets.blastHits,
                              frame.bullets.blastDamage);
            System.out.printf("   %d lives became %d%s%n%n", lives,
                              lv.data.u8(base + Obj.NUM_LIVES),
                              lv.data.u8(base + Obj.NUM_LIVES) == 0
                                      ? "  -- killed" : "");
        }
    }

    /**
     * One enemy bullet, aimed at the player, and what it does on arrival.
     *
     * The enemies only fire every few hundred frames, so waiting for one to
     * happen proves little either way. This puts a single shot into the pool by
     * hand and follows it: the same {@code ItsABullet} flies it and the same
     * loop tests it, the only difference being that its {@code EnemyFlags}
     * names the players rather than the things that walk.
     */
    private static void enemyShot(GameData game, String name) throws Exception {
        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        int zone = -1, ex = 0, ez = 0;
        for (int i = 0; i < lv.objects.size(); i++) {
            int b = lv.ptrObjects + i * GameObject.SIZE;
            if (Enemy.kindOf(lv.data.u8(b + Obj.TYPE)) != null
                    && lv.data.s16(b + Obj.ZONE) >= 0) {
                zone = lv.data.s16(b + Obj.ZONE);
                int pt = lv.data.s16(b + Obj.POINT);
                ex = lv.objectPointX[pt];
                ez = lv.objectPointZ[pt];
                break;
            }
        }
        int standY = lv.zone(zone).floorHeight - Player.EYE_HEIGHT;
        int px = ex - 300, pz = ez;

        // put the player's record where they stand, as jg.s does every frame
        frame.updateObjects(zone, px, pz, standY, false, 1, 0);

        int before = frame.objectHandler.energy;
        boolean went = frame.bullets.fireEnemy(frame.gunData, 6, 7, 32, 4,
                                               ex, ez, standY, zone, false,
                                               px, pz, standY);
        System.out.printf("one enemy shot from %d away: spawned %b%n", 300, went);
        int frames = 0;
        for (int t = 0; t < 200; t++) {
            frame.updateObjects(zone, px, pz, standY, false, 1, 0);
            frames++;
            if (frame.bullets.hitTarget > 0 || frame.bullets.hitWall > 0
                    || frame.bullets.hitSurface > 0 || frame.bullets.expired > 0) {
                break;
            }
        }
        int energy = frame.usePlayer(before);
        System.out.printf("  after %d frames: %d hit the player, %d a wall, "
                          + "%d a surface, %d expired%n", frames,
                          frame.bullets.hitTarget, frame.bullets.hitWall,
                          frame.bullets.hitSurface, frame.bullets.expired);
        System.out.printf("  energy %d became %d (the shot's power is 7)%n",
                          before, energy);
    }

    private static void sweep(GameData game, String name, int gun, int away)
            throws Exception {
        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        int victim = -1;
        for (int i = 0; i < lv.objects.size(); i++) {
            int b = lv.ptrObjects + i * GameObject.SIZE;
            if (Enemy.kindOf(lv.data.u8(b + Obj.TYPE)) != null
                    && lv.data.s16(b + Obj.ZONE) >= 0
                    && lv.data.u8(b + Obj.NUM_LIVES) > 0) {
                victim = i;
                break;
            }
        }
        int base = lv.ptrObjects + victim * GameObject.SIZE;
        int zone = lv.data.s16(base + Obj.ZONE);
        int pt = lv.data.s16(base + Obj.POINT);
        int vx = lv.objectPointX[pt], vz = lv.objectPointZ[pt];
        int standY = lv.zone(zone).floorHeight - Player.EYE_HEIGHT;
        int lives = lv.data.u8(base + Obj.NUM_LIVES);

        frame.gunSelected = gun;
        frame.gunData.setByte(gun, GunData.GOT_GUN, 0xff);
        frame.gunData.setWord(gun, GunData.AMMO, GunData.AMMO_MAX);
        for (int t = 0; t < 400 && lv.data.u8(base + Obj.NUM_LIVES) > 0; t++) {
            frame.firePlayer(true, t == 0, 1, vx - away, vz, standY,
                             Player.EYE_HEIGHT, angleTo(vx - away, vz, vx, vz),
                             zone, false);
            frame.armObjects(zone);
            frame.updateObjects(zone, vx - away, vz, standY, false, 1, 0);
        }
        System.out.printf("  from %3d away: %d hit the target, %d expired, "
                          + "%d lives left of %d%n", away,
                          frame.bullets.hitTarget, frame.bullets.expired,
                          lv.data.u8(base + Obj.NUM_LIVES), lives);
    }

    private static int angleTo(int fromX, int fromZ, int toX, int toZ) {
        double a = Math.atan2(toX - fromX, toZ - fromZ);
        return ((int) Math.round(a / (2 * Math.PI) * 4096)) & 4095;
    }
}
