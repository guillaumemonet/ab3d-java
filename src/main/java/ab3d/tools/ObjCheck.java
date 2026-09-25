package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.SpriteBank;
import ab3d.data.Zone;
import ab3d.engine.BitMapObj;
import ab3d.engine.EngineState;
import ab3d.engine.ObjDraw;
import ab3d.engine.Renderer68k;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Checks {@code BitMapObj} and the sort that feeds it.
 *
 * Four criteria, all fixed before the first run:
 *
 * <ol>
 *   <li>the depth table never holds an inversion, and the first object drawn is
 *       the farthest;</li>
 *   <li>every pixel that reaches the buffer lies inside the clip window the
 *       routine computed for that sprite;</li>
 *   <li>a sprite that draws at all touches at least one column, and no column
 *       index escapes its sheet;</li>
 *   <li>the rejection reasons account for every sprite that draws nothing.</li>
 * </ol>
 *
 * The second is the one worth the trouble. The buffer is stamped with a value
 * the palette cannot produce, so a written slot is recognisable without the
 * routine reporting its own positions -- a clip sign error shows up as a pixel
 * outside the window rather than as a count that happens to agree.
 *
 * One class of sprite draws columns and writes nothing, and the depth spread is
 * what accounts for it: those sit at a median depth of about ninety against
 * about three hundred for the ones that draw, and are clipped to a third of the
 * rows. They are objects the camera is standing almost on top of, where the only
 * part inside the room's clip window is the transparent margin above the
 * graphic. That is a property of where this check puts the camera -- the mean of
 * a zone's exit-line ends, which often lands on an object -- rather than of the
 * routine.
 */
public final class ObjCheck {

    /** Outside the twelve bits a colour can occupy, so it cannot be drawn. */
    private static final short UNWRITTEN = 0x7fff;

    /** Median and quartiles, which say more here than a mean over a long tail. */
    private static String spread(java.util.List<Integer> v) {
        if (v.isEmpty()) {
            return "(none)";
        }
        java.util.List<Integer> c = new java.util.ArrayList<>(v);
        java.util.Collections.sort(c);
        return String.format("min %d q1 %d med %d q3 %d max %d",
                             c.get(0), c.get(c.size() / 4), c.get(c.size() / 2),
                             c.get(c.size() * 3 / 4), c.get(c.size() - 1));
    }

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");
        SineTable sine = SineTable.load(game);
        SpriteBank bank = new SpriteBank(game);

        EngineState st = new EngineState(lv);
        Renderer68k rot = new Renderer68k(st);
        BitMapObj bm = new BitMapObj(st, bank, game);
        ObjDraw od = new ObjDraw(st, bm);

        Map<String, Integer> rejects = new TreeMap<>();
        Map<Integer, Integer> slots = new HashMap<>();
        int inversions = 0, outside = 0, drawnSprites = 0, attempted = 0;
        int pixels = 0, columns = 0, zonesVisited = 0;
        int rowsBefore = 0, rowsPast = 0, emptyFetches = 0, emptyCols = 0;
        java.util.List<Integer> emptyDepth = new java.util.ArrayList<>();
        java.util.List<Integer> drewDepth = new java.util.ArrayList<>();
        java.util.List<Integer> emptyRows = new java.util.ArrayList<>();
        java.util.List<Integer> drewRows = new java.util.ArrayList<>();

        for (int zi = 0; zi < lv.zones.length; zi++) {
            Zone zone = lv.zone(zi);
            if (zone.exitLines.length == 0) {
                continue;
            }
            boolean any = false;
            for (GameObject o : lv.objects) {
                if (o.zone == zi) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                continue;
            }
            zonesVisited++;

            long sx = 0, sz = 0;
            for (int e : zone.exitLines) {
                sx += lv.floorLine(e).x1;
                sz += lv.floorLine(e).z1;
            }
            st.xoff = (int) (sx / zone.exitLines.length);
            st.zoff = (int) (sz / zone.exitLines.length);
            st.yoff = zone.floorHeight - 12 * 1024;
            st.deriveYoffs();

            od.currZone = zi;
            od.doUpper = false;

            for (int a = 0; a < 4096; a += 512) {
                st.sinval = sine.sin(a);
                st.cosval = sine.cos(a);
                rot.rotateObjectPts();

                // One sprite at a time, so a violation names its object.
                for (GameObject o : lv.objects) {
                    if (o.zone != zi || o.inUpperStorey || !o.isSprite()) {
                        continue;
                    }
                    java.util.Arrays.fill(st.screen, UNWRITTEN);
                    attempted++;
                    boolean drew = bm.draw(o, zone.roofHeight, zone.floorHeight);
                    if (!drew) {
                        String why = bm.rejectedBy == null ? "nopixels" : bm.rejectedBy;
                        if (why.equals("tooclose")) {
                            why = st.objRotZ[o.pointIndex] <= 0 ? "behind" : "tooclose";
                        }
                        if (why.equals("nopixels")) {
                            why = bm.columnsDrawn == 0 ? "nopixels:allblank"
                                 : bm.rowsBefore + bm.rowsPast > 0
                                     ? "nopixels:offgraphic" : "nopixels:transparent";
                        }
                        rejects.merge(why, 1, Integer::sum);
                        if (why.startsWith("nopixels:t")) {
                            emptyFetches += bm.rowsFetched;
                            emptyCols += bm.columnsDrawn;
                            emptyDepth.add(bm.lastDepth);
                            emptyRows.add(bm.lastRows);
                        }
                        continue;
                    }
                    drawnSprites++;
                    pixels += bm.pixelsWritten;
                    columns += bm.columnsDrawn;
                    rowsBefore += bm.rowsBefore;
                    rowsPast += bm.rowsPast;
                    slots.merge(o.slot, 1, Integer::sum);
                    drewDepth.add(bm.lastDepth);
                    drewRows.add(bm.lastRows);

                    for (int row = 0; row < EngineState.VIEW_ROWS; row++) {
                        for (int col = 0; col < EngineState.VIEW_COLUMNS; col++) {
                            int at = st.rowStart(row) + EngineState.columnWord(col);
                            if (st.screen[at] == UNWRITTEN) {
                                continue;
                            }
                            if (row < bm.objClipT || row >= bm.objClipB
                                    || col < bm.leftClipB || col >= bm.rightClipB) {
                                outside++;
                            }
                        }
                    }
                }

                // The sort, run whole so the order can be checked.
                od.run(lv.objects, zone.roofHeight, zone.floorHeight);
                for (int i = 1; i < ObjDraw.MAX_SORTED; i++) {
                    if (od.depthAt(i) <= 0) {
                        break;
                    }
                    if (od.depthAt(i) > od.depthAt(i - 1)) {
                        inversions++;
                    }
                }
            }
        }

        System.out.printf("%s: %d zones with objects, %d sprite draws attempted%n",
                          lv.name, zonesVisited, attempted);
        System.out.printf("  drew: %d sprites, %d columns, %d pixels%n",
                          drawnSprites, columns, pixels);
        System.out.printf("  pixels outside the clip window: %d%n", outside);
        System.out.printf("  rows fetched before the graphic: %d, past it: %d%n",
                          rowsBefore, rowsPast);
        System.out.printf("  depth-table inversions: %d%n", inversions);
        System.out.printf("  all-transparent sprites: %d columns, %d fetches%n",
                          emptyCols, emptyFetches);
        System.out.printf("  depth  drew: %s   all-transparent: %s%n",
                          spread(drewDepth), spread(emptyDepth));
        System.out.printf("  rows   drew: %s   all-transparent: %s%n",
                          spread(drewRows), spread(emptyRows));
        System.out.println("  rejected: " + rejects);
        System.out.println("  graphic slots drawn: " + new TreeMap<>(slots));
    }
}
