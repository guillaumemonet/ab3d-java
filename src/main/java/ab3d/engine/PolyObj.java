package ab3d.engine;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.SineTable;
import ab3d.data.VectorModel;
import ab3d.m68k.M68k;

import java.io.IOException;

/**
 * {@code PolygonObj}: the second object renderer, for the ones that are models.
 *
 * {@code cmp.b #$ff,6(a0) / bne BitMapObj} is the fork. Almost everything in the
 * game is a sprite, but a hundred and thirty-nine things across the sixteen
 * levels are not: the exit signs, the gas pipes, and the four coloured
 * indicators that sit beside a locked door and say which key opens it. Without
 * this they simply are not drawn, which is why a red door and a blue one looked
 * the same.
 *
 * <h2>How it draws</h2>
 *
 * The model turns on one axis only -- there is no pitch or roll -- so the
 * rotation is a single sine and cosine, taken at the object's facing less the
 * viewer's. Points become screen columns and rows the same way everything else
 * does, by dividing by depth.
 *
 * Hidden surfaces are handled by sorting, not by a depth buffer: each model is
 * cut into parts, every part has one point that stands for it, and the parts are
 * drawn furthest first. Within a part the faces are back-face culled by the sign
 * of the cross product of the first three corners.
 *
 * A face is then filled <em>by column</em>, not by row. That is not a choice --
 * the copper list this engine draws into is column-major, so
 * {@link #topAt}/{@link #bottomAt} hold, for each screen column the face covers,
 * the row it starts and ends on and the texture position at each end.
 */
public final class PolyObj {

    /** {@code cmp.w #50,d1 / ble objbehind}. */
    private static final int MIN_DEPTH = 50;
    /** {@code add.w #47,d3} and {@code add.w #40,d4}: the middle of the view. */
    private static final int MID_COLUMN = 47, MID_ROW = 40;
    /** {@code move.w #63*256+63,d1}: a texture is sixty-four by sixty-four. */
    private static final int TEXEL_MASK = 0x3f3f;
    /** {@code PolyTopTab: ds.w 96*4}. */
    private static final int COLUMNS = EngineState.VIEW_COLUMNS;
    /** Colours in one row of {@code texturepalscaled}. */
    private static final int PALETTE_ROW = 256;

    /**
     * {@code objscalecols}: how a face's brightness picks a palette row.
     *
     * Two entries of the darkest, then four of each shade up to the thirteenth,
     * then twenty of the brightest -- so the ramp is coarse at both ends and
     * even in the middle. The value is already the row times sixty-four, and the
     * caller shifts it up three more to reach the byte.
     */
    private static final int[] SCALE_COLS = buildScaleCols();

    private static int[] buildScaleCols() {
        int[] out = new int[2 + 4 * 13 + 20];
        int at = 0;
        for (int i = 0; i < 2; i++) {
            out[at++] = 0;
        }
        for (int shade = 1; shade <= 13; shade++) {
            for (int i = 0; i < 4; i++) {
                out[at++] = 64 * shade;
            }
        }
        while (at < out.length) {
            out[at++] = 64 * 14;
        }
        return out;
    }

    private final EngineState s;
    private final SineTable sine;
    private final byte[] textureMaps;
    private final short[] texturePal;
    private final short[] objIntoCop;
    private final VectorModel[] models = new VectorModel[VectorModel.SLOTS.length];

    /** {@code boxrot}: each point turned, then moved to where the object is. */
    private final int[] rotX = new int[MAX_POINTS];
    private final int[] rotY = new int[MAX_POINTS];
    private final int[] rotZ = new int[MAX_POINTS];
    /** {@code boxonscr}: the column and row each point lands on. */
    private final int[] scrX = new int[MAX_POINTS];
    private final int[] scrY = new int[MAX_POINTS];
    /** {@code boxbrights}: how lit each point is, before the face averages them. */
    private final int[] bright = new int[MAX_POINTS];
    private static final int MAX_POINTS = 256;

    /** {@code PolyTopTab} and {@code PolyBotTab}: row, u and v per column. */
    private final int[] topAt = new int[COLUMNS];
    private final int[] topU = new int[COLUMNS];
    private final int[] topV = new int[COLUMNS];
    private final int[] bottomAt = new int[COLUMNS];
    private final int[] bottomU = new int[COLUMNS];
    private final int[] bottomV = new int[COLUMNS];

    /** How much came out, for the checks. */
    public int modelsDrawn, facesDrawn, facesCulled, pixelsWritten;
    /** Why the last object was not drawn, or null. */
    public String rejectedBy;

    private int left, right;
    private boolean anyEdge;
    private int objBright;
    private int objClipT, objClipB;

    public PolyObj(EngineState state, GameData game, SineTable sine)
            throws IOException {
        this.s = state;
        this.sine = sine;
        this.textureMaps = game.bytes("Texturemaps");

        byte[] pal = game.bytes("texturepalscaled");
        this.texturePal = new short[pal.length / 2];
        for (int i = 0; i < texturePal.length; i++) {
            texturePal[i] = (short) (((pal[i * 2] & 0xff) << 8)
                                     | (pal[i * 2 + 1] & 0xff));
        }

        int[] x = ab3d.data.BuiltTables.xToCopX();
        this.objIntoCop = new short[x.length];
        for (int i = 0; i < x.length; i++) {
            objIntoCop[i] = (short) x[i];
        }

        for (int slot = 0; slot < models.length; slot++) {
            try {
                models[slot] = VectorModel.load(game, slot);
            } catch (IOException e) {
                models[slot] = null;     // a model this build does not carry
            }
        }
    }

    /**
     * Draws one model, or says why not.
     *
     * @param facing the object's own {@code Facing}, which is what it turns by
     */
    public boolean draw(GameObject o, int facing, int ty3d, int by3d) {
        rejectedBy = null;
        int before = pixelsWritten;

        if (o.slot < 0 || o.slot >= models.length || models[o.slot] == null) {
            return reject("nomodel");
        }
        VectorModel model = models[o.slot];
        if (model.pointCount() > MAX_POINTS) {
            return reject("toomanypoints");
        }

        int point = o.pointIndex;
        if (point < 0 || point >= s.objRotZ.length) {
            return reject("nopoint");
        }
        // move.w 2(a1,d0.w*8),d1 -- the depth of the point it stands on
        int midZ = s.objRotZ[point];
        if (midZ <= 0) {
            return reject("behind");
        }

        // move.w (a0),d2 / move.w d1,d3 / asr.w #7,d3 / add.w d3,d2
        objBright = M68k.w(o.brightness + (midZ >> 7));

        objClipT = s.topClip;
        objClipB = s.botClip;

        if (!rotate(model, o, facing, midZ, s.objRotX[point])) {
            return reject("allbehind");
        }

        drawParts(model, o);
        modelsDrawn++;
        return pixelsWritten > before;
    }

    /**
     * {@code rotobj} and {@code convtoscr}: turn every point, then project it.
     *
     * The turn is about the upright axis alone. Height needs no rotation at all,
     * only the shift into the same fixed point as everything else --
     * {@code ext.l d3 / asl.l #7,d3} on a value the routine has already doubled.
     */
    private boolean rotate(VectorModel model, GameObject o, int facing,
                           int midZ, int midX) {
        // move.w ObjAng,d2 / sub.w #2048,d2 / sub.w angpos,d2 / and.w #8191,d2
        int ang = M68k.w(M68k.w(facing - 2048) - s.angpos) & 8191;
        int sn = sine.sinByte(ang);
        int cs = sine.cosByte(ang);

        VectorModel.Point[] pts = model.frame(o.frame);
        int n = model.pointCount();

        // move.w 2(a0),d2 / ext.l d2 / asl.l #7,d2 / sub.l yoff,d2
        int height = (o.height << 7) - s.yoff;

        for (int i = 0; i < n; i++) {
            int px = pts[i].x(), py = pts[i].y(), pz = pts[i].z();
            int dx = M68k.w(px + px);
            int dy = M68k.w(py + py);
            int dz = M68k.w(pz + pz);

            // muls d7,d4 / muls d6,d2 / sub.l d4,d2 / asr.l #8,d2
            rotX[i] = (M68k.muls(dx, sn) - M68k.muls(dz, cs)) >> 8;
            // ext.l d3 / asl.l #7,d3
            rotY[i] = dy << 7;
            // the depth uses the point as written, not the doubled copy
            int z = M68k.muls(px, cs) + M68k.muls(pz, sn);
            rotZ[i] = (z << 2) >> 16;

            // add.w #20,d4 / asr.w #2,d4
            bright[i] = M68k.w(rotZ[i] + 20) >> 2;
        }

        // convtoscr
        for (int i = 0; i < n; i++) {
            int x = rotX[i] + midX;
            int y = rotY[i] + height;
            int z = M68k.w(rotZ[i] + midZ);
            if (z <= 0) {
                return false;                     // ble polybehind
            }
            rotX[i] = x;
            rotY[i] = y;
            rotZ[i] = z;
            scrX[i] = M68k.w(M68k.divsInto(x, z) + MID_COLUMN);
            scrY[i] = M68k.w(M68k.divsInto(y, z) + MID_ROW);
            bright[i] = Math.max(0, Math.min(13, bright[i]));
        }
        return true;
    }

    /**
     * {@code PutinParts} then {@code Partloop}: furthest part first.
     *
     * The key is the squared distance of the point that stands for the part, the
     * two horizontal terms taken down by seven first so the sum stays in a
     * longword. Nothing else sorts: within a part the faces are simply in the
     * order the file has them, and the back-facing ones drop out.
     */
    private void drawParts(VectorModel model, GameObject o) {
        VectorModel.Part[] parts = model.parts();
        long[] key = new long[parts.length];
        Integer[] order = new Integer[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int p = parts[i].pointIndex();
            if (p < 0 || p >= model.pointCount()) {
                key[i] = Long.MIN_VALUE;
            } else {
                long dx = rotX[p] >> 7;
                long dy = rotY[p] >> 7;
                long dz = rotZ[p];
                key[i] = dx * dx + dy * dy + dz * dz;
            }
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Long.compare(key[b], key[a]));

        for (Integer i : order) {
            for (VectorModel.Polygon face : parts[i].faces()) {
                if (face.edges() > 0) {
                    face(face, model.pointCount());
                }
            }
        }
    }

    /**
     * {@code doapoly}: one face, culled, clipped, and filled.
     *
     * The cull is the cross product of the first three corners on screen. Its
     * sign says which way the face points, and its size is how square-on it is,
     * which is also what lights it: {@code polybright} divided by a number the
     * face carries gives the shade.
     */
    private void face(VectorModel.Polygon f, int points) {
        left = 960;
        right = -10;
        anyEdge = false;

        int[] p = f.point();
        for (int i = 0; i < 3; i++) {
            if (p[i] < 0 || p[i] >= points) {
                return;
            }
        }
        int x0 = scrX[p[0]], y0 = scrY[p[0]];
        int x1 = scrX[p[1]], y1 = scrY[p[1]];
        int x2 = scrX[p[2]], y2 = scrY[p[2]];

        // sub.w d1,d0 / sub.w d1,d2 / sub.w d4,d3 / sub.w d4,d5
        // muls d3,d2 / muls d5,d0 / sub.l d0,d2 / ble polybehind
        long cross = (long) M68k.w(y0 - y1) * M68k.w(x2 - x1)
                   - (long) M68k.w(y2 - y1) * M68k.w(x0 - x1);
        if (cross <= 0) {
            facesCulled++;
            return;                               // facing away
        }

        edges(f, points);
        if (!anyEdge) {
            return;
        }

        int lo = left, hi = right;
        if (hi <= s.leftClip || lo >= s.rightClip) {
            return;
        }
        lo = Math.max(lo, s.leftClip);
        hi = Math.min(hi, s.rightClip);
        if (hi <= lo) {
            return;
        }

        // move.l polybright,d1 / asl.l #3,d1 / divs (a1)+,d1
        int shade = shadeOf(cross, f.divisor());
        fill(f, lo, hi, shade);
        facesDrawn++;
    }

    /** {@code neg.w d1 / add.w #14,d1 / add.w objbright,d1}, then the ramp. */
    private int shadeOf(long cross, int divisor) {
        int d1 = divisor == 0 ? 0 : (int) ((cross << 3) / divisor);
        d1 = M68k.w(M68k.w(M68k.w(-d1) + 14) + objBright);
        if (d1 < 0) {
            d1 = 0;
        }
        if (d1 >= SCALE_COLS.length) {
            d1 = SCALE_COLS.length - 1;
        }
        return (SCALE_COLS[d1] << 3) / 2;         // asl.w #3, then words not bytes
    }

    /**
     * {@code putinlines}: every edge, walked across the columns it spans.
     *
     * An edge going right fills the top table, one going left the bottom -- the
     * routine swaps its ends and changes table rather than testing which way
     * round the face was wound. A vertical edge fills nothing, which is why a
     * face needs no special case for its flat sides.
     */
    private void edges(VectorModel.Polygon f, int points) {
        int[] p = f.point();
        int[] t = f.texel();
        for (int e = 0; e < f.edges() + 1 && e + 1 < p.length; e++) {
            int a = p[e], b = p[e + 1];
            if (a < 0 || a >= points || b < 0 || b >= points) {
                continue;
            }
            int ax = scrX[a], ay = scrY[a];
            int bx = scrX[b], by = scrY[b];
            int au = (t[e] >> 8) & 0xff, av = t[e] & 0xff;
            int bu = (t[e + 1] >> 8) & 0xff, bv = t[e + 1] & 0xff;

            boolean top = bx > ax;                // cmp.w d2,d4 / bgt thislineontop
            if (bx == ax) {
                continue;                         // beq thislineflat
            }
            if (!top) {                           // exg: walk it left to right
                int tx = ax; ax = bx; bx = tx;
                int ty = ay; ay = by; by = ty;
                int tu = au; au = bu; bu = tu;
                int tv = av; av = bv; bv = tv;
            }
            walk(top, ax, ay, au, av, bx, by, bu, bv);
        }
    }

    /** One edge into one of the two tables, a column at a time. */
    private void walk(boolean top, int ax, int ay, int au, int av,
                      int bx, int by, int bu, int bv) {
        if (ax >= s.rightClip || bx <= s.leftClip) {
            return;
        }
        int span = bx - ax;
        if (span <= 0) {
            return;
        }
        int dy = ((by - ay) << 16) / span;
        int du = ((bu - au) << 16) / span;
        int dv = ((bv - av) << 16) / span;

        int from = ax;
        int y = ay << 16, u = au << 16, v = av << 16;
        if (from < s.leftClip) {                  // offleftby: step past, do not draw
            int skip = s.leftClip - from;
            y += dy * skip;
            u += du * skip;
            v += dv * skip;
            from = s.leftClip;
        }
        int to = Math.min(bx, s.rightClip);
        if (to <= from) {
            return;
        }

        anyEdge = true;                           // st drawit
        if (from < left) {
            left = from;
        }
        if (to - 1 > right) {
            right = to - 1;
        }

        int[] rowTab = top ? topAt : bottomAt;
        int[] uTab = top ? topU : bottomU;
        int[] vTab = top ? topV : bottomV;
        for (int col = from; col < to; col++) {
            if (col >= 0 && col < COLUMNS) {
                rowTab[col] = y >> 16;
                uTab[col] = u >> 16;
                vTab[col] = v >> 16;
            }
            y += dy;
            u += du;
            v += dv;
        }
    }

    /**
     * {@code dopoly} and {@code drawpol}: down each column, a texel a row.
     *
     * The texture is sixty-four by sixty-four and its two coordinates share one
     * longword, a byte apart, so a single {@code addx.l} steps both and the mask
     * {@code $3f3f} wraps both. This keeps that shape rather than two counters,
     * because the wrap it gives is the wrap the original has.
     */
    private void fill(VectorModel.Polygon f, int lo, int hi, int shade) {
        int texture = f.textureAt();
        for (int col = lo; col < hi; col++) {
            int y0 = topAt[col];
            int y1 = bottomAt[col];
            if (y1 <= y0) {
                continue;
            }
            int rows = y1 - y0;

            int u = topU[col] << 16;
            int v = topV[col] << 16;
            int du = ((bottomU[col] - topU[col]) << 16) / rows;
            int dv = ((bottomV[col] - topV[col]) << 16) / rows;

            int from = y0;
            if (from < objClipT) {                // clipped off the top
                int skip = objClipT - from;
                u += du * skip;
                v += dv * skip;
                from = objClipT;
            }
            int to = Math.min(y1, objClipB);
            if (to <= from || col >= objIntoCop.length) {
                continue;
            }

            int screen = (objIntoCop[col] & 0xffff) / 2 + from * EngineState.ROW_WORDS;
            for (int row = from; row < to; row++) {
                int texel = (((v >> 16) << 8) | ((u >> 16) & 0xff)) & TEXEL_MASK;
                int at = texture + texel * 4;
                if (at >= 0 && at < textureMaps.length
                        && screen >= 0 && screen < s.screen.length) {
                    int colour = shade + (textureMaps[at] & 0xff);
                    if (colour >= 0 && colour < texturePal.length) {
                        s.screen[screen] = texturePal[colour];
                        pixelsWritten++;
                    }
                }
                screen += EngineState.ROW_WORDS;
                u += du;
                v += dv;
            }
        }
    }

    private boolean reject(String why) {
        rejectedBy = why;
        return false;
    }

    /** Whether a slot has a model at all, for the checks. */
    public boolean has(int slot) {
        return slot >= 0 && slot < models.length && models[slot] != null;
    }
}
