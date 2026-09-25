package ab3d.tools;

import ab3d.engine.WallSubdivide;

/**
 * Exercises the transcribed walldraw subdivision on walls of varying geometry.
 *
 * What should hold: the point count follows the doubling the passes imply, the
 * depths stay between the two ends and vary monotonically, and a wall that is
 * nearer or more skewed gets subdivided more than one that is far and flat.
 */
public final class SubdivideCheck {

    public static void main(String[] args) {
        WallSubdivide s = new WallSubdivide();

        System.out.printf("%-30s %6s %6s %7s %12s %12s%n",
                "wall", "iters", "mult", "points", "depth range", "monotonic");

        check(s, "far, flat",        -4000, 900,  4000, 900);
        check(s, "far, skewed",      -4000, 900,  4000, 1900);
        check(s, "near, flat",       -4000, 90,   4000, 90);
        check(s, "near, skewed",     -4000, 60,   4000, 800);
        check(s, "very near",        -4000, 20,   4000, 30);
        check(s, "huge depth split", -4000, 60,   4000, 2000);
        check(s, "one end behind",   -4000, -50,  4000, 600);
        check(s, "both behind",      -4000, -50,  4000, -20);
    }

    private static void check(WallSubdivide s, String name,
                              int lx, int ld, int rx, int rd) {
        boolean drawn = s.run(lx, ld, rx, rd, 0, 191, 300, 300);
        if (!drawn) {
            System.out.printf("%-30s %6s %6s %7s %12s %12s%n",
                    name, "-", "-", 0, "-", "rejected");
            return;
        }
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        boolean monotonic = true;
        for (int i = 0; i < s.count; i++) {
            min = Math.min(min, s.depth[i]);
            max = Math.max(max, s.depth[i]);
            if (i > 0 && s.count > 2) {
                boolean up = s.depth[s.count - 1] >= s.depth[0];
                if (up ? s.depth[i] < s.depth[i - 1] - 1
                       : s.depth[i] > s.depth[i - 1] + 1) {
                    monotonic = false;
                }
            }
        }
        System.out.printf("%-30s %6d %6d %7d %12s %12s%n",
                name, s.iters, s.multCount, s.count,
                min + ".." + max, monotonic ? "yes" : "NO");
    }
}
