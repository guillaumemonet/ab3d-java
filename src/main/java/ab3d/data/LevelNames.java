package ab3d.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code LEVEL_OPTS}: the sixteen level lines, from source/CONTROLLOOP.s.
 *
 * {@code READMAINMENU} copies one of them over {@code CURRENTLEVELLINE} with
 * {@code PUTINLINE}, indexed by {@code MAXLEVEL}, so they are already forty
 * characters wide and already centred -- nothing has to be laid out here.
 *
 * {@code SETPLAYERS} does {@code move.w PLOPT,d0 / add.b #'a',d0} to make the
 * file name, so the row number and the letter in {@code level_a} are the same
 * thing.
 */
public final class LevelNames {

    public static final int COUNT = 16;

    private final List<String> rows = new ArrayList<>();

    public static LevelNames load(GameData game) throws IOException {
        return new LevelNames(Files.readString(
                game.root().resolve("source/CONTROLLOOP.s"),
                StandardCharsets.ISO_8859_1));
    }

    LevelNames(String src) {
        Matcher block = Pattern.compile("LEVEL_OPTS:(.*?)\\n\\s*\\n",
                                        Pattern.DOTALL).matcher(src);
        if (!block.find()) {
            return;
        }
        Matcher row = Pattern.compile("dc\\.b\\s+'(.*)'").matcher(block.group(1));
        while (row.find() && rows.size() < COUNT) {
            rows.add(row.group(1));
        }
    }

    /** The line for a level, counting from nought as {@code MAXLEVEL} does. */
    public String line(int level) {
        return level >= 0 && level < rows.size() ? rows.get(level) : "";
    }

    /** {@code add.b #'a',d0}: the file that level lives in. */
    public static String fileName(int level) {
        return "level_" + (char) ('a' + level);
    }

    /** How many of the sixteen the source names. */
    public int count() {
        return rows.size();
    }
}
