package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.SpriteBank;
import ab3d.data.SpriteSheet;
import ab3d.data.Zone;
import ab3d.engine.BitMapObj;
import ab3d.engine.EngineState;
import ab3d.engine.Renderer68k;

/** Prints the working values of the first sprite that draws columns but no pixels. */
public final class ObjProbe {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");
        SineTable sine = SineTable.load(game);
        SpriteBank bank = new SpriteBank(game);
        EngineState st = new EngineState(lv);
        Renderer68k rot = new Renderer68k(st);
        BitMapObj bm = new BitMapObj(st, bank, game);

        int shown = 0;
        for (int zi = 0; zi < lv.zones.length && shown < 3; zi++) {
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

            for (int a = 0; a < 4096 && shown < 3; a += 512) {
                st.sinval = sine.sin(a);
                st.cosval = sine.cos(a);
                rot.rotateObjectPts();
                for (GameObject o : lv.objects) {
                    if (o.zone != zi || o.inUpperStorey || !o.isSprite()) {
                        continue;
                    }
                    boolean drew = bm.draw(o, zone.roofHeight, zone.floorHeight);
                    boolean empty = !drew && bm.columnsDrawn > 0;
                    if (!empty || bm.rowsFetched < 400) {
                        continue;
                    }
                    SpriteSheet sheet = bank.sheet(o.slot);
                    System.out.printf("%s obj %d slot %d frame %d  w=%d h=%d "
                                    + "texels %dx%d%n",
                                    empty ? "EMPTY" : "DREW ", o.index, o.slot,
                                    o.frame, o.widthScale, o.heightScale,
                                    o.spriteWidth, o.spriteHeight);
                    System.out.printf("   depth %d  startColumn %d  rows %d startRow %d%n",
                                      bm.lastDepth, bm.lastColumn, bm.lastRows,
                                      bm.lastStartRow);
                    System.out.printf("   horiz step %.4f  vert step %.4f%n",
                                      bm.lastHoriz / 65536.0, bm.lastVert / 65536.0);
                    System.out.printf("   columns %d..%d of %d in the table%n",
                                      bm.lastColumn,
                                      bm.lastColumn + (int) (bm.columnsDrawn
                                          * bm.lastHoriz / 65536.0),
                                      sheet.columnCount());
                    System.out.printf("   fetched %d, columns drawn %d, blank %d%n",
                                      bm.rowsFetched, bm.columnsDrawn, bm.blankColumns);
                    int opaque = 0, tried = 0;
                    for (int c = bm.lastColumn; c < bm.lastColumn + 64; c++) {
                        for (int y = 0; y < 64; y++) {
                            tried++;
                            if (sheet.pixel(c, y) != 0) {
                                opaque++;
                            }
                        }
                    }
                    System.out.printf("   sheet under it: %d of %d opaque%n", opaque, tried);
                    StringBuilder sb = new StringBuilder("   rows visited:");
                    int d1 = bm.lastStartRow;
                    for (int r = 0; r < Math.min(bm.lastRows, 12); r++) {
                        sb.append(' ').append(d1);
                        d1 += bm.lastVert >> 16;
                    }
                    System.out.println(sb);
                    shown++;
                    if (shown >= 3) {
                        break;
                    }
                }
            }
        }
    }
}
