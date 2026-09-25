package ab3d.engine;

/**
 * Where a routine's {@code jsr MakeSomeNoise} goes.
 *
 * Every routine in the game asks for sound the same way -- a sample number, a
 * position relative to the player, a volume and an identifying number -- so one
 * interface serves all of them, and a port with no sound at all can simply not
 * supply one.
 *
 * {@code notifplaying} is worth keeping in the signature rather than deciding
 * per caller: it is what stops twenty aliens in a room each starting their own
 * scream, and which routines set it is part of what the original sounds like.
 */
public interface Sound {

    /** The slots of {@code SFX_NAMES} the engine names by number. */
    int SCREAM = 0, FIRE = 1, MUNCH = 2, ENEMY_SHOT = 3, COLLECT = 4;
    int DOOR = 5, SPLASH = 6, FOOTSTEP = 7, LOW_SCREAM = 8, BADDIE_GUN = 9;
    int SWITCH = 10, RELOAD = 11, NO_AMMO = 12, SPLOTCH = 13, SPLAT_POP = 14;
    int BOOM = 15, HISS = 16, HOWL1 = 17, HOWL2 = 18, PANT = 19;
    int WHOOSH = 20, SHOTGUN = 21, FLAME = 22, MUFFLED = 23, CLOP = 24;
    int CLANK = 25, TELEPORT = 26, WORM_PAIN = 27;

    void play(int sample, int x, int z, int volume, int id, boolean notIfPlaying);

    /** A sink that does nothing, for the checks and for a silent machine. */
    Sound SILENT = (sample, x, z, volume, id, notIfPlaying) -> { };
}
