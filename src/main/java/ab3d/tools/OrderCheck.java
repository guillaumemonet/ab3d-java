package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.engine.OrderZones;

/**
 * Runs the transcribed OrderZones and checks the result really is back to front.
 *
 * The routine is a painter's sort over portals rather than a distance sort, so a
 * few inversions between zones of similar depth are expected; a large count
 * would mean the list surgery was transcribed wrongly.
 */
public final class OrderCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");
        OrderZones order = new OrderZones(lv);

        int totalZones = 0, totalInversions = 0, totalPairs = 0, empty = 0;
        long relinks = 0, tail = 0, runs = 0, unconverged = 0;
        double sumFirst = 0, sumLast = 0;
        int directionSamples = 0;

        for (int zi = 0; zi < lv.zones.length; zi++) {
            Zone zone = lv.zone(zi);
            if (zone.graphics.isEmpty()) {
                continue;
            }
            int[] c = centroid(lv, zone);
            order.run(zone.graphics, c[0], c[1]);
            runs++;
            relinks += order.relinks;
            tail += order.relinksInTail;
            if (order.relinksInTail > 0) {
                unconverged++;
            }
            if (order.orderCount == 0) {
                empty++;
                continue;
            }
            totalZones++;

            double[] dist = new double[order.orderCount];
            for (int i = 0; i < order.orderCount; i++) {
                int z = order.finalOrder[i];
                if (z < 0 || z >= lv.zones.length) {
                    dist[i] = Double.NaN;
                    continue;
                }
                int[] zc = centroid(lv, lv.zone(z));
                dist[i] = Math.hypot(zc[0] - c[0], zc[1] - c[1]);
            }
            for (int i = 0; i + 1 < dist.length; i++) {
                if (Double.isNaN(dist[i]) || Double.isNaN(dist[i + 1])) {
                    continue;
                }
                totalPairs++;
                if (dist[i] < dist[i + 1]) {
                    totalInversions++;   // a nearer zone listed before a further one
                }
            }

            // Which end of the list is nearer settles whether the engine draws
            // front to back or back to front.
            if (dist.length >= 2 && !Double.isNaN(dist[0])
                    && !Double.isNaN(dist[dist.length - 1])) {
                sumFirst += dist[0];
                sumLast += dist[dist.length - 1];
                directionSamples++;
            }

            if (zi == (args.length > 1 ? Integer.parseInt(args[1]) : -1)) {
                System.out.printf("zone %d lists %d rooms, order:%n", zi, order.orderCount);
                for (int i = 0; i < order.orderCount; i++) {
                    System.out.printf("   %2d. zone %3d  distance %7.0f%n",
                            i, order.finalOrder[i], dist[i]);
                }
            }
        }

        System.out.printf("%s: ordered %d zones (%d produced no list)%n",
                lv.name, totalZones, empty);
        double pctIncreasing = totalPairs == 0 ? 0
                : 100.0 * totalInversions / totalPairs;
        System.out.printf("adjacent pairs: %d, distance increasing along the list: "
                + "%.1f%% (decreasing: %.1f%%)%n",
                totalPairs, pctIncreasing, 100.0 - pctIncreasing);
        if (directionSamples > 0) {
            System.out.printf("mean distance: first entry %.0f, last entry %.0f -> %s%n",
                    sumFirst / directionSamples, sumLast / directionSamples,
                    sumFirst < sumLast ? "NEAREST first (front to back)"
                                       : "FURTHEST first (back to front)");
        System.out.printf("  relinks %d over %d runs, %d in the last ten passes, "
                        + "%d runs that had not settled%n",
                        relinks, runs, tail, unconverged);
        }
    }

    private static int[] centroid(Level lv, Zone z) {
        long sx = 0, sz = 0;
        int n = 0;
        for (int e : z.exitLines) {
            if (e < lv.floorLines.length) {
                sx += lv.floorLine(e).x1;
                sz += lv.floorLine(e).z1;
                n++;
            }
        }
        return n == 0 ? new int[] { 0, 0 } : new int[] { (int) (sx / n), (int) (sz / n) };
    }
}
