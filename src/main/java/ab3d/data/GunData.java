package ab3d.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code PLR1_GunData}, from source/jg.s: eight guns of thirty-two bytes.
 *
 * jg.s writes the layout out above the table:
 *
 * <pre>
 *   0 (w)  ammo left
 *   2 (b)  ammo per shot
 *   3 (b)  gun noise
 *   4 (b)  ammo in a clip -- what a pickup adds, shifted up three
 *   5 (b)  visible or instant: nought for a bullet that flies, $ff for a hit
 *   6 (b)  damage
 *   7 (b)  got gun
 *   8 (w)  delay between shots
 *  10 (w)  how long a bullet lives, or -1
 *  12 (w)  click or hold down
 *  14 (w)  bullet speed
 *  22 (w)  which bullet it fires
 * </pre>
 *
 * Only five of the eight are reachable. {@code pickweap} in PLR1CONTROL.s reads
 * the number keys one to five and puts them through {@code GUNVALS}, which is
 * {@code 0, 7, 1, 4, 2} -- so the pulse rifle, shotgun, plasma gun, grenade
 * launcher and rocket launcher are slots nought, seven, one, four and two, and
 * slots three, five and six are never selected. That is why two entries of
 * {@code GunAnims} are {@code dc.l 0,0}.
 *
 * Reading the table out of the assembly keeps the one copy, and the one
 * expression in it -- {@code 90*8} for the flame thrower's ammunition -- is
 * worked out rather than rounded to whatever it looked like.
 */
public final class GunData {

    public static final int GUNS = 8, RECORD = 32;

    /** Offsets jg.s names above the table. */
    public static final int AMMO = 0, PER_SHOT = 2, NOISE = 3, CLIP = 4;
    public static final int INSTANT = 5, DAMAGE = 6, GOT_GUN = 7;
    public static final int DELAY = 8, LIFETIME = 10, HOLD = 12, SPEED = 14;
    public static final int BULLET = 22;
    /**
     * The three words the bullet takes from the gun that fired it.
     *
     * jg.s does not name them above the table -- they read as {@code dc.w 0,0,0}
     * for every gun but one -- and what they are only shows in
     * {@code PLR1FIREBULLET}, which copies sixteen to {@code shotgrav} and
     * eighteen to {@code shotflags} and adds twenty to the aim. The grenade
     * launcher is the gun that uses them: sixty, three, and minus a thousand,
     * which is a shot lobbed upward that falls and bounces.
     */
    public static final int SHOT_GRAV = 16, SHOT_FLAGS = 18, SHOT_RISE = 20;

    /** {@code GUNVALS}: which slot each of the number keys one to five picks. */
    public static final int[] GUNVALS = {0, 7, 1, 4, 2};
    /** {@code cmp.w #80*8,(a6)}: an ammunition clip will not go past this. */
    public static final int AMMO_MAX = 80 * 8;
    /** {@code AmmoInGuns}: what picking a gun up gives it. */
    public static final int[] AMMO_IN_GUNS = {0, 5, 1, 0, 1, 0, 0, 5};

    private final byte[] data = new byte[GUNS * RECORD];

    public static GunData load(GameData game) {
        Path src = game.root().resolve("source/jg.s");
        if (Files.isRegularFile(src)) {
            try {
                return new GunData(Files.readString(src,
                                                    StandardCharsets.ISO_8859_1));
            } catch (IOException ignored) {
                // an unreadable source simply means using the saved table
            }
        }
        return new GunData(ab3d.gen.Tables.GUN_DATA);
    }

    private GunData(int[] saved) {
        for (int i = 0; i < data.length && i < saved.length; i++) {
            data[i] = (byte) saved[i];
        }
    }

    GunData(String src) {
        int at = src.indexOf("PLR1_GunData:");
        int end = src.indexOf("PLR2_GunData:");
        if (at < 0 || end < 0) {
            return;
        }
        int put = 0;
        for (String line : src.substring(at, end).split("\r?\n")) {
            int semi = line.indexOf(';');
            if (semi >= 0) {
                line = line.substring(0, semi);
            }
            Matcher m = Pattern.compile("\\b(dc|ds)\\.([bwl])\\s+(.*)").matcher(line);
            if (!m.find()) {
                continue;
            }
            int width = switch (m.group(2)) {
                case "b" -> 1;
                case "l" -> 4;
                default -> 2;
            };
            if (width > 1 && (put & 1) != 0) {
                // the 68000 wants a word on an even address, so the assembler
                // pads here -- and two of the eight records have an odd number
                // of bytes before their first dc.w, which without this shifts
                // every field after them
                put++;
            }
            for (int v : values(m.group(3))) {
                if (m.group(1).equals("ds")) {
                    put += v * width;               // ds reserves, it does not fill
                    continue;
                }
                for (int b = width - 1; b >= 0; b--) {
                    if (put < data.length) {
                        data[put++] = (byte) (v >> (b * 8));
                    } else {
                        put++;
                    }
                }
            }
        }
    }

    /** A comma-separated list, each term possibly a small product. */
    private static List<Integer> values(String s) {
        List<Integer> out = new ArrayList<>();
        for (String term : s.split(",")) {
            term = term.trim();
            if (term.isEmpty()) {
                continue;
            }
            int total = 1;
            boolean ok = true;
            for (String factor : term.split("\\*")) {
                Integer v = number(factor.trim());
                if (v == null) {
                    ok = false;
                    break;
                }
                total *= v;
            }
            if (ok) {
                out.add(total);
            }
        }
        return out;
    }

    private static Integer number(String s) {
        try {
            if (s.startsWith("$")) {
                return (int) Long.parseLong(s.substring(1), 16);
            }
            if (s.startsWith("-$")) {
                return -(int) Long.parseLong(s.substring(2), 16);
            }
            if (s.startsWith("%")) {
                return Integer.parseInt(s.substring(1), 2);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int word(int gun, int offset) {
        int at = gun * RECORD + offset;
        return (short) (((data[at] & 0xff) << 8) | (data[at + 1] & 0xff));
    }

    public void setWord(int gun, int offset, int value) {
        int at = gun * RECORD + offset;
        data[at] = (byte) (value >> 8);
        data[at + 1] = (byte) value;
    }

    public int unsigned(int gun, int offset) {
        return word(gun, offset) & 0xffff;
    }

    public int byteAt(int gun, int offset) {
        return data[gun * RECORD + offset] & 0xff;
    }

    public void setByte(int gun, int offset, int value) {
        data[gun * RECORD + offset] = (byte) value;
    }

    /**
     * {@code DEFAULTGAME}: the state a new game starts from.
     *
     * The table as assembled is not it. A fresh game gives the pistol a hundred
     * and sixty rounds and its {@code gotgun}, then clears both fields on every
     * other gun it can reach -- including the flame thrower, whose ninety clips
     * in the table are wiped before anyone sees them. So the assembled values
     * are defaults for the fields the routine does not touch, and the ammunition
     * and ownership come from here.
     */
    public void defaultGame() {
        setWord(0, AMMO, 160);                     // move.w #160,PLR1_GunData
        setByte(0, GOT_GUN, 0xff);                 // st PLR1_GunData+7
        for (int slot : new int[]{1, 2, 3, 4, 7}) {
            setByte(slot, GOT_GUN, 0);
            setWord(slot, AMMO, 0);
        }
    }

    /** {@code tst.b 7(a3,d2.w)}: whether the player has this one at all. */
    public boolean has(int gun) {
        return byteAt(gun, GOT_GUN) != 0;
    }

    /** {@code move.w (a6),d0 / asr.w #3,d0}: what the ammunition bar shows. */
    public int shownAmmo(int gun) {
        return unsigned(gun, AMMO) >> 3;
    }
}
