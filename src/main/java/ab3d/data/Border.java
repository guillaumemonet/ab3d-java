package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;

/**
 * The left and right screen borders, from includes/newleftbord and newrightbord.
 *
 * These are not part of the bitmap: jg.s hands them to the hardware sprites,
 * four of them, as two attached pairs. {@code move.w #52*256+64,borders} sets
 * the first pair's position and {@code #212*256} its end, so they run down a
 * hundred and sixty display lines from horizontal position sixty-four; the
 * second pair sits at a hundred and ninety-two. In screen terms that is
 * sixty-four pixels at each edge with the view's hundred and ninety-two between
 * them -- which is where the view's width comes from, ninety-six engine columns
 * doubled.
 *
 * Each file is two sprites of 2592 bytes: a sixteen-byte header, then a hundred
 * and sixty lines of sixteen, eight bytes of one plane and eight of the other at
 * sixty-four pixels across. Attached, the pair's four planes give sixteen
 * colours.
 *
 * The energy and ammunition bars are drawn into these, not into the status
 * panel: {@code EnergyBar} writes at {@code borders+25*16+6}, which is byte six
 * of line twenty-five of the left pair, and {@code AmmoBar} at
 * {@code borders+5184+25*16+1} in the right. Both are eight pixels wide and a
 * hundred and twenty-eight lines tall, and both drain from the top.
 */
public final class Border {

    /** {@code borders+2592}: one sprite, header included. */
    public static final int SPRITE_BYTES = 2592;
    /** {@code move.w #52*256+64} to {@code #212*256}. */
    public static final int LINES = 160;
    public static final int WIDTH = 64;
    /** Two eight-byte planes make one line. */
    public static final int LINE_BYTES = 16;
    /** The header the hardware reads before the first line. */
    public static final int HEADER = 16;

    /** {@code move.l #borders,a1 / add.l #25*8*2+6,a1}. */
    public static final int ENERGY_AT = 25 * 16 + 6;
    /** {@code move.l #borders+5184+25*16+1,a1}. */
    public static final int AMMO_AT = 25 * 16 + 1;
    /** {@code move.w #127,d0} and {@code cmp.w #63,Ammo}. */
    public static final int ENERGY_MAX = 127, AMMO_MAX = 63;

    /** The four sprites, in the order jg.s points s0 to s3 at them. */
    public final byte[] data;
    /** Sixteen colours, from includes/borderpal. */
    public final int[] palette;
    /** The decoded pairs, kept because they change only when a bar moves. */
    private final byte[][] cache = new byte[2][];
    private int shownEnergy = -1, shownAmmo = -1;

    /** includes/healthstrip: a hundred and twenty-eight lines of four bytes. */
    private final byte[] health;
    /** includes/ammostrip: sixty-four units of eight, two lines each. */
    private final byte[] ammo;

    private Border(byte[] data, int[] palette, byte[] health, byte[] ammo) {
        this.data = data;
        this.palette = palette;
        this.health = health;
        this.ammo = ammo;
    }

    /**
     * {@code FullEnergy}, then {@code LessEnergy} for what is missing.
     *
     * The strip holds the bar as it looks when full, four bytes a line -- one
     * for each of the pair's four planes, written at {@code (a1)}, {@code 8(a1)}
     * and the same two in the second sprite. Drawing it whole and then clearing
     * the top says the same thing as the original's two directions, and says it
     * without carrying {@code OldEnergy} between frames, which exists only so
     * the hardware is touched as little as possible.
     */
    public void setEnergy(int energy) {
        int level = Math.max(0, Math.min(ENERGY_MAX, energy));
        if (level == shownEnergy) {
            return;                          // cmp.w OldEnergy,d0 / bne
        }
        shownEnergy = level;
        cache[0] = null;
        for (int i = 0; i <= ENERGY_MAX; i++) {
            int a1 = ENERGY_AT + i * LINE_BYTES;
            int a2 = a1 + SPRITE_BYTES;
            boolean lit = i >= ENERGY_MAX - level;   // d3 = 127 - Energy
            data[a1] = lit ? health[i * 4] : 0;
            data[a1 + 8] = lit ? health[i * 4 + 1] : 0;
            data[a2] = lit ? health[i * 4 + 2] : 0;
            data[a2 + 8] = lit ? health[i * 4 + 3] : 0;
        }
    }

    /**
     * {@code AmmoBar}, which differs only in that one unit is two lines.
     *
     * {@code lea (a0,d3.w*8),a0} against {@code lsl.w #5,d3} -- eight bytes of
     * strip to thirty-two of sprite -- is what makes sixty-four rounds fill the
     * same hundred and twenty-eight lines the energy does.
     */
    public void setAmmo(int rounds) {
        int level = Math.max(0, Math.min(AMMO_MAX, rounds));
        if (level == shownAmmo) {
            return;                          // cmp.w OldAmmo,d0 / bne
        }
        shownAmmo = level;
        cache[1] = null;
        int base = SPRITE_BYTES * 2 + AMMO_AT;
        for (int i = 0; i <= AMMO_MAX; i++) {
            boolean lit = i >= AMMO_MAX - level;
            for (int half = 0; half < 2; half++) {
                int a1 = base + (i * 2 + half) * LINE_BYTES;
                int a2 = a1 + SPRITE_BYTES;
                int at = i * 8 + half * 4;
                data[a1] = lit ? ammo[at] : 0;
                data[a1 + 8] = lit ? ammo[at + 1] : 0;
                data[a2] = lit ? ammo[at + 2] : 0;
                data[a2 + 8] = lit ? ammo[at + 3] : 0;
            }
        }
    }

    public static Border load(GameData game) throws IOException {
        byte[] left = Files.readAllBytes(game.include("newleftbord"));
        byte[] right = Files.readAllBytes(game.include("newrightbord"));
        byte[] all = new byte[left.length + right.length];
        System.arraycopy(left, 0, all, 0, left.length);
        System.arraycopy(right, 0, all, left.length, right.length);
        return new Border(all,
                Panel.readCopperPalette(Files.readAllBytes(game.include("borderpal"))),
                Files.readAllBytes(game.include("healthstrip")),
                Files.readAllBytes(game.include("ammostrip")));
    }

    /**
     * One attached pair as colour indices, sixty-four across by a hundred and
     * sixty down.
     *
     * @param pair 0 for the left edge, 1 for the right
     */
    public byte[] pixels(int pair) {
        if (cache[pair] != null) {
            return cache[pair];
        }
        byte[] out = new byte[WIDTH * LINES];
        for (int half = 0; half < 2; half++) {
            int base = (pair * 2 + half) * SPRITE_BYTES + HEADER;
            for (int y = 0; y < LINES; y++) {
                int at = base + y * LINE_BYTES;
                for (int p = 0; p < 2; p++) {
                    for (int b = 0; b < 8; b++) {
                        int bits = data[at + p * 8 + b] & 0xff;
                        if (bits == 0) {
                            continue;
                        }
                        // sprite 0 of the pair holds bits 0-1, sprite 1 bits 2-3
                        int bit = 1 << (half * 2 + p);
                        for (int i = 0; i < 8; i++) {
                            if ((bits & (0x80 >> i)) != 0) {
                                out[y * WIDTH + b * 8 + i] |= (byte) bit;
                            }
                        }
                    }
                }
            }
        }
        cache[pair] = out;
        return out;
    }
}
