package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.GunData;
import ab3d.data.Level;
import ab3d.engine.ObjectHandler;

/**
 * What the four walk-into pickups actually do, one level at a time.
 *
 * The point is not that they are picked up -- the key check already showed the
 * reach test works -- but that each one moves the thing it is supposed to, by
 * the amount its own record says.
 *
 * The criterion has to allow for company. Pickups are laid out in clusters, and
 * the handler walks the whole object list every frame, so standing on one takes
 * every other one within a hundred units at the same time. Comparing the change
 * against a single record's amount reads that as a failure at exactly double, or
 * treble. So the sum is taken over whatever was actually retired in the pass,
 * which is the claim worth making: every pickup that went contributed its own
 * record's amount and no more.
 */
public final class PickupCensus {

    private static final String[] TYPE = new String[32];

    static {
        TYPE[ObjectHandler.TYPE_MEDIKIT] = "medikit";
        TYPE[ObjectHandler.TYPE_GUN] = "gun";
        TYPE[ObjectHandler.TYPE_KEY] = "key";
        TYPE[ObjectHandler.TYPE_AMMO] = "ammo clip";
    }

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        int passes = 0, healOk = 0, ammoOk = 0, gunOk = 0;
        int tookMedikits = 0, tookClips = 0, tookGuns = 0;

        for (String name : new String[]{"level_a", "level_b", "level_c", "level_d"}) {
            Level lv = Level.load(game, name);
            int[] count = new int[32];
            for (int i = 0; i < lv.objects.size(); i++) {
                GameObject o = lv.objects.get(i);
                int base = lv.ptrObjects + i * GameObject.SIZE;
                if (o.notInPlay) {
                    continue;
                }
                int t = lv.data.s8(base + 16);
                if (t >= 0 && t < count.length) {
                    count[t]++;
                }
            }
            StringBuilder b = new StringBuilder();
            for (int t = 0; t < count.length; t++) {
                if (count[t] > 0 && TYPE[t] != null) {
                    b.append(String.format("  %d %s", count[t], TYPE[t]));
                }
            }
            System.out.printf("%-8s%s%n", name, b);

            for (int i = 0; i < lv.objects.size(); i++) {
                int base = lv.ptrObjects + i * GameObject.SIZE;
                int t = lv.data.s8(base + 16);
                if (t != ObjectHandler.TYPE_MEDIKIT && t != ObjectHandler.TYPE_AMMO
                        && t != ObjectHandler.TYPE_GUN) {
                    continue;
                }
                Level fresh = Level.load(game, name);
                int zone = fresh.data.s16(base + 12);
                int pt = fresh.data.s16(base);
                if (zone < 0 || zone >= fresh.zones.length
                        || pt < 0 || pt >= fresh.objectPointX.length) {
                    continue;
                }
                ObjectHandler h = new ObjectHandler(fresh);
                h.guns = GunData.load(game);
                h.energy = 1;                       // so a medikit has room
                int[] before = ammoAll(h.guns);
                boolean[] wasThere = inPlay(fresh);

                h.arm(fresh.zone(zone));
                h.update(zone, fresh.objectPointX[pt], fresh.objectPointZ[pt],
                         false, 0);
                passes++;

                // what went, and what each of them should have been worth
                int wantHeal = 0;
                int[] wantAmmo = before.clone();
                for (int j = 0; j < fresh.objects.size(); j++) {
                    int at = fresh.ptrObjects + j * GameObject.SIZE;
                    if (!wasThere[j] || fresh.data.s16(at + 12) >= 0) {
                        continue;                   // still on the floor
                    }
                    int kind = fresh.data.s8(at + 16);
                    if (kind == ObjectHandler.TYPE_MEDIKIT) {
                        wantHeal += fresh.data.s16(at + 18);
                    } else if (kind == ObjectHandler.TYPE_AMMO) {
                        int slot = fresh.data.s16(at + 18);
                        if (slot >= 0 && slot < GunData.GUNS) {
                            wantAmmo[slot] += h.guns.byteAt(slot, GunData.CLIP) << 3;
                        }
                    } else if (kind == ObjectHandler.TYPE_GUN) {
                        int slot = fresh.data.u8(at + 17) + 1;
                        if (slot >= 0 && slot < GunData.GUNS) {
                            wantAmmo[slot] += GunData.AMMO_IN_GUNS[slot - 1];
                        }
                    }
                }

                tookMedikits += h.medikitsTaken;
                tookClips += h.clipsTaken;
                tookGuns += h.gunsTaken;

                int heal = Math.min(ObjectHandler.ENERGY_MAX, 1 + wantHeal);
                if (h.energy == heal) {
                    healOk++;
                } else {
                    System.out.printf("    %s: energy %d, want %d (%d medikits went)%n",
                                      name, h.energy, heal, h.medikitsTaken);
                }
                boolean ammoAgrees = true;
                for (int g = 0; g < GunData.GUNS; g++) {
                    if (h.guns.unsigned(g, GunData.AMMO) != wantAmmo[g]) {
                        ammoAgrees = false;
                        System.out.printf("    %s gun %d: %d rounds, want %d%n",
                                          name, g, h.guns.unsigned(g, GunData.AMMO),
                                          wantAmmo[g]);
                    }
                }
                if (ammoAgrees) {
                    ammoOk++;
                }
                if (h.gunsTaken == 0 || h.guns.has(t == ObjectHandler.TYPE_GUN
                        ? fresh.data.u8(base + 17) + 1 : 0)) {
                    gunOk++;
                }
            }
        }
        System.out.printf("%n%d passes: energy right %d, ammunition right %d, "
                          + "guns right %d%n", passes, healOk, ammoOk, gunOk);
        System.out.printf("taken across them all: %d medikits, %d clips, %d guns%n",
                          tookMedikits, tookClips, tookGuns);
    }

    private static boolean[] inPlay(Level lv) {
        boolean[] out = new boolean[lv.objects.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = lv.data.s16(lv.ptrObjects + i * GameObject.SIZE + 12) >= 0;
        }
        return out;
    }

    private static int[] ammoAll(GunData g) {
        int[] out = new int[GunData.GUNS];
        for (int i = 0; i < out.length; i++) {
            out[i] = g.unsigned(i, GunData.AMMO);
        }
        return out;
    }
}
