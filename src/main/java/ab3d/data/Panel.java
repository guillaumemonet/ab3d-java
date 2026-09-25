package ab3d.data;

import java.io.IOException;
import java.nio.file.Files;

/**
 * The status panel that sits under the view, from includes/panelraw.
 *
 * {@code LOADBOTPIC} in source/loadfromdisk.s reads the file, asks the system
 * for {@code PanelLen} bytes -- thirty thousand seven hundred and twenty -- and
 * unpacks it there with {@code unLHA}. That length is the whole description of
 * the picture: forty bytes a plane row, eight planes, ninety-six rows, in the
 * interleaved order the Amiga's display hardware reads, so it is three hundred
 * and twenty pixels across at eight bits deep.
 *
 * The palette is not a palette but a fragment of a copper list. jg.s does
 * {@code INCBIN "Panelpal"} in the middle of {@code PanelCop}, so the file is
 * already register writes: eight groups of a bank select followed by thirty-two
 * colour registers, which is how AGA addresses two hundred and fifty-six
 * colours through thirty-two hardware registers.
 *
 * {@code OFFSETTOGRAPH} confirms the geometry from the other side. Its entries
 * are byte offsets of the form {@code (40*8)*row+column}, which only makes sense
 * against a row stride of forty times eight.
 */
public final class Panel {

    /** {@code move.l #30720,PanelLen}. */
    public static final int SIZE = 30720;
    public static final int WIDTH = 320, HEIGHT = 96, PLANES = 8;
    /** {@code (40*8)}: one screen row, all eight planes of it. */
    public static final int ROW_BYTES = WIDTH / 8 * PLANES;

    /** One byte a pixel, the colour index the eight planes spell out. */
    public final byte[] pixels;
    /** The raw interleaved bitmap, which the panel routines write into. */
    public final byte[] planes;
    /** 0x00RRGGBB for each of the two hundred and fifty-six entries. */
    public final int[] palette;

    public Panel(byte[] planes, int[] palette) {
        this.planes = planes;
        this.palette = palette;
        this.pixels = new byte[WIDTH * HEIGHT];
        decode();
    }

    public static Panel load(GameData game) throws IOException {
        byte[] raw = game.bytes("panelraw");
        byte[] planes = SbDepacker.isPacked(raw) ? SbDepacker.unpack(raw) : raw;
        if (planes.length < SIZE) {
            byte[] grown = new byte[SIZE];
            System.arraycopy(planes, 0, grown, 0, planes.length);
            planes = grown;
        }
        return new Panel(planes, readCopperPalette(
                game.bytes("panelpal")));
    }

    /**
     * Turns the interleaved planes into one byte a pixel.
     *
     * Plane n contributes bit n, and the planes of a row sit one after another,
     * which is what "interleaved" means here -- not one whole plane after
     * another, as a planar file usually stores it.
     */
    private void decode() {
        for (int y = 0; y < HEIGHT; y++) {
            int row = y * ROW_BYTES;
            for (int p = 0; p < PLANES; p++) {
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

    /**
     * Reads a copper list's worth of colour writes.
     *
     * A write to {@code $106} is {@code bplcon3}, whose top three bits choose
     * which bank of thirty-two the registers {@code $180} to {@code $1be} stand
     * for. Everything else in the file is one of those registers.
     *
     * There are sixteen bank selects rather than eight, because AGA writes a
     * colour twice: bit nine of {@code bplcon3} is LOCT, and a register write
     * carries the high nibble of each gun when it is clear and the low nibble
     * when it is set. So this is a full eight-bit-per-gun palette, and reading
     * only one of the two passes gives eight bits of colour instead of
     * twenty-four -- which looks like the right picture in the wrong paint.
     */
    public static int[] readCopperPalette(byte[] file) {
        int[] out = new int[256];
        int bank = 0;
        boolean low = false;
        for (int at = 0; at + 4 <= file.length; at += 4) {
            int reg = ((file[at] & 0xff) << 8) | (file[at + 1] & 0xff);
            int val = ((file[at + 2] & 0xff) << 8) | (file[at + 3] & 0xff);
            if (reg == 0x106) {
                bank = (val >> 13) & 7;              // BANK, bits 15-13
                low = (val & 0x200) != 0;            // LOCT, bit 9
            } else if (reg >= 0x180 && reg <= 0x1be) {
                int i = bank * 32 + ((reg - 0x180) >> 1);
                int r = (val >> 8) & 0xf, g = (val >> 4) & 0xf, b = val & 0xf;
                out[i] = low
                        ? out[i] | (r << 16) | (g << 8) | b
                        : (r << 20) | (g << 12) | (b << 4);
            }
        }
        return out;
    }
}
