package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;

import java.nio.file.Files;
import java.nio.file.Path;

/** Do the shipped palettes hold twelve-bit colours, or something wider? */
public final class PaletteCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        check(game.root().resolve("includes/walls/pipes.wad"), 2048, "a wall palette");
        check(game.root().resolve("includes/floorpalscaled"), -1, "floorpalscaled");
        check(game.root().resolve("includes/brightenfile"), -1, "brightenfile");
        check(game.root().resolve("includes/darkenedcols"), -1, "darkenedcols");
    }

    private static void check(Path p, int limit, String what) throws Exception {
        if (!Files.isRegularFile(p)) {
            System.out.printf("%-18s missing%n", what);
            return;
        }
        byte[] d = ab3d.data.SbDepacker.unpack(Files.readAllBytes(p));
        int end = limit > 0 ? Math.min(limit, d.length) : d.length;
        int ok = 0, bad = 0;
        for (int i = 0; i + 2 <= end; i += 2) {
            int v = ((d[i] & 0xff) << 8) | (d[i + 1] & 0xff);
            if ((v & 0xf000) == 0) {
                ok++;
            } else {
                bad++;
            }
        }
        System.out.printf("%-18s %5d fit twelve bits, %5d do not (%.1f%% wide)%n",
                          what, ok, bad, 100.0 * bad / (ok + bad));
    }
}
