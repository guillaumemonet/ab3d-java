package ab3d.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code GunAnims} and the frame lists it points at, from source/jg.s.
 *
 * The table is a pointer and a count per weapon, and {@code Player1Shot} reads
 * the count with {@code move.b 7(a0,d0.w*8)} -- byte seven of an eight-byte
 * entry, which is the low byte of the second longword. That count is what
 * {@code PLR1_GunFrame} is set to when a shot goes, and it counts down to
 * nothing; the list is indexed by it directly.
 *
 * For every weapon the count is one less than its list is long, so the animation
 * runs from its last step back to its first. The shotgun's is the one worth
 * reading in the source rather than guessing: {@code dc.w 0} followed by four
 * {@code dcb.w} runs and a final step, sixty-four entries whose count is written
 * as {@code 12+19+11+20+1}.
 */
public final class GunAnims {

    /** The order of {@code GunAnims}, which is the order of the gun records. */
    private static final String[] LISTS = {
        "MachineAnim", "PlasmaAnim", "RocketAnim", "FlameThrowerAnim",
        "GrenadeAnim", null, null, "ShotGunAnim",
    };

    private final int[][] frames = new int[LISTS.length][];

    public static GunAnims load(GameData game) throws IOException {
        return new GunAnims(Files.readString(game.root().resolve("source/jg.s"),
                                             StandardCharsets.ISO_8859_1));
    }

    GunAnims(String src) {
        for (int i = 0; i < LISTS.length; i++) {
            frames[i] = LISTS[i] == null ? new int[0] : read(src, LISTS[i]);
        }
    }

    /**
     * One list, from its label to the next one.
     *
     * {@code dcb.w n,v} is a run of n copies of v, and the shotgun's list is
     * almost entirely made of them.
     */
    private static int[] read(String src, String label) {
        int at = src.indexOf(label + ":");
        if (at < 0) {
            return new int[0];
        }
        List<Integer> out = new ArrayList<>();
        String[] lines = src.substring(at).split("\r?\n");
        Pattern dcb = Pattern.compile("dcb\\.w\\s+(\\d+)\\s*,\\s*(-?\\d+)");
        Pattern dcw = Pattern.compile("dc\\.w\\s+([-\\d,\\s]+)");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // the next list's label ends this one, and it may carry data of its
            // own on the same line -- GrenadeAnim does
            if (i > 0 && line.matches("^[A-Za-z_][A-Za-z0-9_]*:.*")) {
                break;
            }
            Matcher r = dcb.matcher(line);
            if (r.find()) {
                int n = Integer.parseInt(r.group(1));
                int v = Integer.parseInt(r.group(2));
                for (int k = 0; k < n; k++) {
                    out.add(v);
                }
                continue;
            }
            Matcher m = dcw.matcher(line);
            if (m.find()) {
                for (String t : m.group(1).split(",")) {
                    t = t.trim();
                    if (!t.isEmpty()) {
                        out.add(Integer.parseInt(t));
                    }
                }
                continue;
            }
            if (i > 0 && !line.isBlank() && !line.trim().startsWith(";")) {
                break;                              // anything else ends it
            }
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = out.get(i);
        }
        return a;
    }

    /** The frames of one weapon's animation, first step first. */
    public int[] frames(int gun) {
        return gun >= 0 && gun < frames.length ? frames[gun] : new int[0];
    }

    /**
     * {@code move.b 7(a0,d0.w*8),MaxFrame}: what a shot sets the counter to.
     *
     * Taken as the list's length less one rather than from the table's own
     * number, because the two agree for all six real weapons and this way the
     * counter cannot walk off the end of the list it indexes.
     */
    public int maxFrame(int gun) {
        return Math.max(0, frames(gun).length - 1);
    }
}
