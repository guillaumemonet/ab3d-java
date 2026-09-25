package ab3d.tools;

import ab3d.data.BulletAnims;
import ab3d.data.GameData;

/**
 * Do the bullet tables read back as the source writes them?
 *
 * Three tables indexed the same way, written in three different styles -- words,
 * longwords naming labels, and lists whose steps mix {@code dc.b} and
 * {@code dc.w} for the same field -- so each is a chance to be off by one and
 * still produce numbers. The figures worth recognising are the explosive forces:
 * only two shots have one at all, sixty-four and forty, and they are the rocket
 * and the grenade.
 */
public final class BulletAnimCheck {

    private static final String[] NAMES = {
        "pulse rifle", "plasma gun", "rocket launcher", "flame thrower",
        "grenade launcher", "(none)", "(none)", "shotgun",
    };

    public static void main(String[] args) throws Exception {
        BulletAnims a = BulletAnims.load(GameData.fromSystemProperty());
        System.out.printf("%-18s %6s %6s %7s %6s %6s%n", "shot", "flying",
                          "burst", "force", "steps", "burst");
        for (int i = 0; i < NAMES.length; i++) {
            System.out.printf("%-18s $%04x  $%04x %7d %6d %6d%n", NAMES[i],
                              a.flyingSize(i), a.burstSize(i), a.force(i),
                              a.flight(i).length, a.burst(i).length);
        }
        System.out.println();
        for (int i : new int[]{0, 2, 4}) {
            System.out.printf("%s flight:", NAMES[i]);
            for (BulletAnims.Step s : a.flight(i)) {
                System.out.printf(" [%d:%d%+d]", s.slot(), s.frame(), s.rise());
            }
            System.out.println();
            System.out.printf("%s burst: ", NAMES[i]);
            for (BulletAnims.Step s : a.burst(i)) {
                System.out.printf(" [%d:%d%+d]", s.slot(), s.frame(), s.rise());
            }
            System.out.println();
        }
    }
}
