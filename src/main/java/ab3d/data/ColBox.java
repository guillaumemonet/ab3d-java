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
 * {@code ColBoxTable}, from source/ObjectMove: how big each kind of thing is.
 *
 * Four words each, indexed by the type byte at sixteen of an object record, and
 * the table is commented type by type -- red scurrying alien, medipack, bullet,
 * gun, key, player one, robot, and so on -- which is what confirms the dispatch
 * numbers from a second place.
 *
 * Only the first word is used here. {@code CalcPLR1InLine} compares it against
 * the sideways distance from the player's line of sight, so it is the half-width
 * the auto-aim has to be within.
 */
public final class ColBox {

    public static final int WORDS = 4;

    private int[][] rows;

    public static ColBox load(GameData game) {
        Path src = game.root().resolve("source/ObjectMove");
        if (Files.isRegularFile(src)) {
            try {
                return new ColBox(Files.readString(src,
                                                   StandardCharsets.ISO_8859_1));
            } catch (IOException ignored) {
                // an unreadable source simply means using the saved table
            }
        }
        return new ColBox(ab3d.gen.Tables.COL_WIDTH, ab3d.gen.Tables.COL_HEIGHT);
    }

    private ColBox(int[] width, int[] height) {
        rows = new int[width.length][];
        for (int i = 0; i < width.length; i++) {
            rows[i] = new int[]{width[i], height[i], 0, 0};
        }
    }

    ColBox(String src) {
        int at = src.indexOf("ColBoxTable:");
        List<int[]> out = new ArrayList<>();
        if (at >= 0) {
            Pattern p = Pattern.compile("dc\\.w\\s+(-?\\d+)\\s*,\\s*(-?\\d+)"
                                        + "\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)");
            Matcher m = p.matcher(src.substring(at));
            while (m.find()) {
                out.add(new int[]{Integer.parseInt(m.group(1)),
                                  Integer.parseInt(m.group(2)),
                                  Integer.parseInt(m.group(3)),
                                  Integer.parseInt(m.group(4))});
                if (m.end() > 2000) {
                    break;                      // the table, not what follows it
                }
            }
        }
        rows = out.toArray(new int[0][]);
    }

    /** {@code cmp.w (a6),d2}: how far off the line of sight still counts. */
    public int width(int type) {
        return type >= 0 && type < rows.length ? rows[type][0] : 0;
    }

    /** {@code cmp.w 2(a6),d2}: how far above or below still counts as a hit. */
    public int height(int type) {
        return type >= 0 && type < rows.length ? rows[type][1] : 0;
    }

    public int count() {
        return rows.length;
    }
}
