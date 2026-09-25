package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.SineTable;

/**
 * Is negating the sine and cosine the same as adding half a turn?
 *
 * {@code DrawDisplay} negates both rather than turning the angle, so the two are
 * the same view only if the table is exactly antisymmetric across 2048 entries.
 */
public final class SineSym {

    public static void main(String[] args) throws Exception {
        SineTable t = SineTable.load(GameData.fromSystemProperty());
        int worstSin = 0, worstCos = 0, differ = 0;
        for (int a = 0; a < 4096; a++) {
            int half = (a + 2048) & 4095;
            int ds = Math.abs(t.sin(half) - (-t.sin(a)));
            int dc = Math.abs(t.cos(half) - (-t.cos(a)));
            worstSin = Math.max(worstSin, ds);
            worstCos = Math.max(worstCos, dc);
            if (ds != 0 || dc != 0) {
                differ++;
            }
        }
        System.out.printf("over 4096 angles: %d differ | worst sine %d, worst cosine %d"
                        + " (the table's unit is 32768)%n", differ, worstSin, worstCos);
    }
}
