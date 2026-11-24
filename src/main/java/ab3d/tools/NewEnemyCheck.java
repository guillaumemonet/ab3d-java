package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;
import ab3d.engine.Enemy;
import ab3d.engine.Frame68k;
import ab3d.engine.GasPipe;
import ab3d.engine.Obj;
import ab3d.engine.ObjectHandler;
import ab3d.engine.Robot;
import ab3d.game.Player;

/**
 * Do the things the later levels place actually do something?
 *
 * The first four levels place only the alien, the flying alien, the three
 * marines and the barrel, so for a long time nothing past those was ported --
 * and the census that said the rest were never placed had looked at those four
 * levels alone. The twelve after them place forty-nine worms, thirty-four gas
 * pipes, ten trees, a robot and the big red thing. This takes one of each
 * that is ported, stands the player in its zone, and lets the world run:
 *
 * <ul>
 * <li>a worm must fire, and in bursts of four;</li>
 * <li>a tree must sow eyeballs, and an eyeball must move and fire;</li>
 * <li>the big red thing must fire rockets two at a time;</li>
 * <li>a gas pipe must puff flame on its own rhythm, seen or not;</li>
 * <li>and each, killed, must leave what its file says: a body, nothing, a key.</li>
 * </ul>
 *
 * It also measures how far an enemy shot travels in one frame against the
 * speed it was given, which is what showed shots being flown twice a frame.
 */
public final class NewEnemyCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        shotSpeed(game, "level_b");
        live(game, "level_f", Enemy.WORM.type(), "worm");
        live(game, "level_f", Enemy.TREE.type(), "tree");
        live(game, "level_p", Enemy.BIG_RED.type(), "big red thing");
        live(game, "level_g", GasPipe.TYPE, "gas pipe");
        live(game, "level_h", Robot.TYPE, "robot");
        census(game);
    }

    /** One enemy shot, one frame: how far did it go? */
    private static void shotSpeed(GameData game, String name) throws Exception {
        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        int zone = lv.startZone;
        int x = lv.startX, z = lv.startZ;
        int y = lv.zone(zone).floorHeight - 50 * 128;
        frame.bullets.fireEnemy(frame.gunData, 0, 5, 16, 3, 0, x, z, y, zone,
                                false, x + 1000, z, y);
        int shot = -1;
        for (int j = 0; j < 20; j++) {
            int b = lv.ptrEnemyShots + j * GameObject.SIZE;
            if (lv.data.s16(b + Obj.ZONE) >= 0) {
                shot = b;
                break;
            }
        }
        int pt = lv.data.s16(shot + Obj.POINT);
        int x0 = lv.objectPointX[pt];
        int vel = lv.data.s32(shot + Obj.SHOT_XVEL) >> 16;
        frame.updateObjects(zone, x - 3000, z, y, false, 1, 0);
        int moved = lv.objectPointX[pt] - x0;
        System.out.printf("enemy shot: given %d a frame, moved %d in one frame%s%n%n",
                          vel, moved, moved == vel ? "" : "  <-- not the speed it was given");
    }

    private static int find(Level lv, int type) {
        for (int i = 0; i < lv.objects.size(); i++) {
            int base = lv.ptrObjects + i * GameObject.SIZE;
            if (lv.data.s16(base) < 0) {
                break;
            }
            if (lv.data.s8(base + Obj.TYPE) == type
                    && lv.data.s16(base + Obj.ZONE) >= 0) {
                return base;
            }
        }
        return -1;
    }

    private static void live(GameData game, String name, int type, String what)
            throws Exception {
        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        int base = find(lv, type);
        if (base < 0) {
            System.out.println(what + ": none in " + name);
            return;
        }
        int zone = lv.data.s16(base + Obj.ZONE);
        int pt = lv.data.s16(base + Obj.POINT);
        int ex = lv.objectPointX[pt], ez = lv.objectPointZ[pt];
        // the player stands in the same zone, a little off: the line-of-sight
        // test is between zones, so this is enough for it to be seen
        int px = ex + 1, pz = ez + 1;
        int py = lv.zone(zone).floorHeight - Player.EYE_HEIGHT;
        System.out.printf("%s in %s: record %d, zone %d, lives %d, timer %d%n",
                          what, name, (base - lv.ptrObjects) / 64, zone,
                          lv.data.u8(base + Obj.NUM_LIVES),
                          lv.data.s16(base + Obj.OBJ_TIMER));

        int shotsBefore = frame.enemies.shots;
        StringBuilder fired = new StringBuilder();
        int lastShots = 0;
        for (int t = 0; t < 600; t++) {
            frame.armObjects(zone);
            frame.updateObjects(zone, px, pz, py, false, 1, 0);
            int s = frame.enemies.shots + frame.gasPipe.puffs + frame.robot.shots;
            if (s != lastShots) {
                fired.append(t).append(' ');
                lastShots = s;
            }
        }
        if (fired.length() > 160) {
            fired.setLength(160);
            fired.append("...");
        }
        System.out.printf("   600 frames: %d shots, %d eyeballs sown, %d flames, "
                          + "%d pipe bursts, instant damage to the player %d (shots that fly are not counted here)%n",
                          frame.enemies.shots - shotsBefore + frame.robot.shots,
                          frame.enemies.eyesSown,
                          frame.bullets.flames, frame.gasPipe.bursts,
                          frame.enemies.playerDamage);
        System.out.println("   fired on frames: " + fired);
        if (type == Robot.TYPE) {
            System.out.printf("   robot: %d frames prowling, %d attacking, %d turning "
                              + "on the spot; now at (%d,%d) facing %d, frame %d%n",
                              frame.robot.prowled, frame.robot.attacked,
                              frame.robot.turnsOnSpot,
                              lv.objectPointX[pt], lv.objectPointZ[pt],
                              lv.data.s16(base + Obj.FACING),
                              lv.data.s16(base + Obj.FRAME));
        }

        if (type == Enemy.TREE.type()) {
            eyes(lv);
        }
        if (type == GasPipe.TYPE) {
            System.out.println();
            return;
        }

        // now kill it: a hit big enough for anything
        lv.data.setU8(base + Obj.NUM_LIVES, 1);
        lv.data.setU8(base + Obj.DAMAGE_TAKEN, 100);
        int keysBefore = frame.objectHandler.keysTaken;
        for (int t = 0; t < 40; t++) {
            frame.armObjects(zone);
            frame.updateObjects(zone, px + 3000, pz, py, false, 1, 0);
        }
        int now = lv.data.s8(base + Obj.TYPE);
        System.out.printf("   killed: type %d, zone %d, lives %d, frame %d:%d%s%n",
                          now, lv.data.s16(base + Obj.ZONE),
                          lv.data.u8(base + Obj.NUM_LIVES),
                          lv.data.s16(base + Obj.SLOT), lv.data.s16(base + Obj.FRAME),
                          now == ObjectHandler.TYPE_KEY
                                  ? "  -- a key granting " + lv.data.u8(base + Obj.FLAGS)
                                  : lv.data.s16(base + Obj.ZONE) < 0 ? "  -- gone"
                                  : "  -- a body");
        if (now == ObjectHandler.TYPE_KEY) {
            int conditions = 0;

            for (int t = 0; t < 3; t++) {
                frame.armObjects(zone);
                // where it lies now: it walked for six hundred frames first
                frame.updateObjects(lv.data.s16(base + Obj.ZONE),
                                    lv.objectPointX[pt], lv.objectPointZ[pt],
                                    py, false, 1, 0);
            }
            System.out.printf("   walked onto it: %d key taken, conditions %s%n",
                              frame.objectHandler.keysTaken - keysBefore,
                              Integer.toBinaryString(frame.conditions | conditions));
        }
        System.out.println();
    }

    /** What the tree sowed into the third pool. */
    private static void eyes(Level lv) {
        int pool = lv.ptrEnemyShots + 64 * 20;
        StringBuilder b = new StringBuilder("   eyeballs:");
        for (int i = 0; i < 20; i++) {
            int at = pool + i * 64;
            if (lv.data.s8(at + Obj.TYPE) == Enemy.EYE.type()) {
                int pt = lv.data.s16(at + Obj.POINT);
                b.append(String.format(" [zone %d at %d,%d lives %d frame %d:%d]",
                        lv.data.s16(at + Obj.ZONE), lv.objectPointX[pt],
                        lv.objectPointZ[pt], lv.data.u8(at + Obj.NUM_LIVES),
                        lv.data.s16(at + Obj.SLOT), lv.data.s16(at + Obj.FRAME)));
            }
        }
        System.out.println(b);
    }

    /** Which placed types still have no routine. */
    private static void census(GameData game) throws Exception {
        java.util.Map<Integer, Integer> none = new java.util.TreeMap<>();
        for (String n : game.diskLevels()) {
            Level lv = Level.load(game, n);
            for (int i = 0; i < lv.objects.size(); i++) {
                int base = lv.ptrObjects + i * GameObject.SIZE;
                if (lv.data.s16(base) < 0) {
                    break;
                }
                if (lv.data.s16(base + Obj.ZONE) < 0) {
                    continue;
                }
                int t = lv.data.s8(base + Obj.TYPE);
                boolean handled = Enemy.kindOf(t) != null || t == GasPipe.TYPE
                        || t == Robot.TYPE
                        || t == 1 || t == 3 || t == 4 || t == 9 || t == 10
                        || t == 5 || t == 11;
                if (!handled) {
                    none.merge(t, 1, Integer::sum);
                }
            }
        }
        System.out.println("placed but without a routine, by type: "
                           + (none.isEmpty() ? "none" : none));
    }
}
