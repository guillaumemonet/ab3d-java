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
 * {@code CONTROLBUFFER} and {@code KVALTOASC}, from source/CONTROLLOOP.s.
 *
 * Twelve actions, each held as one Amiga raw key code, which is what the machine
 * hands the keyboard interrupt and what {@code KeyMap} is indexed by:
 * {@code move.b operate_key,d7 / tst.b (a5,d7.w)}. {@code CHANGECONTROLS} writes
 * a new code straight into the buffer and shows its name from
 * {@code KVALTOASC}, a table of four-character labels in raw-key order.
 *
 * Both are read from the source rather than copied here, which is worth doing
 * for one reason beyond tidiness: the defaults appear twice in the original,
 * once as the bytes of {@code CONTROLBUFFER} and once as the labels printed on
 * {@code CONTROL_TXT}, and reading the bytes means the screen and the bindings
 * cannot drift apart.
 */
public final class Controls {

    /** The rows of {@code CONTROL_TXT}, in the order {@code CONTROLBUFFER} holds. */
    public enum Action {
        TURN_LEFT("TURN LEFT"), TURN_RIGHT("TURN RIGHT"),
        FORWARD("FORWARDS"), BACKWARD("BACKWARDS"),
        FIRE("FIRE"), OPERATE("OPERATE DOOR/LIFT/SWITCH"),
        RUN("RUN"), FORCE_SIDESTEP("FORCE SIDESTEP"),
        SIDESTEP_LEFT("SIDESTEP LEFT"), SIDESTEP_RIGHT("SIDESTEP RIGHT"),
        DUCK("DUCK"), LOOK_BEHIND("LOOK BEHIND");

        public final String label;

        Action(String label) {
            this.label = label;
        }
    }

    /** {@code KEY_LINES}: the first rebindable row of {@code CONTROL_TXT}. */
    public static final int FIRST_ROW = 6;
    /** {@code add.w #32,a0}: the column the key's name is printed at. */
    public static final int KEY_COLUMN = 32;
    /** {@code cmp.w #12,d0 / beq .backtomain}. */
    public static final int MAIN_MENU_OPTION = Action.values().length;

    /** One raw key code per action, as {@code CONTROLBUFFER} holds them. */
    private final int[] keys = new int[Action.values().length];
    /** {@code KVALTOASC}: a four-character name per raw key code. */
    private final String[] names;

    public static Controls load(GameData game) {
        Path src = game.root().resolve("source/CONTROLLOOP.s");
        if (Files.isRegularFile(src)) {
            try {
                return fromSource(game);
            } catch (IOException ignored) {
                // an unreadable source simply means using the saved table
            }
        }
        return new Controls(ab3d.gen.Tables.CONTROL_KEYS,
                            ab3d.gen.Tables.KEY_NAMES);
    }

    private Controls(int[] saved, String[] savedNames) {
        System.arraycopy(saved, 0, keys, 0, keys.length);
        names = savedNames.clone();
    }

    private static Controls fromSource(GameData game) throws IOException {
        return new Controls(Files.readString(
                game.root().resolve("source/CONTROLLOOP.s"),
                StandardCharsets.ISO_8859_1));
    }

    Controls(String src) {
        this.names = readNames(src);
        readDefaults(src);
    }

    /**
     * {@code KVALTOASC}: four characters per raw key, in code order.
     *
     * The table is written with both kinds of quote -- double around the ones
     * holding a backslash or an apostrophe's neighbours, single around the
     * blanks -- so both are taken, and each string is exactly four characters
     * whatever is in it.
     */
    private static String[] readNames(String src) {
        int at = src.indexOf("KVALTOASC:");
        if (at < 0) {
            return new String[0];
        }
        int end = src.indexOf("\n even", at);
        String block = src.substring(at, end < 0 ? src.length() : end);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[\"']((?:[^\"'\\n]){4})[\"']").matcher(block);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out.toArray(new String[0]);
    }

    /** The twelve {@code dc.b $xx} of {@code CONTROLBUFFER}. */
    private void readDefaults(String src) {
        int at = src.indexOf("CONTROLBUFFER:");
        if (at < 0) {
            return;
        }
        Matcher m = Pattern.compile("dc\\.b\\s+\\$([0-9a-fA-F]+)")
                           .matcher(src.substring(at));
        for (int i = 0; i < keys.length && m.find(); i++) {
            keys[i] = Integer.parseInt(m.group(1), 16);
        }
    }

    public int key(Action a) {
        return keys[a.ordinal()];
    }

    /** {@code move.b d1,(a1,d0.w)}: the new code, straight into the buffer. */
    public void bind(Action a, int rawKey) {
        keys[a.ordinal()] = rawKey;
    }

    /** {@code move.l (a1,d1.w*4),(a0)}: what to print for a raw key. */
    public String name(int rawKey) {
        return rawKey >= 0 && rawKey < names.length ? names[rawKey] : "    ";
    }

    /** Which action a raw key drives, or null. */
    public Action actionFor(int rawKey) {
        for (Action a : Action.values()) {
            if (keys[a.ordinal()] == rawKey) {
                return a;
            }
        }
        return null;
    }

    /** How many names the table holds, for checking it was read whole. */
    public int nameCount() {
        return names.length;
    }
}
