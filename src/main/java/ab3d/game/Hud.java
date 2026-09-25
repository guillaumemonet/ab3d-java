package ab3d.game;

import ab3d.data.Border;
import ab3d.data.GameData;
import ab3d.data.Panel;

import java.io.IOException;

/**
 * The status panel and everything drawn into it.
 *
 * The panel is a picture three hundred and twenty across and ninety-six deep,
 * loaded by {@code LOADBOTPIC} and pointed at by the copper as the bottom of the
 * screen. The game draws into that same bitmap rather than over it: {@code
 * ItsAKey} ORs a key graphic straight into the planes at an offset {@code
 * OFFSETTOGRAPH} gives it, and the health and ammunition bars are strips copied
 * in the same way. So the panel is kept as two things -- the picture as loaded,
 * which nothing may change, and a working copy the routines write into.
 */
public final class Hud {

    public static final int WIDTH = Panel.WIDTH, HEIGHT = Panel.HEIGHT;

    /**
     * {@code OFFSETTOGRAPH}: where each of the four key graphics goes.
     *
     * The entries are byte offsets into the interleaved bitmap, of the form
     * {@code (40*8)*row+column}, so each one is a row and a byte across. Which
     * of the four a key uses is not stored on the key: {@code ItsAKey} shifts
     * the granted bits right until one falls out, and the number of shifts is
     * the index -- so the bit's position picks the colour, and green, red,
     * yellow and blue are bits zero to three in that order.
     */
    private static final int[][] KEY_AT = {
        {43, 10}, {11, 12}, {11, 22}, {43, 24},
    };

    private final Panel panel;
    /** The left and right borders, and the two bars drawn into them. */
    public final Border border;
    /** The four key icons, each forty-eight across and twenty-two deep. */
    private final byte[][] keyIcons;
    /** The planes as they stand, with whatever has been drawn into them. */
    private final byte[] planes;
    /** One byte a pixel, rebuilt whenever the planes change. */
    private final byte[] pixels = new byte[WIDTH * HEIGHT];
    private boolean dirty = true;
    private int keysShown;

    public Hud(GameData game) throws IOException {
        this.panel = Panel.load(game);
        this.border = Border.load(game);
        this.planes = panel.planes.clone();
        this.keyIcons = new byte[][]{
            read(game, "greenkey"), read(game, "redkey"),
            read(game, "yellowkey"), read(game, "bluekey"),
        };
    }

    private static byte[] read(GameData game, String name) throws IOException {
        return game.bytes(name);
    }

    /**
     * Shows the keys the player is carrying.
     *
     * {@code ItsAKey} draws its icon once, at the moment of the pickup, and the
     * panel keeps it because nothing ever clears those pixels. Here the whole
     * set is redrawn from {@code Conditions} instead, which comes to the same
     * thing while sparing the port a pickup event it would otherwise have to
     * route through to the display.
     */
    public void setKeys(int conditions) {
        int held = conditions & 0xf;
        if (held == keysShown) {
            return;
        }
        keysShown = held;
        System.arraycopy(panel.planes, 0, planes, 0, planes.length);
        for (int i = 0; i < KEY_AT.length; i++) {
            if ((held & (1 << i)) != 0) {
                blitKey(keyIcons[i], KEY_AT[i][0], KEY_AT[i][1]);
            }
        }
        dirty = true;
    }

    /**
     * {@code or.l d1,(a2) / or.w d1,4(a2) / adda.w #40,a2}.
     *
     * Six bytes a line for twenty-two lines of eight planes, ORed rather than
     * written -- the icon is drawn over the panel's own artwork, not in place of
     * it, so the socket it sits in still shows through.
     */
    private void blitKey(byte[] icon, int row, int col) {
        int at = row * Panel.ROW_BYTES + col;
        for (int line = 0; line < 22 * 8 && line * 6 + 6 <= icon.length; line++) {
            int to = at + line * 40;
            if (to + 6 > planes.length) {
                break;
            }
            for (int b = 0; b < 6; b++) {
                planes[to + b] |= icon[line * 6 + b];
            }
        }
    }

    /** {@code EnergyBar} and {@code AmmoBar}, called once a frame in jg.s. */
    public void setBars(int energy, int ammo) {
        border.setEnergy(energy);
        border.setAmmo(ammo);
    }

    /** The colour of one pixel of the panel, as 0x00RRGGBB. */
    public int[] palette() {
        return panel.palette;
    }

    /** The panel as colour indices, rebuilt only when something has changed. */
    public byte[] pixels() {
        if (dirty) {
            decode();
            dirty = false;
        }
        return pixels;
    }

    private void decode() {
        java.util.Arrays.fill(pixels, (byte) 0);
        for (int y = 0; y < HEIGHT; y++) {
            int row = y * Panel.ROW_BYTES;
            for (int p = 0; p < Panel.PLANES; p++) {
                int at = row + p * (WIDTH / 8);
                for (int b = 0; b < WIDTH / 8; b++) {
                    int bits = planes[at + b] & 0xff;
                    if (bits == 0) {
                        continue;
                    }
                    int x = b * 8;
                    for (int i = 0; i < 8; i++) {
                        if ((bits & (0x80 >> i)) != 0) {
                            pixels[y * WIDTH + x + i] |= (byte) (1 << p);
                        }
                    }
                }
            }
        }
    }
}
