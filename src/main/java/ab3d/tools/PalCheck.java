package ab3d.tools;

import ab3d.data.FloorTexture;
import ab3d.data.GameData;

/** Brightness of each row of the floor palette, to say what a shade row means. */
public final class PalCheck {

    public static void main(String[] args) throws Exception {
        FloorTexture f = FloorTexture.load(GameData.fromSystemProperty());
        for (int s = 0; s < FloorTexture.SHADES; s++) {
            int zero = 0;
            long lum = 0;
            for (int i = 0; i < FloorTexture.COLOURS; i++) {
                int c = f.colour(s, i);
                if (c == 0) {
                    zero++;
                }
                lum += ((c >> 8) & 0xf) + ((c >> 4) & 0xf) + (c & 0xf);
            }
            System.out.printf("row %2d: %3d of 256 black, mean luminance %.2f of 45%n",
                              s, zero, lum / (double) FloorTexture.COLOURS);
        }
    }
}
