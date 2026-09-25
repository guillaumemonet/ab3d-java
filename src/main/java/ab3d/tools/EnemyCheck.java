package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.GunData;
import ab3d.data.Level;
import ab3d.engine.Enemy;
import ab3d.engine.Frame68k;
import ab3d.engine.Obj;
import ab3d.game.Player;

/**
 * Do the enemies move, notice the player, hurt them, and die when shot?
 *
 * Four claims, each failing differently. They should walk, which means their
 * points move and they stay inside rooms -- one that left the level would still
 * look busy from the counters alone. They should notice the player, because
 * {@code Player1Shot} refuses to aim at anything whose sight bit is clear, so a
 * blind enemy is also an invulnerable one. They should do damage: the alien by
 * biting at a hundred and sixty units, the marine by shooting from eighty.
 * And once the lives reach nothing they should stop where they fell.
 */
public final class EnemyCheck {

    private static final int FRAMES = 300;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            for (Enemy.Kind k : Enemy.KINDS) {
                run(game, name, k);
            }
        }
    }

    private static void run(GameData game, String name, Enemy.Kind k)
            throws Exception {
        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        Player p = new Player(lv);

        java.util.List<Integer> mine = new java.util.ArrayList<>();
        for (int i = 0; i < lv.objects.size(); i++) {
            int base = lv.ptrObjects + i * GameObject.SIZE;
            if (lv.data.s8(base + Obj.TYPE) == k.type()
                    && lv.data.s16(base + Obj.ZONE) >= 0) {
                mine.add(i);
            }
        }
        if (mine.isEmpty()) {
            System.out.printf("%-8s %-7s none placed%n", name, k.name());
            return;
        }

        int[] startX = new int[mine.size()], startZ = new int[mine.size()];
        for (int j = 0; j < mine.size(); j++) {
            int pt = lv.data.s16(lv.ptrObjects + mine.get(j) * GameObject.SIZE);
            startX[j] = lv.objectPointX[pt];
            startZ[j] = lv.objectPointZ[pt];
        }

        // stand where the first one is: jg.s only wakes what the viewer can see
        int base0 = lv.ptrObjects + mine.get(0) * GameObject.SIZE;
        int watch = lv.data.s16(base0 + Obj.ZONE);
        int wx = startX[0], wz = startZ[0];
        for (int t = 0; t < FRAMES; t++) {
            frame.armObjects(watch);
            frame.updateObjects(watch, wx, wz, lv.data.s16(base0 + Obj.HEIGHT) << 7,
                                false, 1, 0);
        }

        int moved = 0, stray = 0, awake = 0;
        for (int j = 0; j < mine.size(); j++) {
            int base = lv.ptrObjects + mine.get(j) * GameObject.SIZE;
            int pt = lv.data.s16(base + Obj.POINT);
            if (lv.objectPointX[pt] != startX[j] || lv.objectPointZ[pt] != startZ[j]) {
                moved++;
            }
            int z = lv.data.s16(base + Obj.ZONE);
            if (z < 0 || z >= lv.zones.length) {
                stray++;
            }
            if (lv.data.u8(base + Obj.WORRY) != 0) {
                awake++;
            }
        }
        // The counters are shared: ObjectHandler walks every object, so aliens
        // standing near a marine bite during the marine's run. Only the placed
        // and moved figures are this kind's alone.
        System.out.printf("%-8s %-7s %2d placed, %2d awake, %2d moved, %d strayed; "
                          + "in that room: %d charges, %d bites, %d shots, "
                          + "%d damage to the player, %d shots needing the "
                          + "bullet pool%n",
                          name, k.name(), mine.size(), awake, moved, stray,
                          frame.enemies.charged, frame.enemies.bites,
                          frame.enemies.shots, frame.enemies.playerDamage,
                          frame.enemies.unported);

        // and shoot one of the ones that has noticed us
        int victim = -1;
        for (int j : mine) {
            int base = lv.ptrObjects + j * GameObject.SIZE;
            if ((lv.data.u8(base + Obj.FLAGS) & Obj.SEES_PLAYER1) != 0
                    && lv.data.u8(base + Obj.NUM_LIVES) > 0) {
                victim = j;
                break;
            }
        }
        if (victim < 0) {
            System.out.println("           none of them has noticed the player, "
                               + "so none can be shot");
            return;
        }
        int base = lv.ptrObjects + victim * GameObject.SIZE;
        int pt = lv.data.s16(base + Obj.POINT);
        int vx = lv.objectPointX[pt], vz = lv.objectPointZ[pt];
        int standY = lv.data.s16(base + Obj.HEIGHT) << 7;
        int lives = lv.data.u8(base + Obj.NUM_LIVES);

        frame.gunSelected = 0;
        frame.gunData.setWord(0, GunData.AMMO, GunData.AMMO_MAX);
        // Measure on this one's own record. The shot aims at whatever is nearest
        // in line, which in a room full of them need not be the one being watched,
        // so a global damage count says nothing about this one.
        int shots = 0, aimedHere = 0, hitsHere = 0;
        for (int t = 0; t < 400 && lv.data.u8(base + Obj.NUM_LIVES) > 0; t++) {
            if (frame.firePlayer(true, t == 0, 1, vx - 40, vz, standY,
                                 Player.EYE_HEIGHT, angleTo(vx - 40, vz, vx, vz))) {
                shots++;
                if (frame.shot.lastTarget == victim) {
                    aimedHere++;
                    hitsHere += frame.shot.lastHits;
                }
            }
            // arm every frame, as jg.s does: worry decays by one an update, and
            // an enemy that falls asleep stops taking the damage it has been
            // dealt as well as stopping moving
            frame.armObjects(lv.data.s16(base + Obj.ZONE));
            frame.updateObjects(lv.data.s16(base + Obj.ZONE), vx - 40, vz,
                                standY, false, 1, 0);
        }
        // The claim: every pellet that landed on it took the gun's damage off.
        // Two things stand between a shot and a hit, and both are the original's.
        // The aim moves on as soon as another comes nearer, which in a room of
        // twenty-eight marines happens quickly; and each pellet is rolled against
        // the squared distance, so a target that backs away survives shots that
        // were aimed squarely at it.
        int left = lv.data.u8(base + Obj.NUM_LIVES);
        int damage = frame.gunData.byteAt(0, GunData.DAMAGE);
        int expect = Math.max(0, lives - hitsHere * damage);
        System.out.printf("           shot one with %3d lives: %2d shots, %2d aimed "
                          + "at it, %2d landed, %3d lives left (%d expected)%s%n",
                          lives, shots, aimedHere, hitsHere, left, expect,
                          left == expect ? "" : "  <-- does not add up");

        // the dying frames only start once the lives are gone
        StringBuilder f = new StringBuilder();
        for (int t = 0; t < 26; t++) {
            frame.armObjects(lv.data.s16(base + Obj.ZONE));
            frame.updateObjects(lv.data.s16(base + Obj.ZONE), vx - 40, vz,
                                standY, false, 1, 0);
            if (t % 5 == 0) {
                f.append(lv.data.s16(base + Obj.FRAME)).append(' ');
            }
        }
        System.out.printf("           dying frames: %s(the list is %s)%n", f,
                          summarise(k.dying()));
    }

    private static String summarise(int[] a) {
        if (a.length == 0) {
            return "empty -- it falls to the floor and bursts instead";
        }
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < a.length) {
            int j = i;
            while (j < a.length && a[j] == a[i]) {
                j++;
            }
            b.append(j - i).append(" of ").append(a[i]).append(", ");
            i = j;
        }
        return b.substring(0, b.length() - 2);
    }

    /** In the camera's units, where a full turn is four thousand and ninety-six. */
    private static int angleTo(int fromX, int fromZ, int toX, int toZ) {
        double a = Math.atan2(toX - fromX, toZ - fromZ);
        return ((int) Math.round(a / (2 * Math.PI) * 4096)) & 4095;
    }
}
