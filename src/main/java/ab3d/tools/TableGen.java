package ab3d.tools;

import ab3d.data.BulletAnims;
import ab3d.data.ColBox;
import ab3d.data.Controls;
import ab3d.data.EndZones;
import ab3d.data.GameData;
import ab3d.data.GunAnims;
import ab3d.data.GunData;
import ab3d.data.LevelNames;
import ab3d.data.MenuData;
import ab3d.data.Samples;
import ab3d.data.Shading;
import ab3d.data.SpriteBank;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes out, as Java, what the assembly parsers work out.
 *
 * Every table this port reads from the original's source is a function of files
 * that never change, so there is no reason to do the reading on every run. This
 * runs each parser once against a source tree and prints its answer as constants
 * -- gun records, animation lists, collision boxes, end zones, sprite frame
 * tables, key codes and sample lengths.
 *
 * What it cannot remove is the game's own data. The sine table, the palettes,
 * the borders, the font, the music and the shading tables are bytes that were
 * built into the Amiga executable rather than put on a floppy; no amount of
 * reading the assembly produces them.
 *
 * The parsers remain the source of truth. This only saves their answer, and the
 * checks in this directory still run against the original files, so the two can
 * be compared whenever the question comes up.
 */
public final class TableGen {

    private static final Path OUT =
            Path.of("src/main/java/ab3d/gen/Tables.java");

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Files.createDirectories(OUT.getParent());

        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(OUT, StandardCharsets.UTF_8))) {
            head(w);

            EndZones ends = EndZones.load(game);
            int[] endZones = new int[ends.count()];
            for (int i = 0; i < endZones.length; i++) {
                endZones[i] = ends.of(i);
            }
            ints(w, "END_ZONES", "{@code ENDZONES}: the room that ends each level.",
                 endZones);

            ColBox boxes = ColBox.load(game);
            int[] width = new int[boxes.count()];
            int[] height = new int[boxes.count()];
            for (int i = 0; i < width.length; i++) {
                width[i] = boxes.width(i);
                height[i] = boxes.height(i);
            }
            ints(w, "COL_WIDTH", "{@code ColBoxTable}: half-width by type.", width);
            ints(w, "COL_HEIGHT", "And how far above or below still counts.", height);

            GunData guns = GunData.load(game);
            int[] gunBytes = new int[GunData.GUNS * GunData.RECORD];
            for (int g = 0; g < GunData.GUNS; g++) {
                for (int b = 0; b < GunData.RECORD; b++) {
                    gunBytes[g * GunData.RECORD + b] = guns.byteAt(g, b);
                }
            }
            ints(w, "GUN_DATA",
                 "{@code PLR1_GunData}: eight records of thirty-two bytes, as "
                 + "assembled -- before {@code DEFAULTGAME} touches them.",
                 gunBytes);

            GunAnims anims = GunAnims.load(game);
            int[][] gunAnims = new int[8][];
            for (int i = 0; i < 8; i++) {
                gunAnims[i] = anims.frames(i);
            }
            table(w, "GUN_ANIMS",
                  "{@code GunAnims}: the frames of each weapon's animation.",
                  gunAnims);

            BulletAnims bul = BulletAnims.load(game);
            int[] flying = new int[BulletAnims.SLOTS];
            int[] burstSize = new int[BulletAnims.SLOTS];
            int[] force = new int[BulletAnims.SLOTS];
            for (int i = 0; i < BulletAnims.SLOTS; i++) {
                flying[i] = bul.flyingSize(i);
                burstSize[i] = bul.burstSize(i);
                force[i] = bul.force(i);
            }
            ints(w, "BULLET_FLYING_SIZE", "{@code BulletSizes}, first word.", flying);
            ints(w, "BULLET_BURST_SIZE", "And its second.", burstSize);
            ints(w, "BULLET_FORCE",
                 "{@code ExplosiveForce}: nought but for the rocket and grenade.",
                 force);
            steps(w, "BULLET_FLIGHT", "{@code BulletTypes}, the flight lists: "
                  + "each step is size, slot, frame and rise.", bul, true);
            steps(w, "BULLET_BURST", "And the burst lists.", bul, false);

            Controls controls = Controls.load(game);
            int[] keys = new int[Controls.Action.values().length];
            for (Controls.Action a : Controls.Action.values()) {
                keys[a.ordinal()] = controls.key(a);
            }
            ints(w, "CONTROL_KEYS",
                 "{@code CONTROLBUFFER}: the raw key each action starts on.", keys);
            String[] names = new String[controls.nameCount()];
            for (int i = 0; i < names.length; i++) {
                names[i] = controls.name(i);
            }
            strings(w, "KEY_NAMES",
                    "{@code KVALTOASC}: four characters per raw key.", names);

            Samples samples = Samples.load(game);
            String[] sampleNames = new String[samples.count()];
            int[] sampleLengths = new int[samples.count()];
            for (int i = 0; i < sampleNames.length; i++) {
                sampleNames[i] = samples.get(i).name();
                sampleLengths[i] = samples.get(i).statedLength();
            }
            strings(w, "SAMPLE_NAMES",
                    "{@code SFX_NAMES}: which file each sample number is, in the "
                    + "table's own order -- two of its entries are struck out, so "
                    + "counting the files on the disk gives a different answer.",
                    sampleNames);
            ints(w, "SAMPLE_LENGTHS", "And the length the table states.",
                 sampleLengths);

            LevelNames levels = LevelNames.load(game);
            String[] levelLines = new String[LevelNames.COUNT];
            for (int i = 0; i < levelLines.length; i++) {
                levelLines[i] = levels.line(i);
            }
            strings(w, "LEVEL_NAMES",
                    "{@code LEVEL_OPTS}: the sixteen level lines, already forty "
                    + "characters wide and already centred.", levelLines);

            MenuData menu = MenuData.load(game);
            String[] menuNames = new String[menu.screens.size()];
            String[][] menuText = new String[menuNames.length][];
            int[][] menuOpts = new int[menuNames.length][];
            for (int i = 0; i < menuNames.length; i++) {
                MenuData.Screen sc = menu.screens.get(i);
                menuNames[i] = sc.name();
                menuText[i] = new String[MenuData.ROWS];
                for (int r = 0; r < MenuData.ROWS; r++) {
                    menuText[i][r] = new String(sc.text()[r]);
                }
                int[] flat = new int[sc.options().size() * 3];
                for (int o = 0; o < sc.options().size(); o++) {
                    MenuData.Option opt = sc.options().get(o);
                    flat[o * 3] = opt.left();
                    flat[o * 3 + 1] = opt.top();
                    flat[o * 3 + 2] = opt.width();
                }
                menuOpts[i] = flat;
            }
            strings(w, "MENU_NAMES",
                    "{@code MENUDATA}: the option screens, in the order "
                    + "{@code OptScrn} indexes them.", menuNames);
            text(w, "MENU_TEXT",
                 "Each screen's thirty-two lines of forty characters, as the "
                 + "author ruled them out in the source.", menuText);
            table(w, "MENU_OPTIONS",
                  "{@code dc.w left,top,width} per choosable line, three numbers "
                  + "each, stopping where the source's {@code -1} did.", menuOpts);

            SpriteBank bank = new SpriteBank(game);
            int[][] sprites = new int[32][];
            for (int slot = 0; slot < 32; slot++) {
                int n = bank.frameCount(slot);
                int[] flat = new int[n * 2];
                for (int i = 0; i < n; i++) {
                    flat[i * 2] = bank.frame(slot, i).ptrOffset();
                    flat[i * 2 + 1] = bank.frame(slot, i).downStrip();
                }
                sprites[slot] = flat;
            }
            table(w, "SPRITE_FRAMES",
                  "The frame tables of source/objdraw3.chipram, two numbers a "
                  + "frame: where its columns start, and how far down it sits.",
                  sprites);

            int[][] basePal = new int[SpriteBank.SHEETS.length][];
            for (int i = 0; i < basePal.length; i++) {
                Path pal = game.include(SpriteBank.SHEETS[i] + ".pal");
                if (!Files.isRegularFile(pal)) {
                    basePal[i] = new int[0];
                    continue;
                }
                byte[] raw = Files.readAllBytes(pal);
                int[] row = new int[Shading.COLOURS];
                for (int c = 0; c < row.length && c * 2 + 1 < raw.length; c++) {
                    row[c] = ((raw[c * 2] & 0xff) << 8) | (raw[c * 2 + 1] & 0xff);
                }
                basePal[i] = row;
            }
            table(w, "SPRITE_PALETTE",
                  "The top row of each sheet's {@code .pal}: thirty-two colours "
                  + "at full brightness, in the order {@code SHEETS} has the "
                  + "sheets. The fourteen darker rows are not here because "
                  + "{@link ab3d.data.Shading} works them out.", basePal);

            w.println("}");
        }
        System.out.println("wrote " + OUT + " (" + Files.size(OUT) + " bytes)");
    }

    private static void head(PrintWriter w) {
        w.println("package ab3d.gen;");
        w.println();
        w.println("/**");
        w.println(" * Tables worked out from the original's assembly, saved so it");
        w.println(" * need not be read at every start.");
        w.println(" *");
        w.println(" * Written by {@link ab3d.tools.TableGen} against a source tree.");
        w.println(" * The parsers in {@code ab3d.data} remain what decides these");
        w.println(" * values; this is only their answer, kept.");
        w.println(" */");
        w.println("public final class Tables {");
        w.println();
        w.println("    private Tables() {");
        w.println("    }");
    }

    private static void ints(PrintWriter w, String name, String doc, int[] v) {
        w.println();
        w.println("    /** " + doc + " */");
        w.print("    public static final int[] " + name + " = {");
        for (int i = 0; i < v.length; i++) {
            w.print((i % 12 == 0 ? "\n        " : " ") + v[i] + ",");
        }
        w.println("\n    };");
    }

    private static void table(PrintWriter w, String name, String doc, int[][] v) {
        w.println();
        w.println("    /** " + doc + " */");
        w.println("    public static final int[][] " + name + " = {");
        for (int[] row : v) {
            w.print("        {");
            for (int i = 0; i < row.length; i++) {
                w.print((i > 0 ? ", " : "") + row[i]);
            }
            w.println("},");
        }
        w.println("    };");
    }

    private static void steps(PrintWriter w, String name, String doc,
                              BulletAnims a, boolean flight) {
        int[][] v = new int[BulletAnims.SLOTS][];
        for (int i = 0; i < v.length; i++) {
            BulletAnims.Step[] list = flight ? a.flight(i) : a.burst(i);
            int[] flat = new int[list.length * 4];
            for (int j = 0; j < list.length; j++) {
                flat[j * 4] = list[j].size();
                flat[j * 4 + 1] = list[j].slot();
                flat[j * 4 + 2] = list[j].frame();
                flat[j * 4 + 3] = list[j].rise();
            }
            v[i] = flat;
        }
        table(w, name, doc, v);
    }

    private static void strings(PrintWriter w, String name, String doc, String[] v) {
        w.println();
        w.println("    /** " + doc + " */");
        w.println("    public static final String[] " + name + " = {");
        for (String s : v) {
            w.println("        \"" + quote(s) + "\",");
        }
        w.println("    };");
    }

    /** A screen at a time, its rows written one to a line. */
    private static void text(PrintWriter w, String name, String doc, String[][] v) {
        w.println();
        w.println("    /** " + doc + " */");
        w.println("    public static final String[][] " + name + " = {");
        for (String[] screen : v) {
            w.println("        {");
            for (String line : screen) {
                w.println("            \"" + quote(line) + "\",");
            }
            w.println("        },");
        }
        w.println("    };");
    }

    private static String quote(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
