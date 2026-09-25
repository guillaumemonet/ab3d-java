package ab3d.game;

import ab3d.tools.AdfTool;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The first run: finding the original game's files and unpacking them once.
 *
 * This port carries no game data at all. What it needs comes from two places,
 * and they are genuinely different things:
 *
 * <ul>
 *   <li>the <b>two floppies</b>, which hold the levels, the sounds, the wall
 *       and object graphics, and two of the pictures
 *   <li>the <b>source release</b>, whose {@code includes/} directory holds
 *       everything the Amiga executable had built into it -- the sine table,
 *       the palettes, the screen borders, the menu font, the three music
 *       modules, the shading and water tables, the backdrop -- and whose
 *       assembly files this port still reads its tables from
 * </ul>
 *
 * Only four of the twenty-two files read at runtime are on the floppies. The
 * rest were {@code INCBIN} in the executable, which is why having the disks is
 * not on its own enough to run this.
 *
 * What is chosen is remembered in a small properties file beside the unpacked
 * data, so this is asked once.
 */
public final class Setup {

    /** Where the unpacked floppies and the remembered paths live. */
    public static Path home() {
        String override = System.getProperty("ab3d.home");
        if (override != null) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".ab3d-java");
    }

    private static final String CONFIG = "paths.properties";
    private static final String KEY_ROOT = "source", KEY_DISK = "disk";

    /** What the game needs to start: where the sources are, and the unpacked disks. */
    public record Paths2(Path root, Path disk) {}

    private Setup() {
    }

    /**
     * Returns the two roots, asking for them the first time and remembering them.
     *
     * The system properties win when they are given, so a checkout can still be
     * run straight from its own tree without any of this.
     */
    public static Paths2 resolve() {
        String pRoot = System.getProperty("ab3d.root");
        String pDisk = System.getProperty("ab3d.disk");
        if (pRoot != null && pDisk != null) {
            return new Paths2(Paths.get(pRoot), Paths.get(pDisk));
        }

        Properties saved = read();
        Path root = saved.getProperty(KEY_ROOT) == null ? null
                : Paths.get(saved.getProperty(KEY_ROOT));
        Path disk = saved.getProperty(KEY_DISK) == null ? null
                : Paths.get(saved.getProperty(KEY_DISK));
        if (usable(root) && Files.isDirectory(disk == null ? home() : disk)) {
            return new Paths2(root, disk);
        }

        return ask();
    }

    /** A source tree is usable when the files this port reads are in it. */
    private static boolean usable(Path root) {
        return root != null && Files.isRegularFile(root.resolve("source/jg.s"))
               && Files.isRegularFile(root.resolve("includes/bigsine"));
    }

    /** The first-run conversation, and the unpacking that follows it. */
    private static Paths2 ask() {
        JOptionPane.showMessageDialog(null,
                """
                Alien Breed 3D -- portage Java

                Ce programme ne contient aucune donnee du jeu. Il lui faut :

                  1. les deux disquettes (.adf) du jeu
                  2. l'arbre des sources publie par Team17, celui qui contient
                     source/jg.s et includes/bigsine

                Les deux sont necessaires : quatre des vingt-deux fichiers lus
                sont sur les disquettes, tout le reste etait compile dans
                l'executable Amiga et n'existe que dans les sources.

                Ce qui suit n'est demande qu'une fois.""",
                "Premier lancement", JOptionPane.INFORMATION_MESSAGE);

        Path root = chooseDirectory("Ou est l'arbre des sources ? "
                + "(le dossier contenant source/ et includes/)");
        if (root == null) {
            return null;
        }
        if (!usable(root)) {
            JOptionPane.showMessageDialog(null,
                    "Ce dossier ne contient pas source/jg.s et includes/bigsine.",
                    "Pas le bon dossier", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        List<Path> adfs = chooseAdfs();
        if (adfs.isEmpty()) {
            return null;
        }

        Path disk = home().resolve("disk");
        try {
            unpack(adfs, disk);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null,
                    "Les disquettes n'ont pas pu etre lues : " + e.getMessage(),
                    "Echec", JOptionPane.ERROR_MESSAGE);
            return null;
        }

        save(root, disk);
        return new Paths2(root, disk);
    }

    private static Path chooseDirectory(String title) {
        JFileChooser c = new JFileChooser();
        c.setDialogTitle(title);
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        return c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION
                ? c.getSelectedFile().toPath() : null;
    }

    private static List<Path> chooseAdfs() {
        JFileChooser c = new JFileChooser();
        c.setDialogTitle("Les deux disquettes du jeu (.adf)");
        c.setMultiSelectionEnabled(true);
        c.setFileFilter(new FileNameExtensionFilter("Images de disquette Amiga",
                                                    "adf"));
        List<Path> out = new ArrayList<>();
        if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            for (var f : c.getSelectedFiles()) {
                out.add(f.toPath());
            }
        }
        return out;
    }

    /**
     * Unpacks each disk into its own directory.
     *
     * The two are told apart by what they hold rather than by their file names,
     * which vary with whoever made the images: the one with {@code levels/} is
     * the second disk, and the other is the first. Getting that round the wrong
     * way would leave the levels unfindable, which is a confusing way to fail.
     */
    private static void unpack(List<Path> adfs, Path into) throws IOException {
        Files.createDirectories(into);
        for (Path adf : adfs) {
            AdfTool disk = new AdfTool(Files.readAllBytes(adf));
            boolean second = disk.has("levels");
            disk.extractAll(into.resolve(second ? "disk2" : "disk1"));
        }
        if (!Files.isDirectory(into.resolve("disk2/levels"))) {
            throw new IOException("aucune des disquettes ne contient levels/ -- "
                                  + "il faut les deux, dont celle des niveaux");
        }
    }

    private static Properties read() {
        Properties p = new Properties();
        Path f = home().resolve(CONFIG);
        if (Files.isRegularFile(f)) {
            try (var in = Files.newInputStream(f)) {
                p.load(in);
            } catch (IOException ignored) {
                // an unreadable config simply means asking again
            }
        }
        return p;
    }

    private static void save(Path root, Path disk) {
        Properties p = new Properties();
        p.setProperty(KEY_ROOT, root.toAbsolutePath().toString());
        p.setProperty(KEY_DISK, disk.toAbsolutePath().toString());
        try {
            Files.createDirectories(home());
            try (var out = Files.newOutputStream(home().resolve(CONFIG))) {
                p.store(out, "Ou Alien Breed 3D a ete trouve");
            }
        } catch (IOException ignored) {
            // not being able to remember only means asking again next time
        }
    }
}
