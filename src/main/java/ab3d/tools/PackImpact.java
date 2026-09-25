package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.WadTexture;

/**
 * Measures how much the three packed pixel fields of a wall texture differ.
 *
 * Reading only the first field, as the renderer did before the packing was
 * understood, is right for one wall column in three. This says how wrong the
 * other two were rather than leaving it as an assertion.
 */
public final class PackImpact {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String[] names = { "greenmechanic.wad", "bluegreymetal.wad", "technodetail.wad",
                           "bluestone.wad", "redalert.wad", "rock.wad" };
        int height = 64;

        System.out.printf("%-20s %8s %10s %10s%n",
                "texture", "texels", "field1 dif", "field2 dif");
        long totT = 0, tot1 = 0, tot2 = 0;

        for (String n : names) {
            WadTexture t = WadTexture.load(game, n);
            int strips = t.stripCount(height);
            long texels = 0, d1 = 0, d2 = 0;
            for (int s = 0; s < strips; s++) {
                for (int y = 0; y < height; y++) {
                    int f0 = t.texel(s, y, height, 0);
                    if (t.texel(s, y, height, 1) != f0) {
                        d1++;
                    }
                    if (t.texel(s, y, height, 2) != f0) {
                        d2++;
                    }
                    texels++;
                }
            }
            System.out.printf("%-20s %8d %9.1f%% %9.1f%%%n", n, texels,
                    100.0 * d1 / texels, 100.0 * d2 / texels);
            totT += texels;
            tot1 += d1;
            tot2 += d2;
        }
        System.out.printf("%noverall: %.1f%% of columns were reading a wrong value%n",
                100.0 * (tot1 + tot2) / (3.0 * totT));
    }
}
