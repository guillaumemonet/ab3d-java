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
 * {@code ENDZONES}, from source/jg.s: the room that finishes each level.
 *
 * The whole of the win condition is one comparison in the main loop --
 * {@code move.l PLR1_Roompt,a0 / move.w (a0),d0 / cmp.w (a0,d1.w*2),d0 / beq
 * end} -- so a level is over when the player walks into one particular room of
 * it. There is no exit switch and no boss: one zone number a level, sixteen of
 * them, written out with a comment above each.
 *
 * Losing is the same test the other way round. {@code tst.w PLR1_energy / ble
 * end} sends the game to the same place, and what decides between the two is a
 * single {@code tst.w Energy / bgt wevewon} once it gets there.
 */
public final class EndZones {

    private static final Pattern ROW =
            Pattern.compile("^\\s*dc\\.w\\s+(-?\\d+)\\s*$");

    private int[] zones;

    /**
     * Reads the table from the assembly, or takes the saved answer.
     *
     * The generated table is what this very parser produced, so the two cannot
     * disagree; reading the source is kept because it is what the checks compare
     * against, and because a changed source should still win.
     */
    public static EndZones load(GameData game) {
        Path src = game.root().resolve("source/jg.s");
        if (Files.isRegularFile(src)) {
            try {
                return new EndZones(Files.readString(src,
                                                     StandardCharsets.ISO_8859_1));
            } catch (IOException ignored) {
                // an unreadable source simply means using the saved table
            }
        }
        return new EndZones(ab3d.gen.Tables.END_ZONES);
    }

    private EndZones(int[] saved) {
        zones = saved.clone();
    }

    EndZones(String src) {
        int at = src.indexOf("ENDZONES:");
        List<Integer> out = new ArrayList<>();
        if (at >= 0) {
            for (String line : src.substring(at).split("\r?\n")) {
                Matcher m = ROW.matcher(line);
                if (m.find()) {
                    out.add(Integer.parseInt(m.group(1)));
                    continue;
                }
                if (!line.isBlank() && !line.trim().startsWith(";")
                        && !line.startsWith("ENDZONES")) {
                    break;
                }
            }
        }
        zones = new int[out.size()];
        for (int i = 0; i < zones.length; i++) {
            zones[i] = out.get(i);
        }
    }

    /** Which room ends the level, counting from nought as {@code MAXLEVEL} does. */
    public int of(int level) {
        return level >= 0 && level < zones.length ? zones[level] : -1;
    }

    public int count() {
        return zones.length;
    }
}
