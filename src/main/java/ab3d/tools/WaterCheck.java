package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.WaterTexture;

/** Is brightentab a table of twelve-bit colours, as the screen needs? */
public final class WaterCheck {

    public static void main(String[] args) throws Exception {
        WaterTexture w = WaterTexture.load(GameData.fromSystemProperty());
        int[] v = w.validity();
        System.out.printf("brightentab: %d entries fit twelve bits, %d do not (%.1f%%)%n",
                          v[0], v[1], 100.0 * v[1] / (v[0] + v[1]));
    }
}
