package ab3d.game;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Which graphics card the window landed on, said out loud.
 *
 * On a laptop with two cards the driver hands a {@code java.exe} the
 * integrated one, since it is not a game its profile knows -- and nothing
 * says so. A Java process cannot choose for itself on Windows: the
 * {@code NvOptimusEnablement} and {@code AmdPowerXpressRequestHighPerformance}
 * symbols have to be exported by the native executable, which
 * {@code java.exe} does not do. So the most it can do is read
 * {@code GL_RENDERER}, print it, and when it looks like an integrated chip on
 * a machine that has another card, say so plainly and give the fix.
 *
 * This port rasterises in software and only hands the GPU one texture a
 * frame, so the card costs it little; the warning matters for any timing
 * measured on it, which is wrong on the slow chip without anything showing.
 */
public final class GpuCheck {

    /** Renderer strings of integrated chips and of the software fallback. */
    private static final Pattern INTEGRATED = Pattern.compile(
            "Intel.*(HD|UHD|Iris)|AMD Radeon\\(TM\\) Graphics|Microsoft Basic Render",
            Pattern.CASE_INSENSITIVE);

    /** The exact fix, as Windows names its settings. */
    public static final String FIX =
            "Parametres Windows > Systeme > Affichage > Graphiques : ajouter java.exe"
            + " et javaw.exe et choisir << Hautes performances >>, puis relancer.";

    private GpuCheck() {
    }

    public static boolean looksIntegrated(String renderer) {
        return renderer != null && INTEGRATED.matcher(renderer).find();
    }

    /**
     * The display adapters Windows knows of, or an empty list elsewhere or when
     * it cannot be asked. Three seconds at most: this is a courtesy, not
     * something to hang the start on.
     */
    public static List<String> adapters() {
        List<String> out = new ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return out;
        }
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-CimInstance Win32_VideoController | "
                    + "Select-Object -ExpandProperty Name")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return out;
                }
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) {
                        out.add(line.trim());
                    }
                }
            }
        } catch (Exception e) {
            // no PowerShell, or not allowed to run it: say nothing about others
        }
        return out;
    }

    /**
     * Prints the renderer and, when it looks integrated, the warning.
     *
     * @return the warning to keep on screen, or null when there is none
     */
    public static String report(String renderer, List<String> adapters) {
        System.out.println("Carte graphique utilisee (GL_RENDERER) : " + renderer);
        if (!adapters.isEmpty()) {
            System.out.println("Cartes presentes : " + String.join(", ", adapters));
        }
        if (!looksIntegrated(renderer)) {
            return null;
        }
        boolean other = adapters.stream().anyMatch(a -> !looksIntegrated(a));
        String warn = "ATTENTION : le jeu tourne sur la carte integree (" + renderer + ")"
                + (other ? " alors que la machine en a une autre." : ".");
        System.err.println();
        System.err.println("************************************************************");
        System.err.println(warn);
        System.err.println(FIX);
        System.err.println("Toute mesure de performance faite ainsi est celle de l'iGPU.");
        System.err.println("************************************************************");
        System.err.println();
        return warn;
    }
}
