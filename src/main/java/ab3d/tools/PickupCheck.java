package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;
import ab3d.engine.Doors;
import ab3d.engine.ObjectHandler;

/**
 * Does a key actually unlock something, and only from close up?
 *
 * Three things have to line up for a key to matter, and each is checked here on
 * its own: the pickup arms when the player stands on it, it does not arm from
 * across the room, and the bit it grants is one a door in that level is waiting
 * for. The last is the point of the whole exercise -- four of level_d's doors
 * ask for bits zero to three and the level has no switches at all, so without
 * keys it cannot be finished.
 */
public final class PickupCheck {

    /** Far enough away that CheckHit's 100*100 cannot match. */
    private static final int FAR = 400;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        int near = 0, wrongly = 0, useful = 0, keys = 0;

        for (String name : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            int wanted = doorBits(lv);

            for (int i = 0; i < lv.objects.size(); i++) {
                GameObject o = lv.objects.get(i);
                int base = lv.ptrObjects + i * GameObject.SIZE;
                if (o.notInPlay || lv.data.s8(base + 16) != ObjectHandler.TYPE_KEY) {
                    continue;
                }
                keys++;
                int zone = lv.data.s16(base + 12);
                int grant = lv.data.u8(base + 17);
                int pt = lv.data.s16(base);
                int kx = lv.objectPointX[pt], kz = lv.objectPointZ[pt];

                // Standing well away from it: nothing may happen
                Level far = Level.load(game, name);
                ObjectHandler h = new ObjectHandler(far);
                h.arm(far.zone(zone));
                int got = h.update(zone, kx + FAR, kz + FAR, false, 0);
                if (got != 0) {
                    wrongly++;
                    System.out.printf("  %s key in zone %d armed from %d away%n",
                                      name, zone, FAR);
                }

                // Standing on it: the bits must arrive, and the object must go
                Level on = Level.load(game, name);
                h = new ObjectHandler(on);
                h.arm(on.zone(zone));
                got = h.update(zone, kx, kz, false, 0);
                int still = on.data.s16(on.ptrObjects + i * GameObject.SIZE + 12);
                if (got == grant && grant != 0 && still == -1) {
                    near++;
                } else {
                    System.out.printf("  %s key in zone %d: got %d want %d, zone now %d%n",
                                      name, zone, got, grant, still);
                }
                if ((grant & wanted) != 0) {
                    useful++;
                } else {
                    System.out.printf("  %s key in zone %d grants bit %s, nothing wants it%n",
                                      name, zone, bits(grant));
                }
            }
            System.out.printf("%-8s doors and lifts want bits %s%n", name, bits(wanted));
        }
        System.out.printf("%n%d keys: %d picked up on the spot, %d armed from afar, "
                          + "%d open a door%n", keys, near, wrongly, useful);
    }

    /** Every condition bit the level's doors and lifts are waiting on. */
    private static int doorBits(Level lv) {
        int all = 0;
        for (Doors.Door d : new Doors(lv).doors) {
            all |= d.conditions;
        }
        for (ab3d.engine.Lifts.Lift l : new ab3d.engine.Lifts(lv).lifts) {
            all |= l.conditions;
        }
        return all;
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
