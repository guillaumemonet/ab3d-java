package ab3d.tools;

import ab3d.data.GameData;

/**
 * The levels a check runs over when it is given none: every one on the disks.
 *
 * Most checks here were written when only the first four levels were looked
 * at, and carried that list in their own text. Conclusions drawn from it did
 * not all hold for the sixteen -- six enemy types "never placed" were placed
 * in the twelve that were not looked at -- so the default is now the lot.
 */
public final class Levels {

    private Levels() {
    }

    public static String[] all() {
        java.util.List<String> l = GameData.fromSystemProperty().diskLevels();
        if (!l.isEmpty()) {
            return l.toArray(new String[0]);
        }
        String[] out = new String[16];
        for (int i = 0; i < 16; i++) {
            out[i] = "level_" + (char) ('a' + i);
        }
        return out;
    }
}
