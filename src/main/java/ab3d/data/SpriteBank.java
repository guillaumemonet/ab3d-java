package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code Objects} table: a graphic slot to its sprite sheet and frame list.
 *
 * The table is at {@code Objects} in source/objdraw3.chipram, eighteen entries
 * of {wad, ptr, frames, palette}. The wad and ptr of slots 12 upwards are zero
 * in the table and filled in by the loader at runtime, so the sheet names for
 * those come from that loader's file list.
 *
 * Frame tables sit in the same file as pairs of words: the byte offset of the
 * frame's first column inside the pointer table, and a vertical offset that lets
 * two sprites share one block of columns -- the pickups declare
 * {@code dc.w 0,0} for the medikit and {@code dc.w 0,32} for the big gun, the
 * same columns read thirty-two rows apart. Rather than hard-code them they are
 * parsed out of the assembly, where the offsets are written as arithmetic:
 * {@code 64*4}, {@code 64*3*4}, {@code (128+16)*4}.
 */
public final class SpriteBank {

    /** Slot to sheet name, from the Objects table and the loader's file list. */
    private static final String[] SHEETS = {
        "alien2",        // 0
        "pickups",       // 1
        "bigbullet",     // 2
        "uglymonster",   // 3  not present in the repository
        "flyingalien",   // 4
        "keys",          // 5
        "rockets",       // 6
        "barrel",        // 7
        "bigbullet",     // 8  shares the bullet sheet, explosion frames
        "newgunsinhand", // 9
        "newmarine",     // 10
        "bigscaryalien", // 11
        "lamps",         // 12
        "worm",          // 13
        "bigclaws",      // 14
        "tree",          // 15
        "newmarine",     // 16 shares the marine sheet
        "newmarine",     // 17 shares the marine sheet
    };

    /** Slot to the label of its frame table in the assembly. */
    private static final String[] FRAME_LABELS = {
        "ALIEN", "PICKUPS", "BIGBULLET", "UGLYMONSTER", "FLYINGMONSTER", "KEYS",
        "ROCKETS", "BARREL", "EXPLOSION", "GUNS", "MARINE", "BIGALIEN", "LAMPS",
        "WORM", "BIGCLAWS", "TREE", "TOUGHMARINE", "FLAMEMARINE",
    };

    /** One frame: where its columns start, and how far down the sprite sits. */
    public record Frame(int ptrOffset, int downStrip) {}

    private final SpriteSheet[] sheets = new SpriteSheet[SHEETS.length];
    private final List<List<Frame>> frames = new ArrayList<>();

    public SpriteBank(GameData game) {
        Map<String, List<Frame>> tables = parseFrameTables(game);
        for (int i = 0; i < SHEETS.length; i++) {
            try {
                sheets[i] = SpriteSheet.load(game, SHEETS[i]);
            } catch (IOException e) {
                sheets[i] = null;        // a sheet the repository does not carry
            }
            frames.add(tables.getOrDefault(FRAME_LABELS[i], List.of()));
        }
    }

    /**
     * Columns one frame of this slot occupies.
     *
     * The frame table gives it away: consecutive frames start a fixed number of
     * pointer entries apart, so the smallest positive gap between two distinct
     * offsets is the frame width. The alien's table steps by {@code 64*4} bytes,
     * the keys' by {@code 32*4}, the guns' by {@code 96*4} -- 64, 32 and 96
     * columns. The object record's texel width is a smaller number used for the
     * texture step, not the number of columns to read.
     */
    public int frameColumns(int slot, int fallback) {
        if (slot < 0 || slot >= frames.size()) {
            return fallback;
        }
        List<Frame> list = frames.get(slot);
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                int d = Math.abs(list.get(i).ptrOffset() - list.get(j).ptrOffset());
                if (d > 0) {
                    best = Math.min(best, d);
                }
            }
        }
        return best == Integer.MAX_VALUE ? fallback : best / 4;
    }

    public SpriteSheet sheet(int slot) {
        return slot >= 0 && slot < sheets.length ? sheets[slot] : null;
    }

    /** Frame {@code n} of a slot, falling back to the first when out of range. */
    public Frame frame(int slot, int n) {
        if (slot < 0 || slot >= frames.size()) {
            return new Frame(0, 0);
        }
        List<Frame> list = frames.get(slot);
        if (list.isEmpty()) {
            return new Frame(0, 0);
        }
        return list.get(n >= 0 && n < list.size() ? n : 0);
    }

    // ---- reading the frame tables out of the assembly ------------------------

    private static final Pattern LABEL = Pattern.compile("^(\\w+)_FRAMES:");
    private static final Pattern DCW = Pattern.compile(
            "^\\s*dc\\.w\\s+([0-9*+()\\s]+?)\\s*,\\s*([0-9*+()\\s]+?)\\s*$");

    private static Map<String, List<Frame>> parseFrameTables(GameData game) {
        Map<String, List<Frame>> out = new HashMap<>();
        Path src = game.root().resolve("source/objdraw3.chipram");
        if (!Files.isRegularFile(src)) {
            return out;
        }
        try {
            List<Frame> current = null;
            for (String line : Files.readAllLines(src, java.nio.charset.StandardCharsets.ISO_8859_1)) {
                Matcher label = LABEL.matcher(line.trim());
                if (label.find()) {
                    current = out.computeIfAbsent(label.group(1), k -> new ArrayList<>());
                    continue;
                }
                if (current == null) {
                    continue;
                }
                Matcher dcw = DCW.matcher(line);
                if (dcw.matches()) {
                    current.add(new Frame(value(dcw.group(1)), value(dcw.group(2))));
                } else if (!line.isBlank() && !line.trim().startsWith(";")) {
                    current = null;      // anything else ends the table
                }
            }
        } catch (IOException ignored) {
            // no frame tables simply means every object draws its first frame
        }
        return out;
    }

    /**
     * Evaluates the constants the tables are written with.
     *
     * The offsets are arithmetic rather than literals -- {@code 64*4},
     * {@code 64*3*4}, {@code (128+16)*4} -- because they are written as a frame
     * width times a frame number, which is how the tables stay readable when a
     * sheet is re-cut. Brackets bind tighter than the product, so the sum inside
     * one has to be evaluated first; splitting on the operators in turn gets
     * {@code (128+16)*4} wrong.
     */
    private static int value(String expr) {
        return new Expr(expr).sum();
    }

    /** Sums of products with brackets, which is the whole grammar in use. */
    private static final class Expr {
        private final String text;
        private int at;

        Expr(String text) {
            this.text = text;
        }

        int sum() {
            int total = product();
            while (peek() == '+') {
                at++;
                total += product();
            }
            return total;
        }

        private int product() {
            int total = factor();
            while (peek() == '*') {
                at++;
                total *= factor();
            }
            return total;
        }

        private int factor() {
            if (peek() == '(') {
                at++;
                int inner = sum();
                if (peek() == ')') {
                    at++;
                }
                return inner;
            }
            int start = at;
            while (at < text.length() && Character.isDigit(text.charAt(at))) {
                at++;
            }
            return start == at ? 0 : Integer.parseInt(text.substring(start, at));
        }

        private char peek() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            return at < text.length() ? text.charAt(at) : ' ';
        }
    }
}
