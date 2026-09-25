package ab3d.game;

/**
 * {@code CALCPASSWORD} and {@code PASSLINETOGAME}, from source/CONTROLLOOP.s.
 *
 * The password is the whole of what the game carries between levels: how much
 * energy the player has, which guns, how much ammunition in each, and the
 * furthest level reached. It is sixteen letters because it is eight bytes shown
 * a nibble at a time, and each nibble is written as a letter from A so that
 * nothing in it can be mistyped as a digit.
 *
 * Two things stop a made-up password working. Six of the eight bytes carry a
 * parity bit in bit seven, which {@code GETPARITY} sets and {@code CHECKPARITY}
 * tests; and the byte holding the guns and the level is repeated as a checksum,
 * {@code eor #$b5 / neg / add #50}. Then the eight bytes are interleaved one bit
 * at a time with their own reverse, complemented, so a single letter changed
 * moves bits in two places at once.
 *
 * This matters here for one reason beyond completeness: {@code playgame} does
 * {@code move.w MAXLEVEL,PLOPT}, and {@code MAXLEVEL} is only ever set from a
 * password. The menu names the level but cannot choose it -- the password is the
 * choice.
 */
public final class Password {

    /** Eight bytes, sixteen letters. */
    public static final int BYTES = 8, LETTERS = 16;
    /** {@code eor.b #%10110101,d0 / neg.b d0 / add.b #50,d0}. */
    private static final int CHECK_EOR = 0xb5, CHECK_ADD = 50;

    /**
     * What a password carries.
     *
     * @param gunFlags which guns are held, as {@code GETSTATS} reads them:
     *                 bits seven to four of the second byte, for guns one,
     *                 two, four and seven
     * @param gunAmmo  the rounds in guns nought, one, two, four and seven
     */
    public record State(int energy, int level, int gunFlags, int[] gunAmmo) {
        public State {
            gunAmmo = gunAmmo.clone();
        }
    }

    private Password() {
    }

    /**
     * {@code GETPARITY}: bit seven made to even out the other seven.
     *
     * The loop runs over bits six down to nought and flips bit seven on each set
     * bit, so bit seven ends up holding the parity of the rest.
     */
    public static int parity(int d0) {
        d0 &= 0xff;
        for (int bit = 6; bit >= 0; bit--) {
            if ((d0 & (1 << bit)) != 0) {
                d0 ^= 0x80;                       // bchg #7,d0
            }
        }
        return d0;
    }

    /** {@code CHECKPARITY}: true when bit seven agrees with the other seven. */
    public static boolean parityOk(int d0) {
        int d2 = 0;
        for (int bit = 6; bit >= 0; bit--) {
            if ((d0 & (1 << bit)) != 0) {
                d2 ^= 0x80;
            }
        }
        return (d0 & 0x80) == d2;
    }

    /**
     * {@code CALCPASSWORD}: the eight bytes, before they are mixed.
     *
     * The gun nibble is written the way the original writes it, which is not
     * the way it is read. {@code CALCPASSWORD} tests the four guns with four
     * {@code sne d0} in a row, and {@code sne} sets the whole byte rather than
     * one bit, so each test wipes the one before it and the shifts between
     * them move nothing. Only the last gun survives, and the {@code lsr #3}
     * and {@code and #$f0} that follow leave it in bit four.
     * {@code GETSTATS} then reads bits seven to four as four separate guns.
     * So a password the game hands out can never restore more than gun seven,
     * whatever the player was carrying.
     */
    public static int[] buffer(State s) {
        int[] b = new int[BYTES];
        b[0] = parity(s.energy() & 0xff);
        // sne d0 four times: only the last test is left, and it lands in bit 4
        int guns = (s.gunFlags() & 1) != 0 ? 0x10 : 0;
        b[1] = guns | (s.level() & 0x0f);
        for (int i = 0; i < 5 && i < s.gunAmmo().length; i++) {
            b[2 + i] = parity((s.gunAmmo()[i] >> 3) & 0xff);
        }
        b[7] = check(b[1]);
        return b;
    }

    /** {@code eor.b #%10110101,d0 / neg.b d0 / add.b #50,d0}. */
    public static int check(int d0) {
        return (-((d0 ^ CHECK_EOR) & 0xff) + CHECK_ADD) & 0xff;
    }

    /**
     * {@code mixemup}, then {@code putinpassline}.
     *
     * Four words, each built from one byte taken forwards and one taken
     * backwards and complemented, a bit of each in turn. The first bit shifted
     * out ends highest, which is why the loop reads as reversing them.
     */
    public static String encode(State s) {
        int[] b = buffer(s);
        int[] pass = new int[BYTES];
        for (int i = 0; i < 4; i++) {
            int d1 = b[i] & 0xff;
            int d2 = ~b[7 - i] & 0xff;            // not.b d2
            int d3 = 0;
            for (int k = 0; k < 8; k++) {
                d3 = ((d3 << 1) | (d1 & 1)) & 0xffff;   // lsr/addx, alternating
                d1 >>= 1;
                d3 = ((d3 << 1) | (d2 & 1)) & 0xffff;
                d2 >>= 1;
            }
            pass[i * 2] = (d3 >> 8) & 0xff;       // move.w d3,(a2)+ is big-endian
            pass[i * 2 + 1] = d3 & 0xff;
        }
        StringBuilder out = new StringBuilder(LETTERS);
        for (int i = 0; i < BYTES; i++) {
            out.append((char) ('A' + (pass[i] & 0xf)));         // low nibble first
            out.append((char) ('A' + ((pass[i] >> 4) & 0xf)));
        }
        return out.toString();
    }

    /**
     * {@code PASSLINETOGAME}: the letters back to a state, or null.
     *
     * Every one of the six parity bytes and the checksum has to agree; the
     * original answers {@code illega} with {@code -1} and changes nothing.
     */
    public static State decode(String letters) {
        if (letters == null || letters.length() < LETTERS) {
            return null;
        }
        int[] pass = new int[BYTES];
        for (int i = 0; i < BYTES; i++) {
            int d1 = (letters.charAt(i * 2) - 'A') & 0xf;
            int d2 = (letters.charAt(i * 2 + 1) - 'A') & 0xf;
            pass[i] = (d2 << 4) | d1;
        }
        int[] b = new int[BYTES];
        for (int i = 0; i < 4; i++) {
            int d1 = (pass[i * 2] << 8) | pass[i * 2 + 1];
            int d2 = 0, d3 = 0;
            for (int k = 0; k < 8; k++) {
                d3 = ((d3 << 1) | (d1 & 1)) & 0xff;   // the complemented stream
                d1 >>= 1;
                d2 = ((d2 << 1) | (d1 & 1)) & 0xff;
                d1 >>= 1;
            }
            b[7 - i] = ~d3 & 0xff;                // not.b d3 / move.b d3,-(a2)
            b[i] = d2;
        }

        for (int i : new int[]{0, 2, 3, 4, 5, 6}) {
            if (!parityOk(b[i])) {
                return null;                      // bne illega
            }
        }
        if (check(b[1]) != b[7]) {
            return null;
        }
        int[] ammo = new int[5];
        for (int i = 0; i < 5; i++) {
            ammo[i] = (b[2 + i] & 0x7f) << 3;
        }
        // GETSTATS: btst #7 / #6 / #5 / #4 into guns 1, 2, 4 and 7
        return new State(b[0] & 0x7f, b[1] & 0x0f, (b[1] >> 4) & 0x0f, ammo);
    }
}
