package ab3d.tools;

import ab3d.data.FloorLine;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.engine.Doors;
import ab3d.engine.Frame68k;

/** What blocks the player at one spot: every exit line of the zone, with reasons. */
public final class SpotProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args[0]);
        int zi = Integer.parseInt(args[1]);
        int x = Integer.parseInt(args[2]);
        int z = Integer.parseInt(args[3]);
        Frame68k f = new Frame68k(lv, game);

        int angle = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        f.render(zi, x, z, lv.zone(zi).floorHeight - 12 * 1024, angle);
        System.out.printf("  at angle %d: %d zones, %d walls, %d floors, "
                        + "%d sprites of %d object records seen%n",
                          angle, f.zonesDrawn, f.wallsDrawn, f.surfacesDrawn,
                          f.spritesDrawn, f.objectsSeen);
        int inView = 0;
        for (ab3d.data.GameObject o : lv.objects) {
            if (o.notInPlay || !o.isSprite()) {
                continue;
            }
            inView++;
        }
        System.out.printf("  level has %d drawable sprite records%n", inView);
        java.util.Set<Integer> drawn = new java.util.TreeSet<>();
        for (int[] w : f.clipWindows) {
            drawn.add(w[2]);
        }
        int withObjects = 0, objectsThere = 0;
        for (int dz : drawn) {
            int n = 0;
            for (ab3d.data.GameObject o : lv.objects) {
                if (o.zone == dz && !o.notInPlay && o.isSprite()) {
                    n++;
                }
            }
            if (n > 0) {
                withObjects++;
                objectsThere += n;
                System.out.printf("    zone %3d holds %d sprites%n", dz, n);
            }
        }
        System.out.printf("  of the %d zones drawn, %d hold sprites (%d in total)%n",
                          drawn.size(), withObjects, objectsThere);

        Zone here = lv.zone(zi);
        System.out.printf("%s zone %d at (%d,%d): floor %d roof %d headroom %d%n",
                          lv.name, zi, x, z, here.floorHeight, here.roofHeight,
                          here.floorHeight - here.roofHeight);
        System.out.println("  exit lines (side < 0 means the point is beyond it):");
        for (int e : here.exitLines) {
            if (e < 0 || e >= lv.floorLines.length) {
                continue;
            }
            FloorLine fl = lv.floorLine(e);
            String door = "";
            for (Doors.Door d : f.doors.doors) {
                if (d.zone == fl.toZone) {
                    door = String.format(" [DOOR trigger %d, %d..%d, now %d]",
                                         d.trigger, d.bottom, d.top, d.height);
                }
                for (int[] w : d.walls) {
                    if (w[0] == e) {
                        door += " [door wall]";
                    }
                }
            }
            String dest = "solid";
            if (fl.toZone >= 0 && fl.toZone < lv.zones.length) {
                Zone t = lv.zone(fl.toZone);
                int head = t.floorHeight - t.roofHeight;
                dest = String.format("zone %3d floor %6d roof %6d headroom %6d%s",
                                     fl.toZone, t.floorHeight, t.roofHeight, head,
                                     head >= 12 * 1024 ? "" : "  <-- TOO LOW TO ENTER");
            }
            System.out.printf("   line %4d side %8d -> %s%s%n",
                              e, fl.side(x, z), dest, door);
        }
    }
}
