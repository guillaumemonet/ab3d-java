package ab3d.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    private final int[] zones;

    public static EndZones load(GameData game) throws IOException {
        return new EndZones(Files.readString(game.root().resolve("source/jg.s"),
                                             StandardCharsets.ISO_8859_1));
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
