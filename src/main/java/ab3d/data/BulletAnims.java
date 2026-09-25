package ab3d.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a shot looks like in flight and what it looks like bursting, from the
 * tables at the end of source/anims.
 *
 * Three tables, all indexed by {@code shotsize} -- which is the gun that fired
 * it, not a size:
 *
 * <ul>
 *   <li>{@code BulletTypes}, a flight list and a burst list per shot
 *   <li>{@code BulletSizes}, two words: how big it is flying and bursting
 *   <li>{@code ExplosiveForce}, nought for everything but the rocket at
 *       sixty-four and the grenade at forty -- so only those two blast
 * </ul>
 *
 * Each step of a list is eight bytes: a sprite size, the graphic slot and frame
 * as two words, and a height to add. A burst list ends on a step whose first
 * word is {@code -1}, which is what sends the record back to the pool. The two
 * are written differently in the source -- a flight step's size is
 * {@code dc.w 20*256+15} and a burst step's is {@code dc.b 25,25} -- and mean
 * the same thing.
 */
public final class BulletAnims {

    /** One step: {@code move.w d2,6(a0) / move.l 2(a2,..),8(a0) / add.w 6(..)}. */
    public record Step(int size, int slot, int frame, int rise) {}

    public static final int SLOTS = 32;

    private final Step[][] flight = new Step[SLOTS][];
    private final Step[][] burst = new Step[SLOTS][];
    private final int[] flyingSize = new int[SLOTS];
    private final int[] burstSize = new int[SLOTS];
    private final int[] force = new int[SLOTS];

    public static BulletAnims load(GameData game) throws IOException {
        return new BulletAnims(Files.readString(
                game.root().resolve("source/anims"), StandardCharsets.ISO_8859_1));
    }

    BulletAnims(String src) {
        List<int[]> sizes = words(src, "BulletSizes:", SLOTS * 2);
        for (int i = 0; i < SLOTS; i++) {
            flyingSize[i] = at(sizes, i * 2);
            burstSize[i] = at(sizes, i * 2 + 1);
        }
        List<int[]> forces = words(src, "ExplosiveForce:", SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            force[i] = at(forces, i);
        }

        String[] names = longs(src, "BulletTypes:", SLOTS * 2);
        for (int i = 0; i < SLOTS; i++) {
            flight[i] = steps(src, i * 2 < names.length ? names[i * 2] : null);
            burst[i] = steps(src, i * 2 + 1 < names.length ? names[i * 2 + 1] : null);
        }
    }

    private static int at(List<int[]> flat, int i) {
        return i < flat.size() ? flat.get(i)[0] : 0;
    }

    /** A run of {@code dc.w} values from a label, flattened. */
    private static List<int[]> words(String src, String label, int want) {
        List<int[]> out = new ArrayList<>();
        int start = src.indexOf(label);
        if (start < 0) {
            return out;
        }
        for (String line : src.substring(start).split("\r?\n")) {
            if (out.size() >= want) {
                break;
            }
            Matcher m = Pattern.compile("dc\\.w\\s+([-\\$0-9a-fA-F,\\s]+)")
                               .matcher(line);
            if (!m.find()) {
                if (!line.isBlank() && !line.trim().startsWith(";")
                        && !line.startsWith(label)) {
                    break;
                }
                continue;
            }
            for (String t : m.group(1).split(",")) {
                t = t.trim();
                if (t.isEmpty()) {
                    continue;
                }
                out.add(new int[]{value(t)});
            }
        }
        return out;
    }

    /** The label names in a run of {@code dc.l a,b}. */
    private static String[] longs(String src, String label, int want) {
        List<String> out = new ArrayList<>();
        int start = src.indexOf(label);
        if (start < 0) {
            return new String[0];
        }
        for (String line : src.substring(start).split("\r?\n")) {
            if (out.size() >= want) {
                break;
            }
            Matcher m = Pattern.compile("dc\\.l\\s+([A-Za-z0-9_,\\s]+)")
                               .matcher(line);
            if (!m.find()) {
                if (!line.isBlank() && !line.trim().startsWith(";")
                        && !line.startsWith(label)) {
                    break;
                }
                continue;
            }
            for (String t : m.group(1).split(",")) {
                t = t.trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * One animation list: eight bytes a step, ending on a first word of -1.
     *
     * A step's size is written either as one word or as two bytes, and the slot
     * and frame as a pair of words. Reading the values in order and taking them
     * four at a time handles both, because {@code dc.b 25,25} and
     * {@code dc.w 25*256+25} put the same sixteen bits in the same place.
     */
    private static Step[] steps(String src, String name) {
        if (name == null || name.equals("0")) {
            return new Step[0];
        }
        int start = src.indexOf("\n" + name);
        if (start < 0) {
            return new Step[0];
        }

        // The assembler variables in scope. Some lists grow their own size step
        // by step -- RockPop starts at a hundred and adds ten a frame, which is
        // the explosion expanding -- and a reader that stops at the SET line
        // gets an empty list rather than a wrong one, which is at least loud.
        java.util.Map<String, Integer> vars = new java.util.HashMap<>();
        Matcher pre = Pattern.compile("^(\\w+)\\s+SET\\s+(\\d+)\\s*$",
                                      Pattern.MULTILINE)
                             .matcher(src.substring(0, start));
        while (pre.find()) {
            vars.put(pre.group(1), Integer.parseInt(pre.group(2)));
        }

        List<Integer> flat = new ArrayList<>();
        String[] lines = src.substring(start + 1).split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            Matcher set = Pattern.compile("^(\\w+)\\s+SET\\s+(.*?)\\s*$")
                                 .matcher(line);
            if (set.find()) {
                vars.put(set.group(1), value(set.group(2), vars));
                continue;                           // val SET val+10
            }
            if (i > 0 && line.matches("^[A-Za-z_][A-Za-z0-9_]*[: ].*")
                    && !line.trim().startsWith("dc.")) {
                break;                              // the next list's label
            }

            Matcher b = Pattern.compile("dc\\.b\\s+([\\w$]+)\\s*,\\s*([\\w$]+)")
                               .matcher(line);
            if (b.find()) {
                flat.add(((value(b.group(1), vars) & 0xff) << 8)
                         | (value(b.group(2), vars) & 0xff));
                continue;
            }
            Matcher m = Pattern.compile("dc\\.w\\s+([-*+\\w$,\\s]+)").matcher(line);
            if (m.find()) {
                for (String t : m.group(1).split(",")) {
                    t = t.trim();
                    if (!t.isEmpty()) {
                        flat.add(value(t, vars));
                    }
                }
                continue;
            }
            if (i > 0 && !line.isBlank() && !line.trim().startsWith(";")) {
                break;
            }
        }

        List<Step> out = new ArrayList<>();
        for (int i = 0; i + 3 < flat.size(); i += 4) {
            if (flat.get(i) == -1) {
                break;                              // cmp.w #-1,d2
            }
            out.add(new Step(flat.get(i), flat.get(i + 1), flat.get(i + 2),
                             flat.get(i + 3)));
        }
        return out.toArray(new Step[0]);
    }

    private static int value(String s) {
        return value(s, java.util.Map.of());
    }

    /** A term: a sum of products of numbers, hex literals and set variables. */
    private static int value(String s, java.util.Map<String, Integer> vars) {
        int total = 0;
        for (String term : s.split("\\+")) {
            int prod = 1;
            for (String f : term.trim().split("\\*")) {
                f = f.trim();
                Integer v = vars.get(f);
                if (v != null) {
                    prod *= v;
                    continue;
                }
                try {
                    prod *= f.startsWith("$") ? Integer.parseInt(f.substring(1), 16)
                                              : Integer.parseInt(f);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            total += prod;
        }
        return (short) total;
    }

    public Step[] flight(int shot) {
        return shot >= 0 && shot < SLOTS ? flight[shot] : new Step[0];
    }

    public Step[] burst(int shot) {
        return shot >= 0 && shot < SLOTS ? burst[shot] : new Step[0];
    }

    public int flyingSize(int shot) {
        return shot >= 0 && shot < SLOTS ? flyingSize[shot] : 0;
    }

    public int burstSize(int shot) {
        return shot >= 0 && shot < SLOTS ? burstSize[shot] : 0;
    }

    /** {@code ExplosiveForce}: nought means it does not blast. */
    public int force(int shot) {
        return shot >= 0 && shot < SLOTS ? force[shot] : 0;
    }
}
