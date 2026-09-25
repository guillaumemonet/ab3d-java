package ab3d.tools;

import ab3d.game.Password;

import java.util.Random;

/**
 * Does a password survive the trip out and back, and does a wrong one fail?
 *
 * The interleave is the part worth testing: {@code mixemup} shifts two bytes
 * into one word a bit at a time and {@code unmix} takes them apart again, and
 * getting either direction's bit order wrong still produces sixteen plausible
 * letters. Only the round trip catches it.
 *
 * The second half is the point of the checks. Every single-letter change should
 * be rejected -- that is what the six parity bits and the checksum are for --
 * and a password that is accepted after being tampered with is worth more than
 * one that is merely decoded correctly.
 */
public final class PasswordCheck {

    public static void main(String[] args) {
        if (args.length > 0) {
            Password.State s = Password.decode(args[0].toUpperCase());
            System.out.println(s == null ? "rejected" : describe(s));
            return;
        }

        Random rnd = new Random(20260923);
        int trips = 0, tripsOk = 0;
        for (int i = 0; i < 20000; i++) {
            Password.State s = new Password.State(
                    rnd.nextInt(128), rnd.nextInt(16), rnd.nextInt(2),
                    new int[]{rnd.nextInt(128) << 3, rnd.nextInt(128) << 3,
                              rnd.nextInt(128) << 3, rnd.nextInt(128) << 3,
                              rnd.nextInt(128) << 3});
            String word = Password.encode(s);
            Password.State back = Password.decode(word);
            trips++;
            if (back != null && same(s, back)) {
                tripsOk++;
            } else if (tripsOk == trips - 1 && trips < 4) {
                System.out.println("  out  " + describe(s) + " -> " + word);
                System.out.println("  back " + (back == null ? "rejected"
                                                             : describe(back)));
            }
        }
        System.out.printf("round trip: %d of %d came back unchanged%n", tripsOk, trips);

        // every letter of a good password, changed to each of the other fifteen
        String word = Password.encode(new Password.State(
                100, 3, 1, new int[]{200, 48, 0, 96, 24}));
        System.out.println("a level 4 password: " + word);
        int tried = 0, rejected = 0, slipped = 0;
        for (int i = 0; i < Password.LETTERS; i++) {
            for (char c = 'A'; c <= 'P'; c++) {
                if (c == word.charAt(i)) {
                    continue;
                }
                char[] bad = word.toCharArray();
                bad[i] = c;
                tried++;
                if (Password.decode(new String(bad)) == null) {
                    rejected++;
                } else {
                    slipped++;
                }
            }
        }
        System.out.printf("one letter changed: %d tried, %d rejected, %d slipped through%n",
                          tried, rejected, slipped);

        // Where the slips are is the whole question. Parity is one bit a byte, so
        // it misses a change that flips two bits of the same byte -- and a letter
        // is four bits split two and two between a byte and its partner, so such a
        // change exists for every letter. What it cannot reach is the byte holding
        // the level, because that one is covered by a full checksum byte as well.
        Password.State good = Password.decode(word);
        int changedLevel = 0, changedSomething = 0;
        for (int i = 0; i < Password.LETTERS; i++) {
            for (char c = 'A'; c <= 'P'; c++) {
                if (c == word.charAt(i)) {
                    continue;
                }
                char[] bad = word.toCharArray();
                bad[i] = c;
                Password.State s2 = Password.decode(new String(bad));
                if (s2 == null) {
                    continue;
                }
                changedSomething++;
                if (s2.level() != good.level()) {
                    changedLevel++;
                }
            }
        }
        System.out.printf("of the %d that slipped, %d changed the level%n",
                          changedSomething, changedLevel);

        // and the level it names is the one it was made from
        int levelsOk = 0;
        for (int level = 0; level < 16; level++) {
            String w = Password.encode(new Password.State(
                    127, level, 0, new int[]{0, 0, 0, 0, 0}));
            Password.State back = Password.decode(w);
            if (back != null && back.level() == level) {
                levelsOk++;
            }
            System.out.printf("  level %2d: %s%n", level + 1, w);
        }
        System.out.printf("levels: %d of 16 come back%n", levelsOk);
    }

    private static boolean same(Password.State a, Password.State b) {
        if (a.energy() != b.energy() || a.level() != b.level()) {
            return false;
        }
        for (int i = 0; i < 5; i++) {
            if (a.gunAmmo()[i] != b.gunAmmo()[i]) {
                return false;
            }
        }
        return true;
    }

    private static String describe(Password.State s) {
        return String.format("level %d, energy %d, guns %x, ammo %s",
                             s.level() + 1, s.energy(), s.gunFlags(),
                             java.util.Arrays.toString(s.gunAmmo()));
    }
}
