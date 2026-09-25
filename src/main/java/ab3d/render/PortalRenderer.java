package ab3d.render;

import ab3d.data.FloorLine;
import ab3d.data.FloorTexture;
import ab3d.data.GameObject;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.SpriteBank;
import ab3d.data.SpriteSheet;
import ab3d.data.WadTexture;
import ab3d.data.WallTextures;
import ab3d.data.Zone;
import ab3d.data.ZoneGraph;
import ab3d.game.Camera;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Portal renderer, following the original's geometry.
 *
 * The engine projects into a 96 x 80 buffer that is doubled horizontally on its
 * way to the screen, with a focal length of 64 buffer columns and centres at
 * {@code add.w #47,d2} and {@code add.w #40,d5}. Working in displayed pixels
 * instead makes that a focal length of 128 and centres of (94, 40), and the
 * {@link Framebuffer} scale multiplies all four together so the field of view
 * never changes:
 *
 * <pre>
 *   X' = (dx*cos - dz*sin) / 32768      Z' = (dx*sin + dz*cos) / 32768
 *   screenX = focal * X' / Z' + centreX
 *   screenY = (worldY - yoff) * focal / (256 * Z') + centreY
 * </pre>
 *
 * The 256 is the number of Y units in one level unit, which is what makes the
 * two axes agree.
 *
 * Zones are visited nearest first, ordered by the depth of the portal that leads
 * to them. Occlusion uses per-column top/bottom clip spans -- the same idea as
 * {@code deftopclip} / {@code defbotclip} -- and those are only correct if
 * nearer geometry really is drawn first. Visiting in discovery order instead
 * lets the ordering flip as the camera moves, which shows up as flicker.
 */
public final class PortalRenderer {

    /** Nearest Z' we will project; closer than this the divide blows up. */
    private static final double NEAR = 4.0;
    /** Safety valve so a pathological portal graph cannot spin forever. */
    private static final int MAX_VISITS = 4000;
    /** Y units in one level unit. */
    private static final double Y_PER_UNIT = 256.0;
    /**
     * Base shift from level units to floor texels, matching the walls' one texel
     * per two units. A surface's own {@code scale} field adjusts it.
     */
    private static final int FLOOR_SHIFT = 1;

    /** Set to trace sprite placement while the billboard path is tuned. */
    public static boolean DEBUG = Boolean.getBoolean("ab3d.debugSprites");
    /** Layer toggles, for isolating an artifact to walls, planes or sprites. */
    public static final boolean NO_PLANES = Boolean.getBoolean("ab3d.noPlanes");
    public static final boolean NO_SPRITES = Boolean.getBoolean("ab3d.noSprites");
    public static final boolean NO_WALLS = Boolean.getBoolean("ab3d.noWalls");

    private final Level level;
    private final SineTable sine;
    private final WallTextures wallTextures;
    private final FloorTexture floorTexture;
    private final SpriteBank spriteBank;

    private final int width, height;
    private final double focal, centreX, centreY, vscale;
    private final int pixelScale;

    private final int[] topClip;
    private final int[] botClip;
    private final double[] invZ;
    private final double[] uAt;
    /**
     * Clip spans as they stood when the current zone was entered. Objects are
     * clipped against these rather than the live spans: a zone's own solid walls
     * close their columns outright, and an object standing in the zone is in
     * front of those walls, not behind them.
     */
    private final int[] objTop;
    private final int[] objBot;

    /**
     * Which columns each zone has already been drawn into, stamped with the
     * frame number so nothing has to be cleared between frames. Tracking a
     * min/max range instead would swallow a visit whose columns fall in a gap
     * between two earlier ones, leaving that part of the view undrawn.
     */
    private final int[][] zoneStamp;
    private int frameStamp;

    private final ZoneGraph.Surface[] floorSurface;
    private final ZoneGraph.Surface[] roofSurface;
    private final boolean[] surfacesResolved;

    public int lastVisitCount;
    public int lastWallCount;
    public int lastSpriteCount, lastSpritePixels;

    private double sin, cos;

    public PortalRenderer(Level level, SineTable sine, WallTextures wallTextures,
                          FloorTexture floorTexture, SpriteBank spriteBank,
                          Framebuffer fb) {
        this.level = level;
        this.sine = sine;
        this.wallTextures = wallTextures;
        this.floorTexture = floorTexture;
        this.spriteBank = spriteBank;

        this.width = fb.width;
        this.height = fb.height;
        this.focal = fb.focal;
        this.centreX = fb.centreX;
        this.centreY = fb.centreY;
        this.pixelScale = fb.scale;
        this.vscale = fb.focal / Y_PER_UNIT;

        this.topClip = new int[width];
        this.botClip = new int[width];
        this.invZ = new double[width];
        this.uAt = new double[width];
        this.objTop = new int[width];
        this.objBot = new int[width];
        this.zoneStamp = new int[level.zones.length][width];

        int n = level.zones.length;
        this.floorSurface = new ZoneGraph.Surface[n];
        this.roofSurface = new ZoneGraph.Surface[n];
        this.surfacesResolved = new boolean[n];
    }

    /** A zone to draw, the columns it may use, and how far away it starts. */
    private record Visit(int zone, int xl, int xr, double depth) {}

    /** A wall segment already projected into camera space. */
    private record Projected(double sx1, double sx2, double invZ1, double invZ2,
                             double near) {}

    public void render(Framebuffer fb, Camera cam) {
        fb.clear(0x000);
        java.util.Arrays.fill(topClip, 0);
        java.util.Arrays.fill(botClip, height);
        frameStamp++;

        sin = cam.sin(sine) / 32768.0;
        cos = cam.cos(sine) / 32768.0;

        PriorityQueue<Visit> queue =
                new PriorityQueue<>(Comparator.comparingDouble(Visit::depth));
        queue.add(new Visit(cam.zone, 0, width, 0));

        int visits = 0, walls = 0;
        lastSpriteCount = 0;
        lastSpritePixels = 0;

        while (!queue.isEmpty() && visits < MAX_VISITS) {
            Visit v = queue.poll();
            if (v.xl >= v.xr) {
                continue;
            }
            // A zone reached through two portals would otherwise be drawn twice,
            // and the second pass paints over the first with different clips.
            // Skip only when every column of this visit has already been covered.
            int[] stamp = zoneStamp[v.zone];
            boolean anyNew = false;
            for (int x = v.xl; x < v.xr; x++) {
                if (stamp[x] != frameStamp) {
                    anyNew = true;
                    break;
                }
            }
            if (!anyNew) {
                continue;
            }
            for (int x = v.xl; x < v.xr; x++) {
                stamp[x] = frameStamp;
            }

            visits++;
            Zone zone = level.zone(v.zone);
            resolveSurfaces(v.zone);
            ZoneGraph graph = level.graphics.lowerGraph(v.zone);

            System.arraycopy(topClip, 0, objTop, 0, width);
            System.arraycopy(botClip, 0, objBot, 0, width);

            if (graph != null) {
                for (ZoneGraph.Wall w : graph.walls) {
                    if (drawGraphWall(fb, cam, zone, w, v)) {
                        walls++;
                    }
                }
            }

            drawObjects(fb, cam, v);

            for (int lineIndex : zone.exitLines) {
                FloorLine fl = level.floorLine(lineIndex);
                if (fl.isSolid()) {
                    continue;
                }
                Projected p = project(fl.x1 - cam.x, fl.z1 - cam.z,
                                      fl.x2() - cam.x, fl.z2() - cam.z);
                if (p == null) {
                    continue;
                }
                int x0 = Math.max(v.xl, (int) Math.ceil(p.sx1));
                int x1 = Math.min(v.xr, (int) Math.ceil(p.sx2));
                if (x0 < x1) {
                    queue.add(new Visit(fl.toZone, x0, x1, p.near));
                }
            }
        }
        lastVisitCount = visits;
        lastWallCount = walls;
    }

    private void resolveSurfaces(int zone) {
        if (surfacesResolved[zone]) {
            return;
        }
        surfacesResolved[zone] = true;
        ZoneGraph g = level.graphics.lowerGraph(zone);
        if (g == null) {
            return;
        }
        for (ZoneGraph.Surface sf : g.surfaces) {
            if (sf.isRoof() && roofSurface[zone] == null) {
                roofSurface[zone] = sf;
            } else if (sf.isFloor() && floorSurface[zone] == null) {
                floorSurface[zone] = sf;
            }
        }
    }

    // ---- walls ---------------------------------------------------------------

    private boolean drawGraphWall(Framebuffer fb, Camera cam, Zone zone,
                                  ZoneGraph.Wall w, Visit v) {
        if (w.pointA() >= level.numPoints || w.pointB() >= level.numPoints) {
            return false;
        }
        Projected p = project(
                level.pointX[w.pointA()] - cam.x, level.pointZ[w.pointA()] - cam.z,
                level.pointX[w.pointB()] - cam.x, level.pointZ[w.pointB()] - cam.z);
        if (p == null) {
            return false;
        }

        int x0 = Math.max(v.xl, (int) Math.ceil(p.sx1));
        int x1 = Math.min(v.xr, (int) Math.ceil(p.sx2));
        if (x0 >= x1) {
            return false;
        }

        double span = p.sx2 - p.sx1;
        if (DEBUG) {
            System.err.printf(
                    "    wall zone=%3d tex=%2d cols=[%3d,%3d) near=%7.1f "
                    + "top=%8d bot=%8d zroof=%8d zfloor=%8d see=%b%n",
                    zone.index, w.texture(), x0, x1, p.near,
                    w.top(), w.bottom(), zone.roofHeight, zone.floorHeight,
                    w.seeThrough());
        }
        double uz1 = w.uLeft() * p.invZ1, uz2 = w.uRight() * p.invZ2;
        for (int x = x0; x < x1; x++) {
            double t = (x + 0.5 - p.sx1) / span;
            double iz = p.invZ1 + (p.invZ2 - p.invZ1) * t;
            invZ[x] = iz;
            uAt[x] = (uz1 + (uz2 - uz1) * t) / iz;
        }

        WadTexture tex = wallTextures.get(w.texture());
        int texHeight = w.textureHeight();
        if (tex == null || texHeight <= 0 || tex.stripCount(texHeight) <= 0) {
            tex = null;
        }
        boolean occludes = !w.seeThrough();

        for (int x = x0; x < x1; x++) {
            int top = topClip[x];
            int bot = botClip[x];
            if (top >= bot) {
                continue;
            }

            double iz = invZ[x] * vscale;
            int wallTop = clamp(row((w.top() - cam.yoff) * iz), top, bot);
            int wallBot = clamp(row((w.bottom() - cam.yoff) * iz), top, bot);
            int zoneRoof = clamp(row((zone.roofHeight - cam.yoff) * iz), top, bot);
            int zoneFloor = clamp(row((zone.floorHeight - cam.yoff) * iz), top, bot);

            if (!NO_PLANES) {
                if (zoneRoof > top) {
                    drawPlane(fb, cam, x, top, zoneRoof, zone.roofHeight,
                            roofSurface[zone.index]);
                }
                if (bot > zoneFloor) {
                    drawPlane(fb, cam, x, zoneFloor, bot, zone.floorHeight,
                            floorSurface[zone.index]);
                }
            }

            if (wallBot > wallTop && !NO_WALLS) {
                if (tex != null) {
                    drawTexturedColumn(fb, cam, tex, w, texHeight, x, wallTop, wallBot,
                            1.0 / invZ[x], uAt[x]);
                } else {
                    fb.column(x, wallTop, wallBot,
                            Framebuffer.shade(Framebuffer.rgb12(11, 11, 11),
                                    flatShadeFor(1.0 / invZ[x]), 16));
                }
                if (occludes) {
                    if (wallTop <= zoneRoof) {
                        topClip[x] = Math.max(topClip[x], wallBot);
                    }
                    if (wallBot >= zoneFloor) {
                        botClip[x] = Math.min(botClip[x], wallTop);
                    }
                    if (topClip[x] > botClip[x]) {
                        topClip[x] = botClip[x];
                    }
                }
            }
        }
        return true;
    }

    private void drawTexturedColumn(Framebuffer fb, Camera cam, WadTexture tex,
                                    ZoneGraph.Wall w, int texHeight,
                                    int x, int yTop, int yBot, double z, double u) {
        int uInt = (int) Math.floor(u);
        int strip = WadTexture.stripFor(uInt, w.uMask(), w.tile());
        int sub = WadTexture.subPixelFor(uInt, w.uMask(), w.tile());
        int strips = tex.stripCount(texHeight);
        if (strips <= 0) {
            return;
        }
        strip = Math.floorMod(strip, strips);

        int shadeRow = shadeRowFor(z);
        double worldY = (yTop - centreY) * z / vscale + cam.yoff;
        double step = z / vscale;

        for (int y = yTop; y < yBot; y++, worldY += step) {
            int v = Math.floorDiv((int) Math.floor(worldY), WallTextures.Y_PER_TEXEL)
                    + w.vOffset();
            int texel = tex.texel(strip, Math.floorMod(v, texHeight), texHeight, sub);
            fb.set(x, y, tex.colour(shadeRow, texel));
        }
    }

    // ---- floors and ceilings -------------------------------------------------

    /**
     * Fills part of a column with a horizontal plane. Inverting the vertical
     * projection gives one depth per screen row, and the world position of a
     * pixel then follows by rotating the camera-space offset back.
     */
    private void drawPlane(Framebuffer fb, Camera cam, int x, int y0, int y1,
                           int planeY, ZoneGraph.Surface surface) {
        int tile = surface == null ? 0 : surface.tile();
        // The surface's `scale` field is the texture zoom, applied by
        // source/master.s to the world coordinates before sampling. Feeding it
        // in directly makes the textures far too large, so the relation is not
        // the one it looks like and the fixed shift stands until it is derived.
        double xp0 = (x + 0.5 - centreX) / focal;

        for (int y = y0; y < y1; y++) {
            double dy = y - centreY;
            if (dy == 0) {
                continue;
            }
            double z = (planeY - cam.yoff) * vscale / dy;
            if (z <= NEAR) {
                continue;
            }
            double xp = xp0 * z;
            double wx = cam.x + xp * cos + z * sin;
            double wz = cam.z - xp * sin + z * cos;

            int u = ((int) Math.floor(wx)) >> FLOOR_SHIFT;
            int vv = ((int) Math.floor(wz)) >> FLOOR_SHIFT;
            fb.set(x, y, floorTexture.colour(floorShadeFor(z),
                    floorTexture.index(u, vv, tile)));
        }
    }

    // ---- objects -------------------------------------------------------------

    private void drawObjects(Framebuffer fb, Camera cam, Visit v) {
        if (spriteBank == null || NO_SPRITES) {
            return;
        }
        List<GameObject> here = new ArrayList<>();
        for (GameObject o : level.objects) {
            if (o.zone == v.zone && o.isSprite()
                    && o.pointIndex < level.objectPointX.length) {
                here.add(o);
            }
        }
        if (here.isEmpty()) {
            return;
        }
        here.sort((a, b) -> Double.compare(depthOf(cam, b), depthOf(cam, a)));
        for (GameObject o : here) {
            if (drawSprite(fb, cam, o, v)) {
                lastSpriteCount++;
            }
        }
    }

    private double depthOf(Camera cam, GameObject o) {
        double dx = level.objectPointX[o.pointIndex] - cam.x;
        double dz = level.objectPointZ[o.pointIndex] - cam.z;
        return dx * sin + dz * cos;
    }

    /**
     * Draws one object as a billboard, with the placement BitMapObj computes:
     * half sizes scale as {@code worldSize * 128 / dist} in the engine's own
     * distance unit, and the centre comes from the same projection as the walls.
     */
    private boolean drawSprite(Framebuffer fb, Camera cam, GameObject o, Visit v) {
        SpriteSheet sheet = spriteBank.sheet(o.slot);
        if (sheet == null || o.spriteWidth <= 0 || o.spriteHeight <= 0) {
            return false;
        }
        double ox = level.objectPointX[o.pointIndex] - cam.x;
        double oz = level.objectPointZ[o.pointIndex] - cam.z;
        double zp = ox * sin + oz * cos;
        if (zp <= NEAR) {
            return false;
        }
        double xp = ox * cos - oz * sin;
        double dist = 2.0 * zp;              // the engine's own distance unit
        if (dist <= 50) {
            return false;
        }

        int objY = o.height << 7;
        int centreXpx = (int) Math.round(focal * xp / zp + centreX);
        int halfW = (int) (o.widthScale * 256.0 * pixelScale / dist);
        int halfH = (int) (o.heightScale * 128.0 * pixelScale / dist);
        if (halfW <= 0 || halfH <= 0) {
            return false;
        }
        int centreYpx = (int) Math.round((objY - cam.yoff) * vscale / zp
                + centreY - pixelScale);

        int left = centreXpx - halfW, widthPx = halfW * 2;
        int top = centreYpx - halfH, heightPx = halfH * 2;

        SpriteBank.Frame frame = spriteBank.frame(o.slot, o.frame);
        int columnBase = frame.ptrOffset() / 4;
        int columns = spriteBank.frameColumns(o.slot, o.spriteWidth);
        int shade = spriteShade(dist, o.brightness);

        if (DEBUG) {
            Zone z = level.zone(o.zone);
            System.err.printf(
                    "    obj%-3d slot=%d dist=%6.0f objY=%8d floor=%8d eye=%8d"
                    + " worldH=%6d top=%5d bot=%5d halfH=%4d%n",
                    o.index, o.slot, dist, objY, z.floorHeight, cam.yoff,
                    o.heightScale * 256, top, top + heightPx, halfH);
        }

        boolean drew = false;
        int x0 = Math.max(v.xl, Math.max(0, left));
        int x1 = Math.min(v.xr, Math.min(width, left + widthPx));
        for (int x = x0; x < x1; x++) {
            int yTop = Math.max(objTop[x], top);
            int yBot = Math.min(objBot[x], top + heightPx);
            if (yTop >= yBot) {
                continue;
            }
            int u = (x - left) * columns / widthPx;
            for (int y = yTop; y < yBot; y++) {
                int vv = (y - top) * o.spriteHeight / heightPx + frame.downStrip();
                int pixel = sheet.pixel(columnBase + u, vv);
                if (pixel == SpriteSheet.TRANSPARENT) {
                    continue;
                }
                int rgb = sheet.colour(shade, pixel);
                if (rgb >= 0) {
                    fb.set(x, y, rgb);
                    lastSpritePixels++;
                    drew = true;
                }
            }
        }
        return drew;
    }

    /**
     * Palette row for a sprite, following the engine's objscalecols table: the
     * first two steps share the brightest row, then rows advance every four
     * steps, and everything beyond sits on the darkest.
     */
    private static int spriteShade(double dist, int brightness) {
        int i = (int) (dist / 128.0) + brightness;
        if (i < 2) {
            return 0;
        }
        if (i < 54) {
            return 1 + (i - 2) / 4;
        }
        return SpriteSheet.SHADES - 1;
    }

    // ---- shared --------------------------------------------------------------

    /**
     * Projects a segment given in camera-relative level coordinates.
     * Returns null when the segment is behind us or facing away.
     */
    private Projected project(double ax, double az, double bx, double bz) {
        double ar = ax * cos - az * sin, ad = ax * sin + az * cos;
        double br = bx * cos - bz * sin, bd = bx * sin + bz * cos;

        if (ad < NEAR && bd < NEAR) {
            return null;
        }
        if (ad < NEAR) {
            double t = (NEAR - ad) / (bd - ad);
            ar += (br - ar) * t;
            ad = NEAR;
        } else if (bd < NEAR) {
            double t = (NEAR - bd) / (ad - bd);
            br += (ar - br) * t;
            bd = NEAR;
        }

        double sx1 = focal * ar / ad + centreX;
        double sx2 = focal * br / bd + centreX;
        if (sx1 >= sx2) {
            return null;
        }
        return new Projected(sx1, sx2, 1.0 / ad, 1.0 / bd, Math.min(ad, bd));
    }

    private int row(double y) {
        double r = y + centreY;
        if (r < -1e6) {
            return -1000000;
        }
        if (r > 1e6) {
            return 1000000;
        }
        return (int) Math.round(r);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int flatShadeFor(double z) {
        int s = (int) (16 * 2000.0 / (2000.0 + z));
        return Math.max(6, Math.min(16, s));
    }

    private static int floorShadeFor(double z) {
        int row = (int) (z / 220.0);
        return row < 0 ? 0 : Math.min(row, FloorTexture.SHADES - 1);
    }

    /** Palette row for a wall texture at a given depth; row 0 is the brightest. */
    private static int shadeRowFor(double z) {
        int row = (int) (z / 90.0);
        return row < 0 ? 0 : Math.min(row, WadTexture.SHADES - 1);
    }
}
