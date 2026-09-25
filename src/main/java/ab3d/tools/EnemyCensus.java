package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;

/**
 * Which kinds of thing the shipped levels actually place.
 *
 * source/aliencontrol.s pulls in eleven enemy files of roughly nine hundred
 * lines each, and they are near-copies of one another. Before transcribing any
 * of them it is worth knowing which are used at all, and in what numbers -- the
 * same question that showed the see-through walls and the face panel to be dead
 * weight in this build.
 *
 * {@code numlives} is the other half of the answer. {@code Player1Shot} skips
 * anything with none, so a placed enemy with a zero there is inert whatever its
 * routine does.
 */
public final class EnemyCensus {

    /** The dispatch values of {@code ObjectHandler}, with their routines. */
    private static final String[] NAME = new String[24];

    static {
        NAME[0] = "nasty (NormalAlien)";
        NAME[1] = "medikit";
        NAME[2] = "bullet in flight";
        NAME[3] = "gun";
        NAME[4] = "key";
        NAME[5] = "player 1";
        NAME[6] = "robot (Robot)";
        NAME[8] = "flying nasty (FlyingScalyBall)";
        NAME[9] = "ammo clip";
        NAME[10] = "barrel";
        NAME[11] = "player 2";
        NAME[12] = "marine (MutantMarine)";
        NAME[13] = "worm (halfworm)";
        NAME[14] = "wellhard (bigredthing)";
        NAME[16] = "tree";
        NAME[17] = "eyeball (EyeBall)";
        NAME[18] = "tough marine (ToughMarine)";
        NAME[19] = "flame marine (FlameMarine)";
        NAME[20] = "gas pipe";
    }

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        int[] total = new int[NAME.length];
        int[] alive = new int[NAME.length];

        for (String name : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            int[] count = new int[NAME.length];
            int[] withLives = new int[NAME.length];
            for (int i = 0; i < lv.objects.size(); i++) {
                GameObject o = lv.objects.get(i);
                int base = lv.ptrObjects + i * GameObject.SIZE;
                if (o.notInPlay) {
                    continue;
                }
                int t = lv.data.s8(base + 16);
                if (t < 0 || t >= count.length) {
                    continue;
                }
                count[t]++;
                total[t]++;
                if (lv.data.u8(base + 18) != 0) {   // numlives
                    withLives[t]++;
                    alive[t]++;
                }
            }
            StringBuilder b = new StringBuilder();
            for (int t = 0; t < count.length; t++) {
                if (count[t] > 0 && isEnemy(t)) {
                    b.append(String.format("  %d %s", count[t],
                                           NAME[t] == null ? "type " + t : NAME[t]));
                    if (withLives[t] != count[t]) {
                        b.append(String.format(" (%d with lives)", withLives[t]));
                    }
                }
            }
            System.out.printf("%-8s%s%n", name, b.length() == 0 ? "  none" : b);
        }

        System.out.println("\nacross the four levels, enemies only:");
        int kinds = 0, placed = 0;
        for (int t = 0; t < total.length; t++) {
            if (total[t] > 0 && isEnemy(t)) {
                kinds++;
                placed += total[t];
                System.out.printf("  %-32s %3d placed, %3d with lives%n",
                                  NAME[t] == null ? "type " + t : NAME[t],
                                  total[t], alive[t]);
            }
        }
        System.out.printf("%n%d kinds, %d placed in all%n", kinds, placed);
        System.out.println("\nnever placed:");
        for (int t = 0; t < total.length; t++) {
            if (total[t] == 0 && isEnemy(t) && NAME[t] != null) {
                System.out.println("  " + NAME[t]);
            }
        }
    }

    /** Everything that is not a pickup, a player or a bullet already in flight. */
    private static boolean isEnemy(int t) {
        return t != 1 && t != 2 && t != 3 && t != 4 && t != 5 && t != 9 && t != 11;
    }
}
