package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GunData;

/**
 * Does the parsed gun table land on the right boundaries?
 *
 * The table is eight records of thirty-two bytes written as a mixture of
 * {@code dc.w}, {@code dc.b} and {@code ds.w}, so a single miscounted width
 * would shift everything after it and still produce plausible-looking numbers.
 * The check is that the fields jg.s comments on hold what it says they hold: the
 * pulse rifle is the only gun the player starts with, the flame thrower is the
 * only one with ammunition in it, and the five selectable slots are the five the
 * controls screen names.
 */
public final class GunCheckData {

    private static final String[] NAMES = {
        "pulse rifle", "plasma gun", "rocket launcher", "(unused 3)",
        "grenade launcher", "(unused 5)", "(unused 6)", "shotgun",
    };

    public static void main(String[] args) throws Exception {
        GunData g = GunData.load(GameData.fromSystemProperty());

        System.out.printf("%-18s %5s %4s %4s %4s %4s %5s %5s %4s %4s%n",
                          "gun", "ammo", "clip", "dmg", "got", "inst",
                          "delay", "life", "hold", "spd");
        for (int i = 0; i < GunData.GUNS; i++) {
            System.out.printf("%-18s %5d %4d %4d %4d %4d %5d %5d %4d %4d%n",
                              NAMES[i],
                              g.unsigned(i, GunData.AMMO),
                              g.byteAt(i, GunData.CLIP),
                              g.byteAt(i, GunData.DAMAGE),
                              g.byteAt(i, GunData.GOT_GUN),
                              g.byteAt(i, GunData.INSTANT),
                              g.word(i, GunData.DELAY),
                              g.word(i, GunData.LIFETIME),
                              g.word(i, GunData.HOLD),
                              g.word(i, GunData.SPEED));
        }

        System.out.println();
        int held = 0;
        for (int i = 0; i < GunData.GUNS; i++) {
            if (g.has(i)) {
                held++;
                System.out.println("starts holding: " + NAMES[i]);
            }
        }
        System.out.println("guns held at the start: " + held + " (expected 1)");
        System.out.printf("flame thrower ammunition: %d (expected %d, which is 90*8)%n",
                          g.unsigned(3, GunData.AMMO), 90 * 8);

        // and what DEFAULTGAME leaves, which is what the bar shows at the start
        g.defaultGame();
        System.out.printf("%nafter DEFAULTGAME: pistol %d rounds (bar shows %d of 63), "
                          + "flame thrower %d%n",
                          g.unsigned(0, GunData.AMMO), g.shownAmmo(0),
                          g.unsigned(3, GunData.AMMO));
        int after = 0;
        for (int i = 0; i < GunData.GUNS; i++) {
            if (g.has(i)) {
                after++;
            }
        }
        System.out.println("guns held after DEFAULTGAME: " + after + " (expected 1)");

        System.out.println();
        String[] keys = {"PULSE RIFLE", "SHOTGUN", "PLASMA GUN",
                         "GRENADE LAUNCHER", "ROCKET LAUNCHER"};
        for (int k = 0; k < GunData.GUNVALS.length; k++) {
            int slot = GunData.GUNVALS[k];
            System.out.printf("  key %d -> slot %d, %-17s %s%n", k + 1, slot,
                              NAMES[slot],
                              NAMES[slot].equalsIgnoreCase(keys[k]) ? "" : "<-- differs");
        }
    }
}
