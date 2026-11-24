package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;
import ab3d.engine.Obj;
import ab3d.game.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * A picture of one placed thing, after the world has run a while around it.
 *
 * Arguments: level, type byte, frames to run first, output directory, and
 * optionally which of that type (nought for the first). The eye stands at the
 * middle of the thing's zone, facing it -- so the routine sees the player and
 * does what it does when it does, and the frame shows the result through the
 * transcribed drawing chain.
 */
public final class EnemyShot {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String name = args[0];
        int type = Integer.parseInt(args[1]);
        int run = Integer.parseInt(args[2]);
        File out = new File(args.length > 3 ? args[3] : "build/frames");
        int which = args.length > 4 ? Integer.parseInt(args[4]) : 0;
        out.mkdirs();

        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        int base = -1;
        for (int i = 0, seen = 0; i < lv.objects.size(); i++) {
            int b = lv.ptrObjects + i * GameObject.SIZE;
            if (lv.data.s16(b) < 0) {
                break;
            }
            if (lv.data.s8(b + Obj.TYPE) == type && lv.data.s16(b + Obj.ZONE) >= 0
                    && seen++ == which) {
                base = b;
                break;
            }
        }
        if (base < 0) {
            System.out.println("no type " + type + " in " + name);
            return;
        }
        int zone = lv.data.s16(base + Obj.ZONE);
        Zone z = lv.zone(zone);
        long sx = 0, sz = 0;
        for (int e : z.exitLines) {
            sx += lv.floorLine(e).x1;
            sz += lv.floorLine(e).z1;
        }
        int x = (int) (sx / z.exitLines.length);
        int zz = (int) (sz / z.exitLines.length);
        int eye = z.floorHeight - Player.EYE_HEIGHT;
        // -Dab3d.eye=dx,dz: stand that far from the thing instead, in its zone
        String off = System.getProperty("ab3d.eye");
        if (off != null) {
            int p0 = lv.data.s16(base + Obj.POINT);
            x = lv.objectPointX[p0] + Integer.parseInt(off.split(",")[0].trim());
            zz = lv.objectPointZ[p0] + Integer.parseInt(off.split(",")[1].trim());
        }

        for (int t = 0; t < run; t++) {
            int pt = lv.data.s16(base + Obj.POINT);
            int a = angleTo(x, zz, lv.objectPointX[pt], lv.objectPointZ[pt]);
            frame.armObjects(zone);
            frame.updateObjects(zone, x, zz, eye, false, 1, a);
        }
        int pt = lv.data.s16(base + Obj.POINT);
        int ex = lv.objectPointX[pt], ez = lv.objectPointZ[pt];
        int a = angleTo(x, zz, ex, ez);
        frame.render(zone, x, zz, eye, a);
        File f = new File(out, name + "_type" + type + "_" + which + ".png");
        ImageIO.write(toImage(frame.state(), 3), "png", f);
        System.out.printf("%s type %d: zone %d, eye (%d,%d) facing %d, thing at "
                          + "(%d,%d) slot %d frame %d, lives %d; %d sprites drawn -> %s%n",
                          name, type, zone, x, zz, a, ex, ez,
                          lv.data.s16(base + Obj.SLOT), lv.data.s16(base + Obj.FRAME),
                          lv.data.u8(base + Obj.NUM_LIVES), frame.spritesDrawn, f);
    }

    private static int angleTo(int fromX, int fromZ, int toX, int toZ) {
        double a = Math.atan2(toX - fromX, toZ - fromZ);
        return ((int) Math.round(a / (2 * Math.PI) * 4096)) & 4095;
    }

    private static BufferedImage toImage(EngineState st, int scale) {
        int sx = scale * 2;
        BufferedImage img = new BufferedImage(EngineState.VIEW_COLUMNS * sx,
                                              EngineState.VIEW_ROWS * scale,
                                              BufferedImage.TYPE_INT_RGB);
        for (int r = 0; r < EngineState.VIEW_ROWS; r++) {
            for (int c = 0; c < EngineState.VIEW_COLUMNS; c++) {
                int v = st.screen[st.rowStart(r) + EngineState.columnWord(c)] & 0x0fff;
                int rr = (v >> 8) & 0xf, g = (v >> 4) & 0xf, b = v & 0xf;
                int rgb = ((rr | (rr << 4)) << 16) | ((g | (g << 4)) << 8) | (b | (b << 4));
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < sx; dx++) {
                        img.setRGB(c * sx + dx, r * scale + dy, rgb);
                    }
                }
            }
        }
        return img;
    }
}
