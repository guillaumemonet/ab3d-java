package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.FloorTexture;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.Zone;
import ab3d.engine.EngineState;
import ab3d.engine.FloorDraw;
import ab3d.engine.PolyLoop;
import ab3d.engine.Renderer68k;


/**
 * Runs both floor passes over a level and checks they agree.
 *
 * Pass one writes an index per column, pass two converts exactly those. The two
 * counts must match: a colour written where no index was left would mean stale
 * data reaching the screen, and an index never converted would leave a hole.
 */
public final class FloorFillCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");
        SineTable sine = SineTable.load(game);
        FloorTexture sheet = FloorTexture.load(game);

        EngineState st = new EngineState(lv);
        Renderer68k rot = new Renderer68k(st);
        FloorDraw fd = new FloorDraw(st);
        BinReader g = lv.graphics.data;

        long[] n = new long[4];   // surfaces filled, indices, colours, mismatched
        long[] shadeHist = new long[FloorTexture.SHADES + 1];

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
            st.sxoff = st.xoff;
            st.szoff = st.zoff;

            int off = g.s32(lv.graphics.zoneGraphOffsetsOffset + zi * 8);
            if (off <= 0 || !g.inRange(off, 4)) {
                continue;
            }
            for (int a = 0; a < 4096; a += 1024) {
                st.sinval = sine.sin(a);
                st.cosval = sine.cos(a);
                rot.rotateLevelPts(zone.points);

                PolyLoop.run(g, off, g.size(), new PolyLoop.Sink() {
                    @Override
                    public void surface(BinReader gg, int at, int type) {
                        int kind = type == PolyLoop.T_ROOF ? FloorDraw.ROOF : FloorDraw.FLOOR;
                        FloorDraw.Setup su = fd.setup(gg, at, kind);
                        if (!su.visible) {
                            return;
                        }
                        FloorDraw.Corners c = fd.classifyCorners(gg, at, su);
                        if (!FloorDraw.shouldDraw(c)) {
                            return;
                        }
                        fd.buildSpans(gg, at, su);
                        if (!fd.drawIt) {
                            return;
                        }
                        fd.fillRows(su, c);
                        if (fd.rows.isEmpty()) {
                            return;
                        }
                        int after = at + 4 + 2 * (gg.s16(at + 2) + 1);
                        int scale = gg.s16(after + 2);
                        int tile = gg.s16(after + 4);
                        int light = gg.s16(after + 6);

                        fd.writeIndices(sheet, su, scale, tile);
                        fd.convertIndices(sheet, su, light);
                        for (FloorDraw.Row rr : fd.rows) {
                            shadeHist[Math.min(FloorDraw.floorShadeRow(light, rr.depth),
                                               FloorTexture.SHADES)]++;
                        }

                        n[0]++;
                        n[1] += fd.indicesWritten;
                        n[2] += fd.coloursWritten;
                        if (fd.indicesWritten != fd.coloursWritten) {
                            n[3]++;
                        }
                    }
                });
            }
        }

        System.out.printf("%s: %d surfaces filled%n", lv.name, n[0]);
        System.out.printf("  indices written %d, colours written %d%n", n[1], n[2]);
        System.out.printf("  surfaces where the two passes disagree: %d%n", n[3]);
        StringBuilder sb = new StringBuilder("  palette rows used:");
        for (int i = 0; i < shadeHist.length; i++) {
            if (shadeHist[i] > 0) {
                sb.append(' ').append(i).append('=').append(shadeHist[i]);
            }
        }
        System.out.println(sb);
    }
}
