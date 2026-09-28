package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;

/**
 * Which polygon objects do the shipped levels actually place?
 *
 * {@code cmp.b #$ff,6(a0) / bne BitMapObj}: an object whose width scale is
 * {@code $ff} is not a sprite but a model, and its slot indexes
 * {@code POLYOBJECTS} -- ten entries, of which the last five are the four key
 * indicators and a gas pipe. Knowing which of the ten are used decides how much
 * of the model renderer is worth writing.
 */
public final class PolyCensus {

    private static final String[] NAMES = {
        "robot", "medipac.vec", "exitsign", "crate.vec", "terminal.vec",
        "blueind", "greenind", "redind", "yellowind", "gaspipe",
    };

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        java.util.Map<Integer, Integer> tally = new java.util.TreeMap<>();
        for (String name : game.diskLevels()) {
            Level lv = Level.load(game, name);
            StringBuilder here = new StringBuilder();
            for (GameObject o : lv.objects) {
                if (o.isSprite()) {
                    continue;
                }
                tally.merge(o.slot, 1, Integer::sum);
                here.append(' ').append(o.slot);
            }
            System.out.printf("  %-8s slots:%s%n", name, here);
        }
        System.out.println("\nacross the sixteen levels:");
        tally.forEach((slot, n) -> System.out.printf("  slot %d  %-14s %3d placed%n",
                slot, slot < NAMES.length ? NAMES[slot] : "?", n));
    }
}
