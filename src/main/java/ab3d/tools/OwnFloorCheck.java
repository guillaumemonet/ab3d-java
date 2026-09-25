package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.Zone;
import ab3d.engine.EngineState;
import ab3d.engine.FloorDraw;
import ab3d.engine.PolyLoop;
import ab3d.engine.Renderer68k;

/**
 * Checks the floor the viewer is actually standing on.
 *
 * A zone's own floor sits a fixed eye height below the camera and fills the
 * lower half of the view, so it must produce spans at every angle. Surfaces
 * belonging to other zones can legitimately be rejected -- far below, far above,
 * or behind -- which is why a rejection rate over a whole graph says nothing on
 * its own.
 */
public final class OwnFloorCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");
        SineTable sine = SineTable.load(game);

        EngineState st = new EngineState(lv);
        Renderer68k rot = new Renderer68k(st);
        FloorDraw fd = new FloorDraw(st);
        BinReader g = lv.graphics.data;

        int tested = 0, drew = 0, noSurface = 0;
        java.util.TreeMap<String, Integer> why = new java.util.TreeMap<>();

        for (int zi = 0; zi < lv.zones.length; zi++) {
            Zone zone = lv.zone(zi);
            if (zone.exitLines.length == 0) {
                continue;
            }
            long sx = 0, sz = 0;
            for (int e : zone.exitLines) {
                sx += lv.floorLine(e).x1;
                sz += lv.floorLine(e).z1;
            }
            st.xoff = (int) (sx / zone.exitLines.length);
            st.zoff = (int) (sz / zone.exitLines.length);
            st.yoff = zone.floorHeight - 12 * 1024;
            st.deriveYoffs();

            int off = g.s32(lv.graphics.zoneGraphOffsetsOffset + zi * 8);
            if (off <= 0 || !g.inRange(off, 4)) {
                continue;
            }

            for (int a = 0; a < 4096; a += 512) {
                st.sinval = sine.sin(a);
                st.cosval = sine.cos(a);
                rot.rotateLevelPts(zone.points);

                final boolean[] found = { false, false };
                final int[] deepest = { Integer.MIN_VALUE };
                final int[] minz = { 0 };
                final String[] reason = { null };
                PolyLoop.run(g, off, g.size(), new PolyLoop.Sink() {
                    @Override
                    public void surface(BinReader gg, int at, int type) {
                        if (type != PolyLoop.T_FLOOR) {
                            return;
                        }
                        // the surface whose height is this zone's own floor
                        if ((gg.s16(at) << 6) != zone.floorHeight) {
                            return;
                        }
                        found[0] = true;
                        FloorDraw.Setup su = fd.setup(gg, at, FloorDraw.FLOOR);
                        if (!su.visible) {
                            reason[0] = su.rejectedBy;
                            return;
                        }
                        fd.buildSpans(gg, at, su);
                        if (fd.drawIt) {
                            found[1] = true;
                        } else {
                            reason[0] = fd.depthMax < su.minZ
                                    ? "whole surface nearer than minZ (out of view below)"
                                    : "no edge walked despite reachable depth";
                            deepest[0] = Math.max(deepest[0], fd.depthMax);
                            minz[0] = su.minZ;
                        }
                    }
                });
                if (!found[0]) {
                    noSurface++;
                    continue;
                }
                tested++;
                if (found[1]) {
                    drew++;
                } else {
                    why.merge(reason[0] == null ? "?" : reason[0], 1, Integer::sum);
                }
            }
        }

        System.out.printf("%s: own floor tested %d times, drew %d (%.1f%%)%n",
                lv.name, tested, drew, tested == 0 ? 0.0 : 100.0 * drew / tested);
        System.out.printf("  viewpoints whose graph had no matching floor: %d%n", noSurface);
        if (!why.isEmpty()) {
            System.out.println("  failures:");
            why.forEach((k, v) -> System.out.printf("    %-44s %5d%n", k, v));
        }
    }
}
