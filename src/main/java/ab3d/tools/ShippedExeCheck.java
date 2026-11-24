package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.ShippedExe;

import java.util.Arrays;

/**
 * Decrunches disk one's {@code abd.pk} and checks that it is laid out the way
 * {@link ShippedExe} assumes: the tables either side of the three it takes from
 * the executable must match the source release byte for byte, so the offsets
 * really are where those tables sit.
 *
 * <pre>java -Dab3d.root=../ab3d-rtg -Dab3d.disk=../adf-extract ab3d.tools.ShippedExeCheck</pre>
 */
public final class ShippedExeCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        long t0 = System.nanoTime();
        ShippedExe exe = game.shippedExe();
        if (exe == null) {
            System.out.println("FAIL: no known abd.pk under " + game.disk());
            System.exit(1);
        }
        System.out.printf("decrunched in %.0f ms%n", (System.nanoTime() - t0) / 1e6);
        int bad = 0;
        // neighbours, from the source tree itself
        bad += same(exe, game, "texturepalscaled", 1, 0x2ddec + 65536, "includes/texturepalscaled");
        bad += same(exe, game, "waterfile", 1, 0x78412 + 11264 + 8192 * 4, "includes/waterfile");
        bad += same(exe, game, "darkenedcols", 1, 0x78412 - 8192, "includes/darkenedcols");
        bad += same(exe, game, "OptFont", 1, 0x4ef56 - 2048, "includes/OptFont");
        bad += same(exe, game, "blueind.vec", 1, 0x265ce, "newvectobj/blueind.vec");
        bad += same(exe, game, "robot.vec", 1, 0x22bcc, "newvectobj/robot.vec");
        // each table taken, against the release's copy (three must differ)
        java.util.Set<String> differ = java.util.Set.of("Texturemaps", "brightenfile", "optcop");
        for (String name : ShippedExe.names()) {
            java.nio.file.Path src = game.root().resolve("includes").resolve(name);
            byte[] rel = java.nio.file.Files.isRegularFile(src)
                         ? java.nio.file.Files.readAllBytes(src) : null;
            boolean eq = rel != null && Arrays.equals(rel, exe.table(name));
            boolean expected = differ.contains(name) ? !eq : eq;
            System.out.printf("%-17s %s%s%n", name, eq ? "same as release" : "differs from release",
                              expected ? "" : "   <-- UNEXPECTED");
            if (!expected) {
                bad++;
            }
        }
        // and what is taken: the water table must be twelve-bit colours
        byte[] br = exe.table("brightenfile");
        int notColour = 0;
        for (int i = 0; i < br.length; i += 2) {
            if ((br[i] & 0xf0) != 0) {
                notColour++;
            }
        }
        System.out.println("brightentab: " + br.length / 2 + " words, " + notColour
                           + " not twelve-bit colours");
        if (notColour != 0) {
            bad++;
        }
        System.out.println(bad == 0 ? "OK" : "FAIL: " + bad);
        System.exit(bad == 0 ? 0 : 1);
    }

    private static int same(ShippedExe exe, GameData game, String what, int hunk, int at,
                            String sourceFile) throws Exception {
        byte[] want = java.nio.file.Files.readAllBytes(game.root().resolve(sourceFile));
        byte[] got = exe.slice(hunk, at, want.length);
        boolean ok = Arrays.equals(want, got);
        System.out.printf("%-17s hunk %d +%06x %6d bytes %s%n", what, hunk, at, want.length,
                          ok ? "identical" : "DIFFERENT");
        return ok ? 0 : 1;
    }
}
