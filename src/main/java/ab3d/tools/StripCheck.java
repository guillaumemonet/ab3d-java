package ab3d.tools;

import ab3d.data.BinReader;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.Zone;
import ab3d.engine.CalcAndDraw;
import ab3d.engine.EngineState;
import ab3d.engine.PolyLoop;
import ab3d.engine.Renderer68k;
import ab3d.engine.WallDraw;
import ab3d.engine.WallSubdivide;

/**
 * Runs the whole transcribed chain for one viewpoint -- rotate, test, subdivide,
 * build strips -- and checks the strips are well formed.
 *
 * What must hold: columns advance left to right and stay inside the clip bounds,
 * depths stay positive, and the top of a strip is above its bottom. Anything
 * else means one of the divides is producing a value the original would not.
 */
public final class StripCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");
        SineTable sine = SineTable.load(game);

        EngineState st = new EngineState(lv);
        Renderer68k rot = new Renderer68k(st);
        WallDraw wd = new WallDraw(st);
        WallSubdivide sub = new WallSubdivide();
        CalcAndDraw cad = new CalcAndDraw(st);
        BinReader g = lv.graphics.data;

        int walls = 0, drawn = 0, strips = 0;
        int badOrder = 0, badClip = 0, badDepth = 0, badRows = 0;
        int minCol = Integer.MAX_VALUE, maxCol = Integer.MIN_VALUE;
        int minRow = Integer.MAX_VALUE, maxRow = Integer.MIN_VALUE;

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

            for (int a = 0; a < 4096; a += 512) {
                st.sinval = sine.sin(a);
                st.cosval = sine.cos(a);
                rot.rotateLevelPts(zone.points);

                int off = g.s32(lv.graphics.zoneGraphOffsetsOffset + zi * 8);
                if (off <= 0 || !g.inRange(off, 4)) {
                    continue;
                }
                final int[] counters = { 0, 0, 0 };
                PolyLoop.run(g, off, g.size(), new PolyLoop.Sink() {
                    @Override
                    public void wall(BinReader gg, int at, boolean seeThrough) {
                        counters[0]++;
                        WallDraw.Wall w = wd.read(gg, at);
                        if (!w.visible) {
                            return;
                        }
                        counters[1]++;
                        if (!sub.run(st.rotatedX[w.pointA], st.depth(w.pointA),
                                st.rotatedX[w.pointB], st.depth(w.pointB),
                                w.leftEnd, w.rightEnd, w.leftBright, w.rightBright)) {
                            return;
                        }
                        cad.run(sub, w.topOfWall, w.botOfWall, sub.multCount);
                        counters[2] += cad.strips.size();
                    }
                });
                walls += counters[0];
                drawn += counters[1];
                strips += counters[2];

                for (CalcAndDraw.Strip s : cad.strips) {
                    if (s.leftColumn > s.rightColumn) {
                        badOrder++;
                    }
                    if (s.leftColumn < st.leftClip || s.rightColumn > st.rightClip + 1) {
                        badClip++;
                    }
                    if (s.leftDepth <= 0 || s.rightDepth <= 0) {
                        badDepth++;
                    }
                    if (s.leftTop > s.leftBottom || s.rightTop > s.rightBottom) {
                        badRows++;
                    }
                    minCol = Math.min(minCol, s.leftColumn);
                    maxCol = Math.max(maxCol, s.rightColumn);
                    minRow = Math.min(minRow, Math.min(s.leftTop, s.rightTop));
                    maxRow = Math.max(maxRow, Math.max(s.leftBottom, s.rightBottom));
                }
            }
        }

        System.out.printf("%s: %d wall records, %d passed the tests, %d strips built%n",
                lv.name, walls, drawn, strips);
        System.out.printf("  columns %d..%d (clip 0..%d), rows %d..%d (view 0..%d)%n",
                minCol, maxCol, EngineState.VIEW_COLUMNS,
                minRow, maxRow, EngineState.VIEW_ROWS);
        System.out.printf("  malformed: order %d, outside clip %d, depth %d, rows %d%n",
                badOrder, badClip, badDepth, badRows);
    }
}
