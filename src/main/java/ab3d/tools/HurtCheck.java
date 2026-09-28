package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.Obj;
import ab3d.engine.Frame68k;
import ab3d.data.GameObject;

/**
 * Does standing next to an alien actually cost the player energy?
 *
 * {@link EnemyCheck} answers a narrower question than it looks like it does. It
 * runs the enemies and prints {@code playerDamage} raw, so it shows damage being
 * worked out; it never calls {@code USEPLR1}, which is the half that turns that
 * number into lost energy and clears it. A port could pass that check with the
 * second half missing entirely, which is why this one exists.
 *
 * So this drives the loop in the order Ab3dGame does -- arm, move the objects,
 * then {@code usePlayer} -- and watches the energy itself.
 */
public final class HurtCheck {

    private static final int FRAMES = 3000;
    private static final int START_ENERGY = 1000;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : new String[]{"level_a", "level_d", "level_c"}) {
            run(game, name);
        }
        // and the two that fire a real bullet rather than rolling a die: their
        // shot has to travel and find the player's own record to do anything
        for (String name : game.diskLevels()) {
            projectile(game, name);
        }
    }

    private static void run(GameData game, String name) throws Exception {
        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        frame.objectHandler.energy = START_ENERGY;

        // stand on top of the first enemy the level places: jg.s only wakes what
        // the viewer's own zone can see, so anywhere else and nothing happens
        int found = -1;
        for (int i = 0; i < lv.objects.size(); i++) {
            int base = lv.ptrObjects + i * GameObject.SIZE;
            int type = lv.data.u8(base + Obj.TYPE);
            if (type == 0 || type == 12 || type == 18 || type == 19 || type == 8) {
                found = base;
                break;
            }
        }
        if (found < 0) {
            System.out.printf("%-8s no enemy placed%n", name);
            return;
        }

        // Stand where the game starts the player, not on top of the alien: the
        // question is whether the loop hurts them where they actually are.
        ab3d.game.Player player = new ab3d.game.Player(lv);
        int px = player.camera.x;
        int pz = player.camera.z;
        int zone = player.camera.zone;
        int py = player.camera.yoff;

        int plr = lv.ptrPlr1Obj;
        int idx = (plr - lv.ptrObjects) / GameObject.SIZE;
        System.out.printf("%-8s PLR1_Obj at %d, objects at %d, %d records: "
                          + "index %d -> %s%n",
                          name, plr, lv.ptrObjects, lv.objects.size(), idx,
                          idx >= 0 && idx < lv.objects.size()
                              ? "inside the array the bullets scan"
                              : "OUTSIDE it, so no enemy shot can find them");

        int firstHurt = -1;
        int flashes = 0;
        for (int t = 0; t < FRAMES; t++) {
            frame.updateObjects(zone, px, pz, py, player.stoodInTop, 1,
                                player.camera.angle);
            frame.armObjects(zone);
            int before = frame.objectHandler.energy;
            frame.objectHandler.energy = frame.usePlayer(before);
            if (frame.hitFlash) {
                flashes++;
            }
            if (frame.objectHandler.energy < before && firstHurt < 0) {
                firstHurt = t;
            }
        }

        int lost = START_ENERGY - frame.objectHandler.energy;
        System.out.printf("%-8s type %2d: %d bites, %d shots worked out, "
                          + "%d damage raised%n",
                          name, lv.data.u8(found + Obj.TYPE),
                          frame.enemies.bites, frame.enemies.shots,
                          frame.enemies.playerDamage);
        System.out.printf("         energy %d -> %d (lost %d), first hurt on "
                          + "frame %s, %d red flashes%n%n",
                          START_ENERGY, frame.objectHandler.energy, lost,
                          firstHurt < 0 ? "never" : String.valueOf(firstHurt),
                          flashes);
    }

    /**
     * A shot that has to arrive: the tough marine's and the flying alien's.
     *
     * The hitscan kinds add their damage where they stand, so they prove very
     * little about the bullet pool. These put a round in the air aimed at the
     * player, and it only counts if it reaches the player's record -- which is
     * the part that can silently not be wired up.
     */
    private static void projectile(GameData game, String name) throws Exception {
        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        frame.objectHandler.energy = START_ENERGY;

        int found = -1;
        for (int i = 0; i < lv.objects.size(); i++) {
            int base = lv.ptrObjects + i * GameObject.SIZE;
            int type = lv.data.u8(base + Obj.TYPE);
            if (type == 18 || type == 8) {          // tough marine, flying alien
                found = base;
                break;
            }
        }
        if (found < 0) {
            return;
        }

        int pt = lv.data.s16(found + Obj.POINT);
        // stand a little away, so the shot has somewhere to travel
        int px = lv.objectPointX[pt] + 300;
        int pz = lv.objectPointZ[pt];
        int zone = lv.data.s16(found + Obj.ZONE);
        int py = lv.data.s16(found + Obj.HEIGHT) << 7;

        int lost = 0;
        for (int t = 0; t < FRAMES; t++) {
            frame.updateObjects(zone, px, pz, py, false, 1, 0);
            frame.armObjects(zone);
            int before = frame.objectHandler.energy;
            frame.objectHandler.energy = frame.usePlayer(before);
            lost += before - frame.objectHandler.energy;
        }
        System.out.printf("%-8s type %2d fires bullets: %d shots, %d energy "
                          + "lost -> %s%n",
                          name, lv.data.u8(found + Obj.TYPE), frame.enemies.shots,
                          lost,
                          frame.enemies.shots == 0 ? "never got a shot off"
                              : lost > 0 ? "they land"
                              : "NONE OF THEM LAND");
    }
}
