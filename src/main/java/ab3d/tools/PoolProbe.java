package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;

/**
 * Where the three pools of twenty records sit against the object list.
 *
 * jg.s takes {@code PlayerShotData} and {@code NastyShotData} from the level
 * header and then {@code add.l #64*20,a2 / move.l a2,OtherNastyData}: a third
 * pool straight after the enemy shots, with no header entry of its own. The
 * tree puts the eyeballs it spawns there. This prints, for each level, the
 * record index each pool starts at, how many records the loader kept as the
 * object list, and what the third pool's records hold.
 */
public final class PoolProbe {

    public static void main(String[] args) throws Exception {
        GameData g = GameData.fromSystemProperty();
        for (String n : args.length > 0 ? java.util.List.of(args) : g.diskLevels()) {
            Level lv = Level.load(g, n);
            int o = lv.ptrObjects;
            int other = lv.ptrEnemyShots + 64 * 20;
            System.out.printf("%s objs=%d  playerShots@%d enemyShots@%d other@%d"
                              + "  plr1@%d plr2@%d  points=%d%n",
                    n, lv.objects.size(), (lv.ptrPlayerShots - o) / 64,
                    (lv.ptrEnemyShots - o) / 64, (other - o) / 64,
                    (lv.ptrPlr1Obj - o) / 64, (lv.ptrPlr2Obj - o) / 64,
                    lv.objectPointX.length);
            StringBuilder b = new StringBuilder("   other:");
            for (int i = 0; i < 21; i++) {
                int at = other + i * 64;
                if (!lv.data.inRange(at, 64)) {
                    b.append(" <end at ").append(i).append('>');
                    break;
                }
                b.append(' ').append(lv.data.s16(at)).append('/')
                 .append(lv.data.s16(at + 12)).append('/')
                 .append(lv.data.s8(at + 16));
            }
            System.out.println(b);
        }
    }
}
