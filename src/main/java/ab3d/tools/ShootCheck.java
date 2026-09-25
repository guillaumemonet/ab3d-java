package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GunData;
import ab3d.data.Level;
import ab3d.engine.Frame68k;
import ab3d.game.Player;

/**
 * Holding the trigger down for a while: what does each weapon actually do?
 *
 * Three things have to come out right and they constrain each other. A gun
 * fires at its own rate, because {@code PLR1_TimeToShoot} is set from the delay
 * in its record and counted down by {@code TempFrames}. Each shot spends
 * {@code ammopershot}, so the rounds and the shots have to agree exactly. And a
 * shot starts the animation at its last step, which counts down by one more than
 * the frames elapsed -- so the gun frame has to reach nothing before the next
 * shot, or the weapon would never appear at rest.
 *
 * The click-or-hold field is the other half: a gun with it clear fires once per
 * press however long the key is held.
 */
public final class ShootCheck {

    private static final String[] NAMES = {
        "pulse rifle", "plasma gun", "rocket launcher", "flame thrower",
        "grenade launcher", "(none)", "(none)", "shotgun",
    };
    private static final int FRAMES = 300;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args.length > 0 ? args[0] : "level_a");

        System.out.printf("%-18s %5s %5s %6s %6s %5s %6s%n", "gun", "delay",
                          "/shot", "shots", "spent", "hold", "maxfrm");
        for (int gun = 0; gun < GunData.GUNS; gun++) {
            Frame68k frame = new Frame68k(lv, game);
            Player p = new Player(lv);
            if (frame.gunAnims.frames(gun).length == 0) {
                continue;                           // a slot with no weapon
            }
            // give it enough to run the whole test, as a password or a pickup would
            frame.gunData.setByte(gun, GunData.GOT_GUN, 0xff);
            frame.gunData.setWord(gun, GunData.AMMO, GunData.AMMO_MAX);
            frame.gunSelected = gun;
            int before = frame.gunData.unsigned(gun, GunData.AMMO);

            int shots = 0, framesAtRest = 0;
            boolean held = false;
            for (int t = 0; t < FRAMES; t++) {
                boolean clicked = !held;            // one press, then held down
                held = true;
                if (frame.firePlayer(true, clicked, 1, p.camera.x, p.camera.z,
                                     p.camera.yoff, Player.EYE_HEIGHT,
                                     p.camera.angle)) {
                    shots++;
                }
                frame.shot.ageGunFrame(1);
                if (frame.shot.gunFrame == 0) {
                    framesAtRest++;
                }
            }
            int spent = before - frame.gunData.unsigned(gun, GunData.AMMO);
            int perShot = frame.gunData.byteAt(gun, GunData.PER_SHOT);
            System.out.printf("%-18s %5d %5d %6d %6d %5d %6d  %s%n",
                              NAMES[gun], frame.gunData.word(gun, GunData.DELAY),
                              perShot, shots, spent,
                              frame.gunData.word(gun, GunData.HOLD),
                              frame.gunAnims.maxFrame(gun),
                              spent == shots * perShot ? ""
                                      : "<-- rounds do not match the shots");
            if (framesAtRest == 0 && shots > 0) {
                System.out.printf("      the animation never reaches rest in %d frames%n",
                                  FRAMES);
            }
        }

        // and a gun with nothing in it must refuse rather than go negative
        Frame68k frame = new Frame68k(lv, game);
        Player p = new Player(lv);
        frame.gunSelected = 0;
        frame.gunData.setWord(0, GunData.AMMO, 0);
        for (int t = 0; t < 30; t++) {
            frame.firePlayer(true, t == 0, 1, p.camera.x, p.camera.z,
                             p.camera.yoff, Player.EYE_HEIGHT, p.camera.angle);
        }
        System.out.printf("%nempty gun: %d shots, %d refused, %d rounds left%n",
                          frame.shot.fired, frame.shot.dryFired,
                          frame.gunData.word(0, GunData.AMMO));
    }
}
