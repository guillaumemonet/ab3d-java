package ab3d.engine;

/**
 * The object record's fields, as source/defs.s names them.
 *
 * One record is sixty-four bytes and serves as three different things depending
 * on its type byte -- a bullet in flight, an enemy, or a pickup -- so the same
 * offset carries different meanings. defs.s writes the three layouts out one
 * after another and they overlap on purpose: eighteen is {@code numlives} for an
 * enemy, {@code shotxvel} for a bullet, and the heal or ammunition amount for a
 * pickup.
 *
 * Gathering them here rather than repeating the numbers is worth it for exactly
 * that reason: a field read at the wrong offset still gives a plausible number.
 */
public final class Obj {

    public static final int SIZE = 64;

    /** {@code move.w (a0),d0}: which of the object points holds the position. */
    public static final int POINT = 0;
    /** The brightness the renderer shades the sprite with. */
    public static final int BRIGHT = 2;
    /** Height above the zone's floor, in units the engine scales by 128. */
    public static final int HEIGHT = 4;
    /** The graphic slot, and the frame within it. */
    public static final int SLOT = 8, FRAME = 10;
    /** The zone it is in play in, or -1. {@code GraphicRoom} follows it. */
    public static final int ZONE = 12, GRAPHIC_ROOM = 26;
    /** Sprite width and height in texels, written as one word. */
    public static final int SPRITE_SIZE = 14;
    /** The dispatch byte, and the bits above it. */
    public static final int TYPE = 16, FLAGS = 17;

    /** {@code numlives EQU 18} and what follows it. */
    public static final int NUM_LIVES = 18, DAMAGE_TAKEN = 19;
    public static final int MAX_SPEED = 20, CURR_SPEED = 22, TARG_HEIGHT = 24;
    public static final int CURR_CPT = 28, FACING = 30, LEAD = 32;
    public static final int OBJ_TIMER = 34, ENEMY_FLAGS = 36, SEC_TIMER = 40;
    public static final int IMPACT_X = 42, IMPACT_Z = 44, IMPACT_Y = 46;
    public static final int OBJ_YVEL = 48, TURN_SPEED = 50;
    public static final int THIRD_TIMER = 52, FOURTH_TIMER = 54;

    /**
     * The bullet layout, which defs.s writes out above the enemy one.
     *
     * These overlap the enemy fields on purpose: eighteen is {@code numlives}
     * for a thing that walks and {@code shotxvel} for one that flies.
     */
    public static final int SHOT_XVEL = 18, SHOT_ZVEL = 22;
    public static final int SHOT_POWER = 28, SHOT_STATUS = 30, SHOT_SIZE = 31;
    public static final int SHOT_YVEL = 42, ACC_YPOS = 44;
    public static final int SHOT_ANIM = 52, SHOT_GRAV = 54;
    public static final int SHOT_IMPACT = 56, SHOT_LIFE = 58, SHOT_FLAGS = 60;

    /** {@code worry EQU 62}, armed by jg.s for everything in view. */
    public static final int WORRY = 62;
    /** {@code ObjInTop EQU 63}. */
    public static final int IN_TOP = 63;

    /** {@code btst #0,17(a0)}: the enemy can see player one. */
    public static final int SEES_PLAYER1 = 1;
    /** And player two, which {@code CanItBeSeen} ORs in as bit one. */
    public static final int SEES_PLAYER2 = 2;

    private Obj() {
    }
}
