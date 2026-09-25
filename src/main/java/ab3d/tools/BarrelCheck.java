package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.GunData;
import ab3d.data.Level;
import ab3d.engine.Barrel;
import ab3d.engine.Enemy;
import ab3d.engine.Frame68k;
import ab3d.engine.Obj;
import ab3d.game.Player;

/**
 * Does a barrel go off, and does going off hurt what stands near it?
 *
 * The barrel is worth its own check because it is the one thing in the game
 * that is a weapon without being a gun: killing it calls the same
 * {@code ComputeBlast} a grenade does, with the same force of forty, and the
 * blast is stopped by walls through the same room-list test the enemies see
 * through. So the claim is not just that it bursts but that the burst reaches
 * the things it should and nothing further.
 *
 * The gibs are the other half. An enemy killed by more than one point of damage
 * calls {@code ExplodeIntoBits}, which takes records from the enemy shot pool
 * and throws them out with gravity -- shots in every respect except that their
 * {@code EnemyFlags} is nought, so they hit nothing.
 */
public final class BarrelCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : new String[]{"level_a", "level_b", "level_d"}) {
            Level lv = Level.load(game, name);
            Frame68k frame = new Frame68k(lv, game);

            int barrel = -1;
            for (int i = 0; i < lv.objects.size(); i++) {
                int b = lv.ptrObjects + i * GameObject.SIZE;
                if (lv.data.u8(b + Obj.TYPE) == Barrel.TYPE
                        && lv.data.s16(b + Obj.ZONE) >= 0) {
                    barrel = i;
                    break;
                }
            }
            if (barrel < 0) {
                System.out.printf("%-8s no barrels%n", name);
                continue;
            }
            int base = lv.ptrObjects + barrel * GameObject.SIZE;
            int zone = lv.data.s16(base + Obj.ZONE);
            int pt = lv.data.s16(base + Obj.POINT);
            int bx = lv.objectPointX[pt], bz = lv.objectPointZ[pt];
            int standY = lv.zone(zone).floorHeight - Player.EYE_HEIGHT;
            int lives = lv.data.u8(base + Obj.NUM_LIVES);

            // who is near enough to feel it, and how many lives they have
            int near = 0;
            int[] before = new int[lv.objects.size()];
            for (int i = 0; i < lv.objects.size(); i++) {
                int b = lv.ptrObjects + i * GameObject.SIZE;
                before[i] = lv.data.u8(b + Obj.NUM_LIVES);
                if (Enemy.kindOf(lv.data.u8(b + Obj.TYPE)) != null
                        && lv.data.s16(b + Obj.ZONE) == zone) {
                    near++;
                }
            }

            frame.gunSelected = 0;
            frame.gunData.setWord(0, GunData.AMMO, GunData.AMMO_MAX);
            int shots = 0;
            for (int t = 0; t < 400 && lv.data.s16(base + Obj.SLOT) != 8; t++) {
                if (frame.firePlayer(true, t == 0, 1, bx - 60, bz, standY,
                                     Player.EYE_HEIGHT,
                                     angleTo(bx - 60, bz, bx, bz), zone, false)) {
                    shots++;
                }
                frame.armObjects(zone);
                frame.updateObjects(zone, bx - 60, bz, standY, false, 1, 0);
            }

            int hurt = 0;
            for (int i = 0; i < lv.objects.size(); i++) {
                int b = lv.ptrObjects + i * GameObject.SIZE;
                if (lv.data.u8(b + Obj.NUM_LIVES) < before[i]) {
                    hurt++;
                }
            }
            System.out.printf("%-8s barrel in zone %d, %d lives, %d enemies in "
                              + "that room%n", name, zone, lives, near);
            System.out.printf("         %d shots to break it: burst %d, blasted "
                              + "%d, %d things hurt for %d, %d pieces thrown%n",
                              shots, frame.barrel.burst, frame.barrel.blasted,
                              frame.bullets.blastHits, frame.bullets.blastDamage,
                              frame.bullets.bits);
            System.out.printf("         %d records lost lives in all%n", hurt);

            // and it should count itself out and vanish
            int gone = 0;
            for (int t = 0; t < 12; t++) {
                // ItsABarrel clears its own worry byte, so without arming it
                // again the dispatch skips it and the burst never counts on
                frame.armObjects(zone);
                frame.updateObjects(zone, bx - 60, bz, standY, false, 1, 0);
                if (lv.data.s16(base + Obj.ZONE) < 0) {
                    gone = t + 1;
                    break;
                }
            }
            System.out.printf("         gone after %d more frames (the burst is "
                              + "eight)%n%n", gone);
        }
    }

    private static int angleTo(int fromX, int fromZ, int toX, int toZ) {
        double a = Math.atan2(toX - fromX, toZ - fromZ);
        return ((int) Math.round(a / (2 * Math.PI) * 4096)) & 4095;
    }
}
