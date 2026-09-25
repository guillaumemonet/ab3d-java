package ab3d.tools;

import ab3d.data.FloorLine;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.data.ZoneGraph;

/**
 * Prints what the loaders made of the original binary files, and sanity-checks
 * the result. This is how we tell a correct format decode from a plausible one.
 */
public final class DumpTool {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String name = args.length > 0 ? args[0] : "lev1";

        System.out.println("root   : " + game.root());
        Level lv = Level.load(game, name);

        System.out.println("level  : " + lv.name);
        System.out.printf("  start      : x=%d z=%d zone=%d%n", lv.startX, lv.startZ, lv.startZone);
        System.out.printf("  counts     : points=%d zones=%d objectPts=%d controlPts=%d floorLines(hdr)=%d%n",
                lv.numPoints, lv.zones.length, lv.numObjectPoints, lv.numControlPoints, lv.numFloorLines);
        System.out.printf("  pointers   : points=%d floorLines=%d objects=%d objectPts=%d plr1=%d plr2=%d%n",
                lv.ptrPoints, lv.ptrFloorLines, lv.ptrObjects, lv.ptrObjectPoints,
                lv.ptrPlr1Obj, lv.ptrPlr2Obj);
        System.out.printf("  sizes     : geometry=%d graphics=%d clips=%d bytes%n",
                lv.data.size(), lv.graphics.data.size(), lv.clips.size());
        System.out.printf("  derived    : floorLines=%d%n", lv.floorLines.length);

        checks(lv);

        System.out.println("\n-- first 8 zones --");
        for (int i = 0; i < Math.min(8, lv.zones.length); i++) {
            System.out.println("  " + lv.zones[i]);
        }

        System.out.println("\n-- first 8 floor lines --");
        for (int i = 0; i < Math.min(8, lv.floorLines.length); i++) {
            System.out.println("  " + i + " " + lv.floorLines[i]);
        }

        System.out.println("\n-- start zone --");
        Zone z = lv.zone(lv.startZone);
        System.out.println("  " + z);
        System.out.print("  exits :");
        for (int e : z.exitLines) {
            System.out.print(" " + e);
        }
        System.out.print("\n  points:");
        for (int p : z.points) {
            System.out.print(" " + p);
        }
        System.out.println();
        System.out.println("  player inside start zone: " + insideZone(lv, z, lv.startX, lv.startZ));
    }

    /** Every cross-check that can falsify the format decode. */
    private static void checks(Level lv) {
        System.out.println("\n-- consistency checks --");

        check("start zone in range", lv.startZone >= 0 && lv.startZone < lv.zones.length);

        int badZoneRef = 0, solid = 0;
        for (FloorLine fl : lv.floorLines) {
            if (fl.isSolid()) {
                solid++;
            } else if (fl.toZone >= lv.zones.length) {
                badZoneRef++;
            }
        }
        check("floor line zone refs valid (" + badZoneRef + " bad, " + solid + " solid of "
                + lv.floorLines.length + ")", badZoneRef == 0);

        int badExit = 0, badPoint = 0, totalExits = 0, totalPoints = 0;
        for (Zone z : lv.zones) {
            for (int e : z.exitLines) {
                totalExits++;
                if (e >= lv.floorLines.length) {
                    badExit++;
                }
            }
            for (int p : z.points) {
                totalPoints++;
                if (p >= lv.numPoints) {
                    badPoint++;
                }
            }
        }
        check("zone exit line indices valid (" + badExit + " bad of " + totalExits + ")", badExit == 0);
        check("zone point indices valid (" + badPoint + " bad of " + totalPoints + ")", badPoint == 0);

        int badHeights = 0;
        for (Zone z : lv.zones) {
            // y grows downwards, so the floor is numerically below the roof
            if (z.floorHeight < z.roofHeight) {
                badHeights++;
            }
        }
        check("zone floor below roof (" + badHeights + " odd of " + lv.zones.length + ")", badHeights == 0);

        int off = 0, on = 0;
        for (Zone z : lv.zones) {
            if (z.exitLines.length == 0) {
                continue;
            }
            if (insideZone(lv, z, cx(lv, z), cz(lv, z))) {
                on++;
            } else {
                off++;
            }
        }
        check("zone centroid inside its own exit lines (" + on + " ok, " + off + " not)", off == 0);

        // Portals come in pairs: crossing a line into zone B must be mirrored by
        // a line of B leading back. This is what makes the portal walk reversible.
        int unpaired = 0, portals = 0;
        for (Zone z : lv.zones) {
            for (int e : z.exitLines) {
                FloorLine fl = lv.floorLine(e);
                if (fl.isSolid()) {
                    continue;
                }
                portals++;
                boolean back = false;
                for (int e2 : lv.zone(fl.toZone).exitLines) {
                    if (lv.floorLine(e2).toZone == z.index) {
                        back = true;
                        break;
                    }
                }
                if (!back) {
                    unpaired++;
                }
            }
        }
        check("portals are reciprocal (" + unpaired + " unpaired of " + portals + ")", unpaired == 0);

        // Zone graph streams: every wall must name real points and sit inside its zone
        int graphs = 0, upper = 0, walls = 0, surfaces = 0;
        int wallBadPoint = 0, badHeight = 0, truncated = 0;
        java.util.Set<Integer> textures = new java.util.TreeSet<>();
        for (int i = 0; i < lv.zones.length; i++) {
            ZoneGraph g = lv.graphics.lowerGraph(i);
            if (lv.graphics.upperGraph(i) != null) {
                upper++;
            }
            if (g == null) {
                continue;
            }
            graphs++;
            if (g.truncated) {
                truncated++;
            }
            Zone z = lv.zone(i);
            surfaces += g.surfaces.size();
            for (ZoneGraph.Wall w : g.walls) {
                walls++;
                textures.add(w.texture());
                if (w.pointA() < 0 || w.pointA() >= lv.numPoints
                        || w.pointB() < 0 || w.pointB() >= lv.numPoints) {
                    wallBadPoint++;
                }
                if (w.top() < z.roofHeight || w.bottom() > z.floorHeight) {
                    badHeight++;
                }
            }
        }
        System.out.printf("  graphs: %d lower, %d upper, %d walls, %d surfaces, textures used %s%n",
                graphs, upper, walls, surfaces, textures);
        check("wall point indices valid (" + wallBadPoint + " bad of " + walls + ")", wallBadPoint == 0);
        check("wall heights inside their zone (" + badHeight + " bad of " + walls + ")", badHeight == 0);
        check("graph streams terminate (" + truncated + " truncated of " + graphs + ")", truncated == 0);

        int wrongId = 0;
        for (Zone z : lv.zones) {
            if (lv.data.s16(z.offset) != z.index) {
                wrongId++;
            }
        }
        check("zone record self-identifies (" + wrongId + " mismatched of " + lv.zones.length + ")",
                wrongId == 0);
    }

    /** The engine's own inside test: on the correct side of every bounding line. */
    private static boolean insideZone(Level lv, Zone z, int x, int zz) {
        for (int e : z.exitLines) {
            if (e >= lv.floorLines.length) {
                return false;
            }
            if (lv.floorLine(e).side(x, zz) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Centroid of the zone outline, taken from its bounding lines. */
    private static int cx(Level lv, Zone z) {
        long s = 0;
        for (int e : z.exitLines) {
            s += lv.floorLine(e).x1;
        }
        return (int) (s / z.exitLines.length);
    }

    private static int cz(Level lv, Zone z) {
        long s = 0;
        for (int e : z.exitLines) {
            s += lv.floorLine(e).z1;
        }
        return (int) (s / z.exitLines.length);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  [ OK ] " : "  [FAIL] ") + what);
    }
}
