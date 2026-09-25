package ab3d.data;

/**
 * The fifteen shades a sprite palette is drawn in.
 *
 * Each {@code .pal} in the source tree is nine hundred and sixty bytes: fifteen
 * rows of thirty-two twelve-bit colours, row nought at full brightness and each
 * row after it a step darker. Only the first row is a choice anyone made. The
 * fourteen below it follow a rule, and the same rule in every file -- taking all
 * fourteen palettes together gives six thousand seven hundred and twenty samples
 * of "this component at this shade becomes that", and not one of them disagrees
 * with another.
 *
 * So the rule is here and the rows are not. {@link ab3d.tools.ShadeCheck} holds
 * it against every {@code .pal} the source tree carries, which is the only thing
 * that makes it safe to throw the rows away.
 */
public final class Shading {

    /** Rows in a {@code .pal}, and colours in each. */
    public static final int SHADES = 15, COLOURS = 32;

    private Shading() {
    }

    /**
     * A four-bit component at a shade.
     *
     * Fifteen steps, so a shade keeps {@code (15 - shade)/15} of the component
     * and the remainder is dropped rather than rounded. The last shade is the
     * exception: by the rule it would leave a component of fifteen at one, and
     * every file has it at nought instead -- the bottom of the ramp was meant to
     * be black and was made so.
     */
    public static int component(int shade, int value) {
        if (shade >= SHADES - 1) {
            return 0;
        }
        return value * (SHADES - shade) / SHADES;
    }

    /** A whole twelve-bit colour at a shade. */
    public static int colour(int shade, int rgb) {
        return (component(shade, (rgb >> 8) & 0xf) << 8)
             | (component(shade, (rgb >> 4) & 0xf) << 4)
             |  component(shade, rgb & 0xf);
    }

    /**
     * Builds a {@code .pal}'s contents from its top row.
     *
     * Big-endian words in the file's own order, so what comes back can be used
     * wherever the file was.
     */
    public static byte[] ramp(int[] base) {
        byte[] out = new byte[SHADES * COLOURS * 2];
        for (int s = 0; s < SHADES; s++) {
            for (int c = 0; c < COLOURS; c++) {
                int v = colour(s, c < base.length ? base[c] : 0);
                int at = (s * COLOURS + c) * 2;
                out[at] = (byte) (v >> 8);
                out[at + 1] = (byte) v;
            }
        }
        return out;
    }
}
