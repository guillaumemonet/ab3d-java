package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.GunData;
import ab3d.data.Level;
import ab3d.data.Samples;
import ab3d.engine.Barrel;
import ab3d.engine.Enemy;
import ab3d.engine.Frame68k;
import ab3d.engine.Obj;
import ab3d.engine.Sound;
import ab3d.game.Player;

/**
 * Does playing the game actually ask for sounds, and the right ones?
 *
 * Every routine's {@code jsr MakeSomeNoise} was skipped while the rest was being
 * transcribed, so the question is not whether the audio works -- that is a
 * mixer, and it either opens or it does not -- but whether the calls were put
 * back in the places the original makes them. A sink that records what it is
 * asked for answers that without a sound card.
 *
 * The falloff is the other half. {@code MakeSomeNoise} divides the volume by a
 * quarter of the distance plus one, so the same event heard from further away
 * has to come out quieter, and by that rule rather than any other.
 */
public final class NoiseCheck {

    private static final String[] NAMES = new String[28];

    static {
        NAMES[Sound.SCREAM] = "scream";
        NAMES[Sound.FIRE] = "fire!";
        // shoot.dm is shared: it is the pulse rifle's own noise,
        // which the gun record names at offset three, and the
        // marine's as well
        NAMES[Sound.ENEMY_SHOT] = "shoot.dm";
        NAMES[Sound.COLLECT] = "collect";
        NAMES[Sound.DOOR] = "door";
        NAMES[Sound.SWITCH] = "switch";
        NAMES[Sound.RELOAD] = "reload";
        NAMES[Sound.NO_AMMO] = "no ammo";
        NAMES[Sound.SPLAT_POP] = "splat";
        NAMES[Sound.BOOM] = "boom";
        NAMES[Sound.SHOTGUN] = "shotgun";
    }

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Samples bank = Samples.load(game);
        String name = args.length > 0 ? args[0] : "level_b";
        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);
        Player p = new Player(lv);

        int[] count = new int[64];
        frame.setSound((sample, x, z, volume, id, notIfPlaying) -> {
            if (sample >= 0 && sample < count.length) {
                count[sample]++;
            }
        });

        // find something to shoot and a barrel to break
        int victim = -1, barrel = -1;
        for (int i = 0; i < lv.objects.size(); i++) {
            int b = lv.ptrObjects + i * GameObject.SIZE;
            if (lv.data.s16(b + Obj.ZONE) < 0) {
                continue;
            }
            int t = lv.data.u8(b + Obj.TYPE);
            if (victim < 0 && Enemy.kindOf(t) != null) {
                victim = i;
            }
            if (barrel < 0 && t == Barrel.TYPE) {
                barrel = i;
            }
        }
        System.out.printf("watching an enemy and %s barrel%n",
                          barrel < 0 ? "no" : "a");
        int base = lv.ptrObjects + victim * GameObject.SIZE;
        int zone = lv.data.s16(base + Obj.ZONE);
        int pt = lv.data.s16(base + Obj.POINT);
        int vx = lv.objectPointX[pt], vz = lv.objectPointZ[pt];
        int standY = lv.zone(zone).floorHeight - Player.EYE_HEIGHT;

        frame.gunSelected = 0;
        frame.gunData.setWord(0, GunData.AMMO, GunData.AMMO_MAX);
        for (int t = 0; t < 300; t++) {
            frame.firePlayer(true, t == 0, 1, vx - 60, vz, standY,
                             Player.EYE_HEIGHT, angleTo(vx - 60, vz, vx, vz),
                             zone, false);
            frame.armObjects(zone);
            frame.updateObjects(zone, vx - 60, vz, standY, false, 1, 0);
            frame.updateSwitches(1, vx - 60, vz, t % 40 == 0);
            frame.updateDoors(1, t % 40 == 0);
            frame.updateLifts(1, zone, t % 40 == 0);
        }

        System.out.printf("three hundred frames in %s asked for:%n", name);
        int kinds = 0, total = 0;
        for (int i = 0; i < count.length; i++) {
            if (count[i] > 0) {
                kinds++;
                total += count[i];
                System.out.printf("  %-12s (%2d) %4d times, %s%n",
                                  i < NAMES.length && NAMES[i] != null
                                          ? NAMES[i] : "sample " + i,
                                  i, count[i],
                                  bank.has(i) ? bank.get(i).data().length
                                                + " bytes on disk"
                                              : "NO FILE");
            }
        }
        System.out.printf("%d kinds, %d calls in all%n%n", kinds, total);

        // and the falloff: the same sound at four distances
        int[] heard = new int[4];
        int[] away = {0, 200, 600, 2000};
        for (int i = 0; i < away.length; i++) {
            heard[i] = volumeAt(away[i], 100);
        }
        // and whether this machine can actually play one
        ab3d.game.Noise out = new ab3d.game.Noise(bank);
        boolean opened = out.open();
        System.out.printf("%naudio output: %s at %.0f Hz, %d voices%n",
                          opened ? "open" : "unavailable, the game runs silent",
                          Samples.RATE, ab3d.game.Noise.VOICES);
        if (opened) {
            out.play(Sound.COLLECT, 0, 0, 100, 1, false);
            out.play(Sound.BOOM, 400, 0, 100, 2, false);
            out.play(Sound.COLLECT, 0, 0, 100, 1, true);
            System.out.printf("  three asked for: %d started, %d dropped as "
                              + "already playing%n", out.played, out.dropped);
            Thread.sleep(400);
            out.close();
        }

        System.out.println();
        System.out.println("a sound of volume 100 heard from:");
        for (int i = 0; i < away.length; i++) {
            System.out.printf("  %5d away: %d of 64%n", away[i], heard[i]);
        }
    }

    /** The same arithmetic MakeSomeNoise does, to show the curve. */
    private static int volumeAt(int dist, int volume) {
        if (dist == 0) {
            return 64;
        }
        long v = Math.min(32767, (long) volume << 6);
        return (int) Math.min(64, v / ((dist >> 2) + 1));
    }

    private static int angleTo(int fromX, int fromZ, int toX, int toZ) {
        double a = Math.atan2(toX - fromX, toZ - fromZ);
        return ((int) Math.round(a / (2 * Math.PI) * 4096)) & 4095;
    }
}
