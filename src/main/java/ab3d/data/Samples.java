package ab3d.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sound effects, from {@code SFX_NAMES} in source/loadfromdisk.s and the
 * files on the second floppy.
 *
 * {@code LOAD_SFX} walks a table of name and length pairs and loads each one
 * into {@code SampleList}, so the position in that table is what every
 * {@code move.w #n,Samplenum} in the game means. Reading the table rather than
 * numbering the files by hand matters because two entries are commented out --
 * and a reader that counted the files would put everything after them one slot
 * wrong, which would give the wrong sound for every event past the tenth.
 *
 * The lengths in the table are a second opinion on the same thing: every file on
 * the disk is exactly as long as the table says, except the teleport, which is a
 * thousand bytes longer than its entry claims.
 *
 * They are raw signed eight-bit mono. jg.s sets the audio period to four hundred
 * and forty-three, and the Amiga's clock divided by that is a little over eight
 * thousand samples a second.
 */
public final class Samples {

    /** {@code move.w #443,$dff0a6}: the period every channel is set to. */
    public static final int PERIOD = 443;
    /** The PAL clock, so the rate is this over the period. */
    public static final int PAL_CLOCK = 3546895;
    public static final float RATE = (float) PAL_CLOCK / PERIOD;

    /** One entry: what the table calls it, how long it says, and the bytes. */
    public record Sample(String name, int statedLength, byte[] data) {}

    private final List<Sample> samples = new ArrayList<>();

    /**
     * The sample table, from the assembly or from the saved copy.
     *
     * Only the table is saved: which file each number is and how long it says.
     * The sound itself is on the second floppy either way, so this still needs
     * the disks even when the source is gone.
     */
    public static Samples load(GameData game) throws IOException {
        Path src = game.root().resolve("source/loadfromdisk.s");
        if (Files.isRegularFile(src)) {
            return new Samples(Files.readString(src, StandardCharsets.ISO_8859_1),
                               game.disk());
        }
        return new Samples(ab3d.gen.Tables.SAMPLE_NAMES,
                           ab3d.gen.Tables.SAMPLE_LENGTHS, game.disk());
    }

    private Samples(String[] names, int[] lengths, Path disk) throws IOException {
        Path dir = disk.resolve("disk2/sounds");
        for (int i = 0; i < names.length; i++) {
            Path file = dir.resolve(names[i]);
            byte[] data = Files.isRegularFile(file) ? Files.readAllBytes(file)
                                                    : new byte[0];
            samples.add(new Sample(names[i], lengths[i], data));
        }
    }

    Samples(String src, Path disk) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        Path dir = disk.resolve("disk2/sounds");
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                s.forEach(p -> files.put(
                        p.getFileName().toString().toLowerCase(),
                        p.toString()));
            }
        }

        // the label each entry names, and then what that label's string is
        Map<String, String> paths = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(\\w+):\\s*dc\\.b\\s*'([^']*)'").matcher(src);
        while (m.find()) {
            paths.put(m.group(1), m.group(2));
        }

        int at = src.indexOf("SFX_NAMES:");
        if (at < 0) {
            return;
        }
        for (String line : src.substring(at).split("\r?\n")) {
            Matcher e = Pattern.compile("^\\s*dc\\.l\\s+(\\w+)\\s*,\\s*(\\d+)")
                               .matcher(line);
            if (!e.find()) {
                if (line.matches("^\\s*dc\\.l\\s+-1\\s*$")) {
                    break;                          // the table ends here
                }
                continue;                           // a comment, or one struck out
            }
            String label = e.group(1);
            int length = Integer.parseInt(e.group(2));
            String path = paths.get(label);
            String file = path == null ? null
                    : path.substring(path.lastIndexOf('/') + 1).toLowerCase();
            String on = file == null ? null : files.get(file);
            samples.add(new Sample(file == null ? label : file, length,
                                   on == null ? new byte[0]
                                              : Files.readAllBytes(Path.of(on))));
        }
    }

    public int count() {
        return samples.size();
    }

    public Sample get(int n) {
        return n >= 0 && n < samples.size() ? samples.get(n) : null;
    }

    /** Whether a slot has any sound in it at all. */
    public boolean has(int n) {
        Sample s = get(n);
        return s != null && s.data().length > 0;
    }
}
