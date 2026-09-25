package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GunAnims;

/**
 * Do the parsed animation lists match the counts the source writes out?
 *
 * {@code GunAnims} states each weapon's count twice over: once as the number in
 * the table, and once as the length of the list it points at. They are written
 * far apart and one is an arithmetic expression, so agreeing is evidence that
 * both were read right -- and the shotgun's sixty-four entries come almost
 * entirely from {@code dcb.w} runs, which a naive reading would miss.
 */
public final class GunAnimCheck {

    /** The second longword of each {@code GunAnims} entry, as jg.s writes it. */
    private static final int[] STATED = {3, 5, 5, 5, 12, 0, 0, 12 + 19 + 11 + 20 + 1};
    private static final String[] NAMES = {
        "pulse rifle", "plasma gun", "rocket launcher", "flame thrower",
        "grenade launcher", "(none)", "(none)", "shotgun",
    };

    public static void main(String[] args) throws Exception {
        GunAnims a = GunAnims.load(GameData.fromSystemProperty());
        int agree = 0;
        for (int i = 0; i < NAMES.length; i++) {
            int[] f = a.frames(i);
            boolean ok = f.length == 0 ? STATED[i] == 0
                                       : f.length - 1 == STATED[i];
            if (ok) {
                agree++;
            }
            System.out.printf("%-18s %2d frames, count %2d %s%n", NAMES[i],
                              f.length, a.maxFrame(i),
                              ok ? "" : "<-- the table says " + STATED[i]);
            if (f.length > 0 && f.length <= 16) {
                System.out.print("                   ");
                for (int v : f) {
                    System.out.print(v + " ");
                }
                System.out.println();
            }
        }
        System.out.printf("%n%d of %d lists agree with the table%n",
                          agree, NAMES.length);
    }
}
