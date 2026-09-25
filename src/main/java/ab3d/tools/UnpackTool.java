package ab3d.tools;

import ab3d.data.SbDepacker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Unpacks {@code =SB=} files, and verifies the depacker against known pairs.
 *
 * The wall textures exist twice: compressed on the original disk and already
 * unpacked in the source tree. Decoding one and comparing it byte for byte with
 * the other is a complete test of the depacker, with no guesswork left in it.
 */
public final class UnpackTool {

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && args[0].equals("--verify")) {
            verify(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length < 2) {
            System.err.println("usage: UnpackTool <in> <out>");
            System.err.println("       UnpackTool --verify <packedDir> <plainDir>");
            System.exit(2);
        }
        byte[] packed = Files.readAllBytes(Path.of(args[0]));
        byte[] plain = SbDepacker.unpack(packed);
        Files.write(Path.of(args[1]), plain);
        System.out.printf("%d -> %d bytes%n", packed.length, plain.length);
    }

    /** Unpacks everything in one directory and diffs it against the other. */
    private static void verify(Path packedDir, Path plainDir) throws Exception {
        List<Path> files = new ArrayList<>();
        try (var s = Files.list(packedDir)) {
            s.filter(Files::isRegularFile).sorted().forEach(files::add);
        }

        int ok = 0, mismatch = 0, failed = 0, skipped = 0;
        for (Path p : files) {
            Path reference = plainDir.resolve(p.getFileName().toString().toLowerCase());
            if (!Files.isRegularFile(reference)) {
                skipped++;
                continue;
            }
            byte[] packed = Files.readAllBytes(p);
            byte[] expected = Files.readAllBytes(reference);
            String name = p.getFileName().toString();
            try {
                byte[] got = SbDepacker.unpack(packed);
                if (java.util.Arrays.equals(got, expected)) {
                    System.out.printf("  [ OK ] %-24s %6d -> %6d%n",
                            name, packed.length, got.length);
                    ok++;
                } else {
                    System.out.printf("  [DIFF] %-24s got %d, expected %d, first difference at %d%n",
                            name, got.length, expected.length, firstDiff(got, expected));
                    mismatch++;
                }
            } catch (RuntimeException e) {
                System.out.printf("  [FAIL] %-24s %s%n", name, e.getMessage());
                failed++;
            }
        }
        System.out.printf("%n%d identical, %d different, %d failed, %d without a reference%n",
                ok, mismatch, failed, skipped);
        if (mismatch > 0 || failed > 0) {
            System.exit(1);
        }
    }

    private static int firstDiff(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if (a[i] != b[i]) {
                return i;
            }
        }
        return n;
    }
}
