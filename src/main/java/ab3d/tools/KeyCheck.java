package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;

/**
 * Which objects are keys, and what they would unlock.
 *
 * {@code ItsAKey} retires the object and does {@code or.b 17(a0),Conditions+1},
 * so byte 17 is the bits it grants -- and those land in the low byte, which is
 * exactly the range the switches cannot reach.
 */
public final class KeyCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            int granted = 0, found = 0;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < lv.objects.size(); i++) {
                GameObject o = lv.objects.get(i);
                if (o.notInPlay) {
                    continue;
                }
                int base = lv.ptrObjects + i * GameObject.SIZE;
                // ObjectHandler dispatches on byte 16; four is a key
                if (lv.data.s8(base + 16) != 4) {
                    continue;
                }
                int byte17 = lv.data.u8(base + 17);
                found++;
                granted |= byte17;
                b.append(String.format(" [zone %d bit %s top %d]",
                                       lv.data.s16(base + 12), bits(byte17),
                                       lv.data.u8(base + 63)));
            }
            System.out.printf("%-8s %d keys, granting %s%s%n",
                              name, found, bits(granted),
                              b);
        }
    }

    private static String bits(int v) {
        if (v == 0) {
            return "none";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if ((v & (1 << i)) != 0) {
                b.append(b.length() == 0 ? "" : ",").append(i);
            }
        }
        return b.toString();
    }
}
