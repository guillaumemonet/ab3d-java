package ab3d.data;

import java.io.IOException;

/**
 * One of the ten models {@code POLYOBJECTS} names.
 *
 * Most things in this game are sprites, but an object whose width scale is
 * {@code $ff} is not one: {@code cmp.b #$ff,6(a0) / bne BitMapObj} sends it to a
 * second renderer that turns a little model. The levels place a hundred and
 * thirty-nine of them -- the exit signs, the four coloured key indicators beside
 * the doors they open, and the gas pipes.
 *
 * <h2>The file</h2>
 *
 * Read straight off the assembly that walks it:
 *
 * <pre>
 *   word   number of points
 *   word   number of frames
 *   word * frames      where each frame's points start, from the file's own head
 *   ...    the parts, then the polygons, then the points
 * </pre>
 *
 * A part is a pair of words -- where its polygons start, and a point that stands
 * for it -- ending on a negative. Parts are drawn back to front by the squared
 * distance of that point, which is the whole of the hidden surface removal.
 *
 * A polygon is {@code 18 + 4n} bytes:
 *
 * <pre>
 *   word   n, the edges to draw, or negative to end the part
 *   word   whether it has holes in it
 *   word * 2 * (n + 2)   point number and texture position, the last repeating
 *                        the first so the edge walk closes
 *   word   where in {@code Texturemaps} its texture is
 *   word   what its brightness is divided by
 *   word   whether it is gouraud shaded
 * </pre>
 *
 * A point is three words, and the renderer doubles each before it rotates.
 */
public final class VectorModel {

    /** {@code POLYOBJECTS}, in its own order: the slot an object names. */
    public static final String[] SLOTS = {
        "robot", "medipac", "exitsign", "crate", "terminal",
        "blueind", "greenind", "redind", "yellowind", "gaspipe",
    };

    /** A point of one frame, as the file holds it, before the doubling. */
    public record Point(int x, int y, int z) {}

    /** One face. {@code point} and {@code texel} run together, {@code n + 2} long. */
    public record Polygon(int edges, boolean holes, int[] point, int[] texel,
                          int textureAt, int divisor, boolean gouraud) {}

    /** One part: its faces, and the point whose depth sorts it. */
    public record Part(Polygon[] faces, int pointIndex) {}

    private final String name;
    private final int pointCount;
    private final Point[][] frames;
    private final Part[] parts;

    public String name() {
        return name;
    }

    public int pointCount() {
        return pointCount;
    }

    public int frameCount() {
        return frames.length;
    }

    /** The points of one frame, clamped to the frames there are. */
    public Point[] frame(int i) {
        return frames[Math.max(0, Math.min(i, frames.length - 1))];
    }

    public Part[] parts() {
        return parts;
    }

    /** Reads a model by its slot number, from the disks or from this build. */
    public static VectorModel load(GameData game, int slot) throws IOException {
        if (slot < 0 || slot >= SLOTS.length) {
            throw new IOException("no model in slot " + slot);
        }
        return new VectorModel(SLOTS[slot], game.vector(SLOTS[slot] + ".vec"));
    }

    VectorModel(String name, byte[] d) throws IOException {
        this.name = name;
        this.pointCount = u16(d, 0);
        int frameCount = u16(d, 2);
        if (pointCount <= 0 || frameCount <= 0
                || 4 + frameCount * 2 > d.length) {
            throw new IOException(name + ": " + pointCount + " points and "
                                  + frameCount + " frames is not a model");
        }

        this.frames = new Point[frameCount][];
        for (int f = 0; f < frameCount; f++) {
            int at = u16(d, 4 + f * 2);
            Point[] pts = new Point[pointCount];
            for (int i = 0; i < pointCount; i++) {
                int o = at + i * 6;
                pts[i] = new Point(s16(d, o), s16(d, o + 2), s16(d, o + 4));
            }
            frames[f] = pts;
        }

        // the part list sits straight after the frame offsets
        int at = 4 + frameCount * 2;
        java.util.List<Part> found = new java.util.ArrayList<>();
        while (at + 4 <= d.length && s16(d, at) >= 0) {
            found.add(new Part(polygons(d, s16(d, at)), s16(d, at + 2) / 10));
            at += 4;
        }
        this.parts = found.toArray(new Part[0]);
    }

    /** The faces of one part, up to the negative count that ends them. */
    private static Polygon[] polygons(byte[] d, int at) throws IOException {
        java.util.List<Polygon> out = new java.util.ArrayList<>();
        while (at + 2 <= d.length) {
            int edges = s16(d, at);
            if (edges < 0) {
                break;
            }
            // tst.w (a1) / blt: only a negative ends the part. Nought edges is
            // a face the editor left behind, and the original walks past it.
            int size = 18 + edges * 4;
            if (at + size > d.length) {
                throw new IOException("a face of " + edges + " edges at " + at
                                      + " runs past the end of the file");
            }
            int vertices = edges + 2;
            int[] point = new int[vertices];
            int[] texel = new int[vertices];
            for (int i = 0; i < vertices; i++) {
                point[i] = s16(d, at + 4 + i * 4);
                texel[i] = u16(d, at + 6 + i * 4);
            }
            out.add(new Polygon(edges, s16(d, at + 2) != 0, point, texel,
                                u16(d, at + 4 + edges * 4 + 8),
                                s16(d, at + 4 + edges * 4 + 10),
                                s16(d, at + 4 + edges * 4 + 12) != 0));
            at += size;
        }
        return out.toArray(new Polygon[0]);
    }

    private static int u16(byte[] d, int o) {
        return ((d[o] & 0xff) << 8) | (d[o + 1] & 0xff);
    }

    private static int s16(byte[] d, int o) {
        return (short) u16(d, o);
    }
}
