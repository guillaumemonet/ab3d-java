package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.Zone;
import ab3d.engine.EngineState;
import ab3d.engine.Renderer68k;

/**
 * Runs the transcribed RotateLevelPts and reports what it produced, next to the
 * same projection done in floating point. The two should agree to within the
 * rounding the 68000 does; a large gap means the transcription is wrong, and a
 * small one is the fixed-point truncation we are trying to reproduce.
 */
public final class RotateCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_b");
        SineTable sine = SineTable.load(game);

        EngineState st = new EngineState(lv);
        Renderer68k r = new Renderer68k(st);

        int zoneIndex = args.length > 1 ? Integer.parseInt(args[1]) : lv.startZone;
        Zone zone = lv.zone(zoneIndex);

        // Stand at the zone's centroid, looking along the sine table's zero
        long sx = 0, sz = 0;
        for (int e : zone.exitLines) {
            sx += lv.floorLine(e).x1;
            sz += lv.floorLine(e).z1;
        }
        st.xoff = (int) (sx / zone.exitLines.length);
        st.zoff = (int) (sz / zone.exitLines.length);
        st.yoff = zone.floorHeight - 12 * 1024;
        int angle = args.length > 2 ? Integer.parseInt(args[2]) : 300;
        st.sinval = sine.sin(angle);
        st.cosval = sine.cos(angle);
        st.xwobble = 0;

        r.rotateLevelPts(zone.points);

        System.out.printf("zone %d at (%d,%d) angle %d, %d points%n",
                zoneIndex, st.xoff, st.zoff, angle, zone.points.length);
        System.out.printf("%6s %8s %8s %7s %9s %8s%n",
                "point", "rotX", "depth", "column", "float col", "delta");

        double sinf = st.sinval / 32768.0, cosf = st.cosval / 32768.0;
        int shown = 0, big = 0, inFront = 0, visible = 0, worstVisible = 0;
        for (int p : zone.points) {
            if (p < 0 || p >= lv.numPoints) {
                continue;
            }
            double dx = lv.pointX[p] - st.xoff, dz = lv.pointZ[p] - st.zoff;
            double zp = dx * sinf + dz * cosf;
            double xp = dx * cosf - dz * sinf;
            String floatCol = "-";
            int delta = 0;
            if (st.depth(p) > 0) {
                inFront++;
            }
            if (zp > 0.5) {
                double fc = 64.0 * xp / zp + 47.0;
                floatCol = String.format("%9.2f", fc);
                delta = (int) Math.round(st.onScreen[p] - fc);
                boolean onScreenCol = st.onScreen[p] >= 0
                        && st.onScreen[p] <= EngineState.VIEW_COLUMNS;
                if (st.depth(p) > 0) {
                    if (onScreenCol) {
                        visible++;
                        worstVisible = Math.max(worstVisible, Math.abs(delta));
                    } else if (Math.abs(delta) > 2) {
                        big++;
                    }
                }
            }
            if (st.depth(p) > 0 && shown++ < 14) {
                System.out.printf("%6d %8d %8d %7d %9s %8d%n",
                        p, st.rotatedX[p], st.depth(p), st.onScreen[p], floatCol, delta);
            }
        }
        System.out.printf("%n%d in front, %d of them project on screen%n", inFront, visible);
        System.out.printf("worst disagreement among on-screen points: %d column(s)%n",
                worstVisible);
        System.out.printf("off-screen points differing by more than 2: %d "
                + "(clipped before drawing)%n", big);
    }
}
