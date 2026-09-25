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
 * Runs the transcribed floor setup and edge walk over every surface of a level.
 *
 * What must hold for a span table to be usable: the covered rows lie inside the
 * view, the top is above the bottom, and on each covered row the left column is
 * not to the right of the right column. A table failing that would fill
 * backwards or off the screen.
 */
public final class SpanCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");
        SineTable sine = SineTable.load(game);

        EngineState st = new EngineState(lv);
        Renderer68k rot = new Renderer68k(st);
        FloorDraw fd = new FloorDraw(st);
        BinReader g = lv.graphics.data;

        int[] n = new int[7];
        java.util.TreeMap<String,Integer> why = new java.util.TreeMap<>();
        long[] edgeStat = new long[6];
        long[] rowStat = new long[5];
        long[] shade = new long[15];
        int[] zscale = { Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE };   // surfaces, rejected, drawn, badRows, badOrder, spans

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

            for (int a = 0; a < 4096; a += 1024) {
                st.sinval = sine.sin(a);
                st.cosval = sine.cos(a);
                rot.rotateLevelPts(zone.points);

                int off = g.s32(lv.graphics.zoneGraphOffsetsOffset + zi * 8);
                if (off <= 0 || !g.inRange(off, 4)) {
                    continue;
                }
                PolyLoop.run(g, off, g.size(), new PolyLoop.Sink() {
                    @Override
                    public void surface(BinReader gg, int at, int type) {
                        n[0]++;
                        int kind = type == PolyLoop.T_ROOF ? FloorDraw.ROOF : FloorDraw.FLOOR;
                        FloorDraw.Setup su = fd.setup(gg, at, kind);
                        if (!su.visible) {
                            n[1]++;
                            why.merge(su.rejectedBy, 1, Integer::sum);
                            return;
                        }
                        FloorDraw.Corners c = fd.classifyCorners(gg, at, su);
                        if (!FloorDraw.shouldDraw(c)) {
                            n[1]++;
                            why.merge("no corner on screen", 1, Integer::sum);
                            return;
                        }
                        fd.buildSpans(gg, at, su);
                        edgeStat[0] += fd.edgeBothBehind;
                        edgeStat[1] += fd.edgeBadPoint;
                        edgeStat[2] += fd.edgeOverflow;
                        edgeStat[3] += fd.edgeClipFailed;
                        edgeStat[4] += fd.edgeFlat;
                        edgeStat[5] += fd.edgeDrawn;
                        if (fd.depthMin != Integer.MAX_VALUE) {
                            zscale[0] = Math.min(zscale[0], fd.depthMin);
                            zscale[1] = Math.max(zscale[1], fd.depthMax);
                            zscale[2] = Math.min(zscale[2], su.minZ);
                            zscale[3] = Math.max(zscale[3], su.minZ);
                        }
                        if (!fd.drawIt) {
                            n[1]++;
                            why.merge("every edge flat or behind", 1, Integer::sum);
                            return;
                        }
                        n[2]++;
                        n[6] += fd.edgesSkipped;
                        fd.fillRows(su, c);
                        rowStat[0] += fd.rows.size();
                        int prevDepth = Integer.MAX_VALUE;
                        for (ab3d.engine.FloorDraw.Row rw : fd.rows) {
                            if (rw.left < st.leftClip || rw.right > st.rightClip
                                    || rw.left >= rw.right) {
                                rowStat[1]++;
                            }
                            if (rw.depth <= 0) {
                                rowStat[2]++;
                            }
                            if (rw.depth > prevDepth) {
                                rowStat[3]++;
                            }
                            prevDepth = rw.depth;
                            int sr = ab3d.engine.FloorDraw.floorShadeRow(
                                    gg.s16(at + 4 + 2 * (gg.s16(at + 2) + 1) + 6), rw.depth);
                            if (sr < 0 || sr >= ab3d.engine.FloorDraw.FLOOR_SHADES) {
                                rowStat[4]++;
                            }
                            shade[Math.max(0, Math.min(sr, 14))]++;
                        }
                        if (fd.top < 0 || fd.bottom >= EngineState.VIEW_ROWS
                                || fd.top > fd.bottom) {
                            n[3]++;
                            return;
                        }
                        for (int r = fd.top; r <= fd.bottom; r++) {
                            if (fd.leftSide[r] == Integer.MIN_VALUE
                                    || fd.rightSide[r] == Integer.MIN_VALUE) {
                                continue;     // a row no edge reached
                            }
                            n[5]++;
                            if (fd.leftSide[r] > fd.rightSide[r]) {
                                n[4]++;
                            }
                        }
                    }
                });
            }
        }

        System.out.printf("%s: %d surfaces, %d rejected, %d built spans%n",
                lv.name, n[0], n[1], n[2]);
        System.out.printf("  row range outside the view : %d%n", n[3]);
        System.out.printf("  rows with left past right  : %d of %d fully covered%n", n[4], n[5]);
        System.out.printf("  (edges needing a clipping path not yet transcribed: %d)%n", n[6]);
        System.out.println("  rejected by:");
        why.forEach((k, v) -> System.out.printf("    %-40s %5d%n", k, v));
        System.out.println("  edge outcomes:");
        String[] lbl = { "both ends nearer than minZ", "corner index out of range",
                         "divide would overflow", "crossing could not be found",
                         "flat on screen", "walked into the span table" };
        long tot = 0;
        for (long v : edgeStat) {
            tot += v;
        }
        for (int i = 0; i < edgeStat.length; i++) {
            System.out.printf("    %-40s %7d  (%.1f%%)%n", lbl[i], edgeStat[i],
                    tot == 0 ? 0.0 : 100.0 * edgeStat[i] / tot);
        }
        System.out.printf("  filled rows: %d | outside clip %d | depth <= 0 %d | "
                + "depth not decreasing %d%n",
                rowStat[0], rowStat[1], rowStat[2], rowStat[3]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shade.length; i++) {
            if (shade[i] > 0) {
                sb.append(i).append(':').append(shade[i]).append(' ');
            }
        }
        System.out.printf("  shade rows out of range: %d | rows used: %s%n",
                rowStat[4], sb);
        System.out.printf("  corner depths %d..%d, minZ %d..%d%n",
                zscale[0], zscale[1], zscale[2], zscale[3]);
    }
}
