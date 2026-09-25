package ab3d.tools;

import ab3d.data.FloorTexture;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.SpriteBank;
import ab3d.data.WallTextures;
import ab3d.data.Zone;
import ab3d.game.Player;
import ab3d.render.Framebuffer;
import ab3d.render.PortalRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Renders frames without opening a window, so the rasterizer can be checked on
 * its own. Writes one PNG per view angle.
 *
 * Arguments: level, output directory, upscale, first angle, start zone.
 * The internal resolution follows {@code -Dab3d.res=N}, as it does in the game.
 */
public final class RenderTool {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String name = args.length > 0 ? args[0] : "level_a";
        File outDir = new File(args.length > 1 ? args[1] : "build/frames");
        int upscale = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        int angle0 = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int startZone = args.length > 4 ? Integer.parseInt(args[4]) : -1;
        outDir.mkdirs();

        int res = Math.max(1, Math.min(8, Integer.getInteger("ab3d.res", 1)));
        Framebuffer fb = new Framebuffer(res);

        Level level = Level.load(game, name);
        SineTable sine = SineTable.load(game);
        PortalRenderer renderer = new PortalRenderer(level, sine,
                new WallTextures(game), FloorTexture.load(game),
                new SpriteBank(game), fb);
        Player player = new Player(level);

        if (startZone >= 0) {
            Zone z = level.zone(startZone);
            long sx = 0, sz = 0;
            for (int e : z.exitLines) {
                sx += level.floorLine(e).x1;
                sz += level.floorLine(e).z1;
            }
            player.camera.x = (int) (sx / z.exitLines.length);
            player.camera.z = (int) (sz / z.exitLines.length);
            player.camera.zone = startZone;
            player.camera.yoff = z.floorHeight - Player.EYE_HEIGHT;
        }

        // Reproduce one exact view, as the game's status line reports it
        String at = System.getProperty("ab3d.at");
        if (at != null) {
            String[] f = at.split(",");
            player.camera.x = Integer.parseInt(f[0].trim());
            player.camera.z = Integer.parseInt(f[1].trim());
            player.camera.angle = Integer.parseInt(f[2].trim());
            player.camera.zone = Integer.parseInt(f[3].trim());
            player.camera.yoff = level.zone(player.camera.zone).floorHeight
                    - Player.EYE_HEIGHT;
            renderer.render(fb, player.camera);
            byte[] one = new byte[fb.rgbaSize()];
            fb.toRgba(one);
            File out = new File(outDir, name + "-at.png");
            ImageIO.write(toImage(fb, one, upscale), "png", out);
            System.out.printf("exact view: zones %d walls %d sprites %d (%d px) -> %s%n",
                    renderer.lastVisitCount, renderer.lastWallCount,
                    renderer.lastSpriteCount, renderer.lastSpritePixels, out.getName());
            return;
        }

        byte[] rgba = new byte[fb.rgbaSize()];
        System.out.printf("%s at %dx%d (res %d)%n", name, fb.width, fb.height, res);

        for (int i = 0; i < 8; i++) {
            player.camera.angle = (angle0 + i * (SineTable.FULL / 8)) & (SineTable.FULL - 1);
            long t0 = System.nanoTime();
            renderer.render(fb, player.camera);
            long us = (System.nanoTime() - t0) / 1000;
            fb.toRgba(rgba);

            File out = new File(outDir, String.format("%s-a%d.png", name, player.camera.angle));
            ImageIO.write(toImage(fb, rgba, upscale), "png", out);
            System.out.printf("angle %4d  zones %3d  walls %4d  sprites %2d (%d px)  %6d us  -> %s%n",
                    player.camera.angle, renderer.lastVisitCount, renderer.lastWallCount,
                    renderer.lastSpriteCount, renderer.lastSpritePixels, us, out.getName());
        }
        System.out.printf("camera: zone %d at (%d, %d), eye %d%n",
                player.camera.zone, player.camera.x, player.camera.z, player.camera.yoff);
    }

    private static BufferedImage toImage(Framebuffer fb, byte[] rgba, int scale) {
        int w = fb.width, h = fb.height;
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                int rgb = ((rgba[i] & 0xff) << 16) | ((rgba[i + 1] & 0xff) << 8)
                        | (rgba[i + 2] & 0xff);
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        img.setRGB(x * scale + dx, y * scale + dy, rgb);
                    }
                }
            }
        }
        return img;
    }
}
