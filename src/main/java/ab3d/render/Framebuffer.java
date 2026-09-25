package ab3d.render;

/**
 * The 3D view buffer, in 12-bit RGB.
 *
 * Alien Breed 3D renders into a 96 x 80 buffer of 12-bit RGB values -- one word
 * per pixel, not a palette index -- which newsource/chunkyconvert.s then doubles
 * horizontally to 192 x 80 and blits at (32, 0) of the 320 x 200 screen. Buffer
 * pixels are therefore twice as wide as they are tall.
 *
 * Here the buffer is kept at the <em>displayed</em> shape instead, 192 x 80 with
 * square pixels, which costs nothing and already doubles the horizontal detail.
 * A {@code scale} multiplies both axes from there, so the geometry is unchanged
 * and only the sampling gets finer.
 *
 * On the Amiga those 12-bit values drove copper colour changes; the RTG port
 * quantises them to 256 pens. Keeping them as 12-bit and expanding straight to
 * RGB skips that quantisation.
 */
public final class Framebuffer {

    /** Width of the view once the engine's horizontal doubling is applied. */
    public static final int BASE_WIDTH = 192;
    public static final int BASE_HEIGHT = 80;
    /**
     * Focal length in base pixels. The engine projects with 64 buffer columns,
     * and a buffer column is two of these.
     */
    public static final double BASE_FOCAL = 128.0;
    /**
     * Horizontal centre of projection. The engine's {@code add.w #47,d2} is not
     * quite the middle of its 96 columns, and that slight bias is kept.
     */
    public static final double BASE_CENTRE_X = 94.0;
    /** Vertical centre, from the {@code add.w #40,d5} in the wall routine. */
    public static final double BASE_CENTRE_Y = 40.0;

    public final int scale;
    public final int width, height;
    public final double focal, centreX, centreY;

    /** One 12-bit RGB value (0x0RGB) per pixel. */
    public final short[] pixels;

    public Framebuffer() {
        this(1);
    }

    public Framebuffer(int scale) {
        this.scale = Math.max(1, scale);
        this.width = BASE_WIDTH * this.scale;
        this.height = BASE_HEIGHT * this.scale;
        this.focal = BASE_FOCAL * this.scale;
        this.centreX = BASE_CENTRE_X * this.scale;
        this.centreY = BASE_CENTRE_Y * this.scale;
        this.pixels = new short[width * height];
    }

    public void clear(int rgb12) {
        java.util.Arrays.fill(pixels, (short) rgb12);
    }

    public void set(int x, int y, int rgb12) {
        pixels[y * width + x] = (short) rgb12;
    }

    /** Fills column {@code x} from row {@code y0} up to but not including {@code y1}. */
    public void column(int x, int y0, int y1, int rgb12) {
        if (y0 < 0) {
            y0 = 0;
        }
        if (y1 > height) {
            y1 = height;
        }
        int i = y0 * width + x;
        for (int y = y0; y < y1; y++, i += width) {
            pixels[i] = (short) rgb12;
        }
    }

    /** Packs a 12-bit RGB triplet the way the original colour words are laid out. */
    public static int rgb12(int r, int g, int b) {
        return (clamp4(r) << 8) | (clamp4(g) << 4) | clamp4(b);
    }

    private static int clamp4(int v) {
        return v < 0 ? 0 : Math.min(v, 15);
    }

    /** Scales a 12-bit colour by {@code num/den}, for distance shading. */
    public static int shade(int rgb12, int num, int den) {
        if (den <= 0) {
            return 0;
        }
        int r = ((rgb12 >> 8) & 0xf) * num / den;
        int g = ((rgb12 >> 4) & 0xf) * num / den;
        int b = (rgb12 & 0xf) * num / den;
        return rgb12(r, g, b);
    }

    /** Bytes an RGBA copy of this buffer needs. */
    public int rgbaSize() {
        return width * height * 4;
    }

    /** Expands to 8-bit RGBA, replicating each nibble as the engine's palette does. */
    public void toRgba(byte[] out) {
        int o = 0;
        for (short pixel : pixels) {
            int r = (pixel >> 8) & 0xf;
            int g = (pixel >> 4) & 0xf;
            int b = pixel & 0xf;
            out[o++] = (byte) (r | (r << 4));
            out[o++] = (byte) (g | (g << 4));
            out[o++] = (byte) (b | (b << 4));
            out[o++] = (byte) 0xff;
        }
    }
}
