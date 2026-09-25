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
 * The option screens, read out of source/CONTROLLOOP.s.
 *
 * They are not a data file. Each screen is a block of thirty-two
 * {@code dc.b '...'} lines of forty characters -- laid out in the source with a
 * ruler comment above them so the author could count columns -- followed by a
 * list of {@code dc.w left,top,width} entries naming the lines that can be
 * chosen, ending on a {@code -1}. {@code MENUDATA} pairs the two up and
 * {@code OptScrn} indexes it.
 *
 * Reading the assembly at runtime rather than copying the text here keeps the
 * one copy: the level names, the credits and the key list are all in those
 * blocks, and a transcription of them would be a second thing to keep right.
 */
public final class MenuData {

    public static final int COLUMNS = 40, ROWS = 32;

    /** {@code dc.w left,top,width}: one line that can be chosen. */
    public record Option(int left, int top, int width) {}

    /** One entry of {@code MENUDATA}. */
    public record Screen(String name, char[][] text, List<Option> options) {
        /** {@code CURRENTLEVELLINE}, which {@code PUTINLINE} rewrites. */
        public void setLine(int row, String s) {
            for (int i = 0; i < COLUMNS; i++) {
                text[row][i] = i < s.length() ? s.charAt(i) : ' ';
            }
        }
    }

    /** The screens in {@code MENUDATA} order, which is what {@code OptScrn} is. */
    public final List<Screen> screens = new ArrayList<>();
    private final Map<String, Screen> byName = new LinkedHashMap<>();

    /** {@code move.w #0,OptScrn}: the one the main loop starts on. */
    public static final int ONE_PLAYER = 0;

    public Screen screen(String name) {
        return byName.get(name);
    }

    public static MenuData load(GameData game) {
        Path src = game.root().resolve("source/CONTROLLOOP.s");
        if (Files.isRegularFile(src)) {
            try {
                return new MenuData(
                        Files.readString(src, StandardCharsets.ISO_8859_1));
            } catch (IOException ignored) {
                // an unreadable source simply means using the saved screens
            }
        }
        return new MenuData(ab3d.gen.Tables.MENU_NAMES,
                            ab3d.gen.Tables.MENU_TEXT,
                            ab3d.gen.Tables.MENU_OPTIONS);
    }

    /** Puts back what the parser found: a name, its rows, and its choices. */
    private MenuData(String[] names, String[][] text, int[][] options) {
        for (int i = 0; i < names.length; i++) {
            char[][] rows = new char[ROWS][];
            for (int r = 0; r < ROWS; r++) {
                rows[r] = text[i][r].toCharArray();
            }
            List<Option> opts = new ArrayList<>();
            for (int o = 0; o + 2 < options[i].length; o += 3) {
                opts.add(new Option(options[i][o], options[i][o + 1],
                                    options[i][o + 2]));
            }
            Screen s = new Screen(names[i], rows, opts);
            screens.add(s);
            byName.put(names[i], s);
        }
    }

    MenuData(String src) {
        String[] lines = src.split("\r?\n");
        Map<String, char[][]> texts = new LinkedHashMap<>();
        Map<String, List<Option>> opts = new LinkedHashMap<>();

        for (int i = 0; i < lines.length; i++) {
            String label = labelOn(lines[i]);
            if (label == null) {
                continue;
            }
            if (label.endsWith("_TXT")) {
                texts.put(label, readText(lines, i + 1));
            } else if (label.endsWith("_OPTS")) {
                opts.put(label, readOptions(lines, i + 1));
            }
        }

        for (String[] pair : order(lines)) {
            char[][] text = texts.get(pair[0]);
            List<Option> o = opts.get(pair[1]);
            if (text == null) {
                continue;
            }
            Screen s = new Screen(pair[0], text, o == null ? List.of() : o);
            screens.add(s);
            byName.put(pair[0], s);
        }
    }

    /** A label at the start of a line, with or without its colon. */
    private static String labelOn(String line) {
        Matcher m = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):?\\s*$").matcher(line);
        if (m.matches()) {
            return m.group(1);
        }
        m = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Thirty-two rows of forty characters.
     *
     * Labels sit in the middle of the block -- {@code CURRENTLEVELLINE} and
     * {@code PASSWORDLINE} both do -- so the rows are counted rather than the
     * lines of source, and anything that is not a {@code dc.b} string is stepped
     * over.
     *
     * Any row the block does not give is left blank. One block is a line short:
     * the last screen stops at thirty-one, and the
     * thirty-second row on the Amiga would draw whatever bytes followed the
     * block, which is nothing anyone chose. Spaces are the harmless reading of
     * that, and leaving the row as the zeros a fresh array holds is not -- they
     * are not a character the font has.
     */
    private static char[][] readText(String[] lines, int from) {
        char[][] out = new char[ROWS][COLUMNS];
        for (char[] line : out) {
            java.util.Arrays.fill(line, ' ');
        }
        Pattern p = Pattern.compile("dc\\.b\\s+'(.*)'\\s*(;.*)?$");
        int row = 0;
        for (int i = from; i < lines.length && row < ROWS; i++) {
            Matcher m = p.matcher(lines[i]);
            if (!m.find()) {
                if (lines[i].contains("dc.w") || lines[i].contains("dc.l")) {
                    break;
                }
                continue;
            }
            String s = m.group(1);
            for (int c = 0; c < COLUMNS; c++) {
                out[row][c] = c < s.length() ? s.charAt(c) : ' ';
            }
            row++;
        }
        return out;
    }

    /** {@code dc.w left,top,width,...} lines, up to the {@code dc.w -1}. */
    private static List<Option> readOptions(String[] lines, int from) {
        List<Option> out = new ArrayList<>();
        Pattern p = Pattern.compile("dc\\.w\\s+(-?\\d+)\\s*,?\\s*(-?\\d+)?\\s*,?\\s*(-?\\d+)?");
        for (int i = from; i < lines.length; i++) {
            Matcher m = p.matcher(lines[i]);
            if (!m.find()) {
                if (labelOn(lines[i]) != null) {
                    break;
                }
                continue;
            }
            int left = Integer.parseInt(m.group(1));
            if (left < 0) {
                break;                              // dc.w -1
            }
            if (m.group(2) == null || m.group(3) == null) {
                break;
            }
            out.add(new Option(left, Integer.parseInt(m.group(2)),
                               Integer.parseInt(m.group(3))));
        }
        return out;
    }

    /** {@code MENUDATA}: pairs of text and option labels, commented ones skipped. */
    private static List<String[]> order(String[] lines) {
        List<String[]> out = new ArrayList<>();
        Pattern p = Pattern.compile("^\\s*dc\\.l\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*$");
        List<String> flat = new ArrayList<>();
        boolean in = false;
        for (String line : lines) {
            if (line.startsWith("MENUDATA")) {
                in = true;
                continue;
            }
            if (!in) {
                continue;
            }
            if (labelOn(line) != null) {
                break;
            }
            Matcher m = p.matcher(line);
            if (m.find()) {
                flat.add(m.group(1));
            }
        }
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            out.add(new String[]{flat.get(i), flat.get(i + 1)});
        }
        return out;
    }
}
