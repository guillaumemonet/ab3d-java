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
import ab3d.engine.ScreenDivide;
import ab3d.engine.WallDraw;
import ab3d.engine.WallSubdivide;

/**
 * Runs the transcribed chain down to the per-column list and checks it.
 *
 * Within one strip the emitted columns must increase and stay inside the clip
 * bounds, and every interpolated quantity must land between the strip's two
 * ends. A value outside that range means a step was computed with the wrong
 * shift, which is the failure this stage is prone to.
 */
public final class ColumnCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");
        SineTable sine = SineTable.load(game);

        EngineState st = new EngineState(lv);
        Renderer68k rot = new Renderer68k(st);
        WallDraw wd = new WallDraw(st);
        WallSubdivide sub = new WallSubdivide();
        CalcAndDraw cad = new CalcAndDraw(st);
        ScreenDivide sd = new ScreenDivide(st, game);
        BinReader g = lv.graphics.data;

        int[] stat = new int[8];   // strips, columns, notIncreasing, outsideClip,
                                   // depthOutside, rowsSwapped, texOutside, empty

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
                    public void wall(BinReader gg, int at, boolean seeThrough) {
                        WallDraw.Wall w = wd.read(gg, at);
                        if (!w.visible) {
                            return;
                        }
                        if (!sub.run(st.rotatedX[w.pointA], st.depth(w.pointA),
                                st.rotatedX[w.pointB], st.depth(w.pointB),
                                w.leftEnd, w.rightEnd, w.leftBright, w.rightBright)) {
                            return;
                        }
                        cad.run(sub, w.topOfWall, w.botOfWall, sub.multCount);
                        for (CalcAndDraw.Strip s : cad.strips) {
                            if (!sd.run(s)) {
                                continue;
                            }
                            stat[0]++;
                            if (sd.count == 0) {
                                stat[7]++;
                                continue;
                            }
                            stat[1] += sd.count;
                            int prev = Integer.MIN_VALUE;
                            int dLo = Math.min(s.leftDepth, s.rightDepth);
                            int dHi = Math.max(s.leftDepth, s.rightDepth);
                            int tLo = Math.min(s.leftTexColumn, s.rightTexColumn);
                            int tHi = Math.max(s.leftTexColumn, s.rightTexColumn);
                            for (int i = 0; i < sd.count; i++) {
                                int c = sd.columnAt[i];
                                if (c <= prev) {
                                    stat[2]++;
                                }
                                prev = c;
                                if (c < st.leftClip || c >= st.rightClip) {
                                    stat[3]++;
                                }
                                int dep = ScreenDivide.whole(sd.depthAt[i]);
                                if (dep < dLo - 1 || dep > dHi + 1) {
                                    stat[4]++;
                                }
                                if (ScreenDivide.whole(sd.topAt[i])
                                        > ScreenDivide.whole(sd.bottomAt[i])) {
                                    stat[5]++;
                                }
                                int tx = ScreenDivide.whole(sd.texColumnAt[i]);
                                if (tx < tLo - 1 || tx > tHi + 1) {
                                    stat[6]++;
                                }
                            }
                        }
                    }
                });
            }
        }

        System.out.printf("%s: %d strips divided, %d columns emitted (%d strips emitted none)%n",
                lv.name, stat[0], stat[1], stat[7]);
        System.out.printf("  columns not increasing : %d%n", stat[2]);
        System.out.printf("  columns outside clip   : %d%n", stat[3]);
        System.out.printf("  depth outside its ends : %d%n", stat[4]);
        System.out.printf("  texture col outside    : %d%n", stat[6]);
        System.out.printf("  top below bottom       : %d%n", stat[5]);
    }
}
