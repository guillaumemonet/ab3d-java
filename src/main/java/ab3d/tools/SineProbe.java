package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.SineTable;

/** What the sine table gives at the quarter turns, where a word can overflow. */
public final class SineProbe {

    public static void main(String[] args) throws Exception {
        SineTable s = SineTable.load(GameData.fromSystemProperty());
        for (int a : new int[]{0, 1024, 2046, 2048, 2050, 4096, 6144, 8190}) {
            System.out.printf("angle %5d  sin %7d  cos %7d%n", a, s.sin(a), s.cos(a));
        }
    }
}
