package ab3d.game;

import ab3d.data.FloorLine;
import ab3d.data.Level;
import ab3d.data.Zone;
import ab3d.m68k.M68k;

/**
 * Player movement and zone tracking.
 *
 * Collision is the engine's own test from objectmove.s: walk the bounding lines
 * of the zone you are in, and if the new position falls on the far side of one,
 * either step into the zone behind it or -- when the line is solid -- refuse the
 * move. Repeating that until no line is crossed also keeps {@link Camera#zone}
 * correct when a single step crosses several portals.
 */
public final class Player {

    /** newsource/defs.s: {@code playerheight EQU 12*1024}. */
    public static final int EYE_HEIGHT = 12 * 1024;
    /**
     * source/defs.s, the file jg.s includes: {@code playercrouched EQU 8*1024}.
     * master.s says {@code 6*1024}; this build uses the larger one.
     */
    public static final int CROUCH_HEIGHT = 8 * 1024;
    /** {@code cmp.l #playerheight+3*1024,d0}: headroom needed to stand up. */
    private static final int STAND_ROOM = EYE_HEIGHT + 3 * 1024;
    /** {@code add.l #1024,d0} / {@code sub.l #1024,d0}: how fast the eye moves. */
    private static final int HEIGHT_STEP = 1024;

    /** How many portals a single step may cross before we give up. */
    private static final int MAX_CROSSINGS = 16;

    private final Level level;
    public final Camera camera = new Camera();

    /**
     * {@code PLR1_StoodInTop}: whether the player is on a zone's upper storey.
     *
     * {@code MoveObject} decides it at a floor-line crossing, from the height
     * there against the roof of the room being entered, and jg.s copies it back
     * with {@code move.b StoodInTop,PLR1_StoodInTop}.
     *
     * It decides more than where the eye is. {@code ObjectHandler} will not let
     * the player take a pickup on the other storey, {@code Player1Shot} will not
     * aim at anything on it, and the renderer draws the storey it names -- so a
     * wrong answer here is a room drawn from the wrong half of itself.
     *
     * Only ten zones across the four levels have a second storey at all: two in
     * the first, one in the second, none in the third and seven in the last.
     */
    public boolean stoodInTop;

    /** {@code PLR1s_height} and {@code PLR1s_targheight}. */
    public int height = EYE_HEIGHT;
    public int targetHeight = EYE_HEIGHT;
    /** {@code PLR1_Ducked}. */
    public boolean ducked;

    public Player(Level level) {
        this.level = level;
        this.mover = new ab3d.engine.MoveObject(level);
        camera.x = level.startX;
        camera.z = level.startZ;
        camera.zone = level.startZone;
        camera.angle = 0;
        settleHeight();
    }

    /** {@code playerheight} and the crouched value. */
    private static final int STEP_UP = 40 * 256, STEP_UP_DUCKED = 10 * 256;
    /** {@code move.l #$1000000,StepDownVal}: effectively no limit on a fall. */
    private static final int STEP_DOWN = 0x1000000;

    private final ab3d.engine.MoveObject mover;

    /**
     * {@code notduck}: the crouch key toggles which height the eye moves toward.
     *
     * The key is read as a tap -- the original clears the key map entry so a held
     * key acts once -- and only sets the target. The eye then travels there a
     * thousand and twenty-four units a frame, which is what makes crouching a
     * movement rather than a jump.
     */
    public void toggleDuck() {
        ducked = !ducked;
        targetHeight = ducked ? CROUCH_HEIGHT : EYE_HEIGHT;
    }

    /**
     * The rest of {@code PLR1_alwayskeys}: forced crouch, then the easing.
     *
     * A room with less than three units of clearance over standing height forces
     * the crouch whatever the key says, which is how a low passage is entered at
     * all. It is checked every frame, so walking into one ducks the player and
     * walking out lets them rise again.
     */
    public void tick() {
        Zone z = level.zone(camera.zone);
        int room = z.floorHeight - z.roofHeight;
        if (room <= STAND_ROOM) {               // cmp.l #playerheight+3*1024 / bgt
            ducked = true;
            targetHeight = CROUCH_HEIGHT;
        }
        if (height != targetHeight) {
            height += height > targetHeight ? -HEIGHT_STEP : HEIGHT_STEP;
        }
        settleHeight();
    }

    /**
     * {@code MoveObject}: moves and resolves against the room's walls.
     *
     * The routine does the touching as it goes, so the doors see the player
     * without a second pass over the lines.
     */
    /**
     * {@code PLR1_clumptime} and {@code PLR1clump}: the footsteps.
     *
     * The counter takes sixteen times the forward speed each frame, and a step
     * lands every time it passes four thousand and ninety-six -- so running
     * makes more of them and ducking, which halves the speed first, makes half
     * as many. The same counter drives {@code PLR1_bobble}, the head sway, from
     * the same number, which is why a footstep and the bottom of a stride are
     * the same moment.
     *
     * Which noise it is comes from the room: {@code ToFloorNoise} chooses
     * between the three footstep samples, and standing in water replaces all of
     * them with the splash. The original computes that as {@code #6-23} and then
     * adds twenty-three back, which is a roundabout way of saying six.
     */
    public void clump(int speed, ab3d.engine.Sound sound) {
        int d3 = Math.max(-50, Math.min(50, speed));
        if (ducked) {
            d3 = M68k.asrW(d3, 1);                  // asr.w #1,d3
        }
        int d2 = M68k.w(d3 * 16);
        int d1 = M68k.w(clumpTime + d2);
        clumpTime = d1 & 4095;
        if ((d1 & -4096) == 0) {
            return;                                 // .noclump
        }
        footstep(sound);
    }

    /** {@code PLR1clump}: which footstep, from the floor underfoot. */
    private void footstep(ab3d.engine.Sound sound) {
        Zone here = level.zone(camera.zone);
        int d0 = here.floorNoise;
        // the water has to be above the floor and above the eye to count
        if (here.waterHeight < here.floorHeight && here.waterHeight >= camera.yoff) {
            d0 = ab3d.engine.Sound.SPLASH - 23;
        }
        if (stoodInTop) {
            d0 = here.upperFloorNoise;
        }
        sound.play(d0 + 23, 0, 100, 80, 0xf9, false);
    }

    /** {@code PLR1_clumptime}. */
    private int clumpTime;

    /** Moves refused for ending behind their start, for checking. */
    public int backslides;

    /**
     * {@code PLR1s_xoff} and {@code PLR1s_zoff}: where the controls have taken
     * the player, sixteen-sixteen, ahead of the walls. Only the whole part goes
     * through {@code MoveObject}; the fraction is kept from frame to frame.
     */
    private long sx, sz;
    /**
     * Where the camera was when {@link #sx} was last made to agree with it.
     * Anything else that moves the camera -- a new level, a teleport -- is
     * seen as a difference, and the controls start again from there.
     */
    private int syncX, syncZ;
    private boolean synced;

    private void resync() {
        if (synced && camera.x == syncX && camera.z == syncZ) {
            return;
        }
        sx = (long) camera.x << 16;
        sz = (long) camera.z << 16;
        syncX = camera.x;
        syncZ = camera.z;
        synced = true;
    }
    /** {@code PLR1s_xspdval}, {@code PLR1s_zspdval}: sixteen-sixteen, a tick. */
    public int xspd, zspd;
    /** {@code PLR1s_angspd}. */
    public int angspd;

    /** {@code move.w #35,d1 / move.w #2,d2}, and {@code #60} and {@code #3} running. */
    private static final int TURN_MAX = 35, TURN_MAX_RUN = 60;
    private static final int ACCEL = 2, ACCEL_RUN = 3;
    /** {@code sub.w #10,d3}: what a turn key adds to the turning speed a tick. */
    private static final int TURN_ACCEL = 10;

    /**
     * {@code PLR1_keyboard_control}: one tick of the vertical blank, fifty a
     * second, whatever the main loop is doing.
     *
     * Nothing here touches a wall. The keys push the two speeds, the speeds
     * lose an eighth of themselves a tick, and the speeds carry the controls'
     * own position along -- so the player accelerates into a walk, coasts to a
     * stop, and turns with the same give. Walking tops out at eight units a
     * tick and running at twelve. The main loop then puts the move through
     * {@code MoveObject} with {@link #applyMove}; a wall that stops the player
     * puts the position back but not the speed, which keeps pushing for the
     * few ticks it takes to die away -- and that is what lets the door under
     * the player's nose see them touching it when they tap the key.
     */
    public void controlTick(ab3d.data.SineTable sine, boolean fwd, boolean back,
                            boolean left, boolean right, boolean slideLeft,
                            boolean slideRight, boolean run,
                            ab3d.engine.Sound sound) {
        resync();
        int d1 = run ? TURN_MAX_RUN : TURN_MAX;
        int d2 = run ? ACCEL_RUN : ACCEL;
        if (ducked) {
            d2 = M68k.asrW(d2, 1);                 // asr.w #1,d2
        }

        // move.w d3,d5 / add.w d5,d5 / add.w d5,d3 / asr.w #2,d3 / bge / addq #1
        int d3 = M68k.asrW(M68k.w(angspd * 3), 2);
        if (d3 < 0) {
            d3++;
        }
        if (left) {
            d3 -= TURN_ACCEL;
        }
        if (right) {
            d3 += TURN_ACCEL;
        }
        d3 = Math.max(-d1, Math.min(d1, d3));
        angspd = d3;
        // add.w d3,d0 twice into angpos, which counts 8192 to the turn
        camera.angle = (camera.angle + d3) & (ab3d.data.SineTable.FULL - 1);

        int d4 = 0;
        if (slideLeft) {
            d4 = M68k.asrW(d2 + d2, 1);
        }
        if (slideRight) {
            d4 = -M68k.asrW(d2 + d2, 1);
        }

        int sin = camera.sin(sine), cos = camera.cos(sine);
        // neg.l / asr.l #3, one more when it was negative: an eighth, rounded
        int d6 = friction(xspd);
        int d7 = friction(zspd);

        int f = 0;
        if (fwd) {
            f = -d2;
        }
        if (back) {
            f = d2;
        }
        // asl.w #6,d2 / add.w PLR1_clumptime,d2: the footsteps run off the same
        int step = M68k.w(f << 6);
        int c = M68k.w(clumpTime + step);
        clumpTime = c & 4095;
        if ((c & -4096) != 0 && sound != null) {
            footstep(sound);
        }

        d6 -= M68k.muls(sin, f);
        d7 -= M68k.muls(cos, f);
        d6 -= M68k.muls(cos, d4);
        d7 += M68k.muls(sin, d4);
        xspd += d6;
        zspd += d7;
        sx += xspd;
        sz += zspd;
    }

    private static int friction(int spd) {
        int d = -spd;
        return d > 0 ? (d >> 3) + 1 : d >> 3;
    }

    /**
     * {@code PLR1_Control}: the main loop's half -- from where the player is to
     * where the controls have taken them, through the walls.
     *
     * {@code move.w newx,PLR1_xoff} writes only the whole part, so the fraction
     * the controls built up survives a wall.
     */
    public void applyMove() {
        resync();
        int nx = (int) (sx >> 16), nz = (int) (sz >> 16);
        if (nx != camera.x || nz != camera.z) {
            move(M68k.w(nx - camera.x), M68k.w(nz - camera.z));
        }
        sx = ((long) camera.x << 16) | (sx & 0xffff);
        sz = ((long) camera.z << 16) | (sz & 0xffff);
        syncX = camera.x;
        syncZ = camera.z;
    }

    /**
     * Whether a move that comes out behind where it started is thrown away.
     *
     * On, like {@code MoveObject.REFUSE_STRAY} and for the same reason: the
     * transcribed slide leaves a residue the original's does not. Pushed into
     * a corner -- a wall and an opening meeting at an angle, as by the start of
     * the first level -- the two projections put the player a few units
     * <em>back</em> from one start and forward again from the next, so they
     * shuttle between two points every frame and the wall in front of their
     * nose shakes. With the controls' inertia that goes on for the ticks the
     * speed takes to die away. A move that ends against its own direction is
     * refused instead; the doors still see the touch, which the mover flagged
     * on its way.
     */
    public static boolean REFUSE_BACKSLIDE = true;

    /**
     * {@code Collision} for the player: jg.s calls it before {@code MoveObject}
     * with {@code CollId} at -1 and these flags -- the enemies, the barrel,
     * the robot -- and a hit is {@code .cantmove}: the position stays, the
     * controls' speed does not.
     */
    public ab3d.engine.Collision collision;
    /** {@code move.l #%1011111110111000001,CollideFlags}. */
    private static final int COLLIDE_FLAGS = 0b1011111110111000001;
    /**
     * Whose half-width the player has. {@code CollId} is -1, so the original
     * reads the type byte of the record before the first one -- whatever lies
     * there. The player's own row of the table is what it was meant to be.
     */
    private static final int OWN_TYPE = 5;

    public void move(int dx, int dz) {
        int x0 = camera.x, z0 = camera.z;
        if (collision != null && collision.blocked(-1, OWN_TYPE, stoodInTop,
                camera.yoff, height, x0, z0, x0 + dx, z0 + dz, COLLIDE_FLAGS)) {
            return;                          // bra .cantmove
        }
        mover.enterStorey(stoodInTop);       // move.b PLR1_StoodInTop,StoodInTop
        mover.move(camera.zone, camera.x, camera.z,
                   camera.x + dx, camera.z + dz,
                   camera.yoff, camera.yoff,
                   EXT_LEN, height, ducked ? STEP_UP_DUCKED : STEP_UP, STEP_DOWN,
                   ab3d.engine.Doors.PLAYER1, AWAY_FROM_WALL);
        if (REFUSE_BACKSLIDE
                && (long) (mover.x - x0) * dx + (long) (mover.z - z0) * dz < 0) {
            backslides++;
            return;                          // stay where the last move left them
        }
        // move.b StoodInTop,PLR1_StoodInTop: the mover decides which storey
        stoodInTop = mover.stoodInTop;
        camera.x = mover.x;
        camera.z = mover.z;
        if (mover.zone >= 0 && mover.zone < level.zones.length && fits(mover.zone)) {
            camera.zone = mover.zone;
        }
        settleHeight();
    }


    public void turn(int delta) {
        camera.angle = (camera.angle + delta) & (ab3d.data.SineTable.FULL - 1);
    }

    /** {@code move.w #40,extlen}: how wide the player counts as. */
    private static final int EXT_LEN = 40;
    /**
     * {@code move.b #0,awayfromwall}: held off a wall by the line's own normal.
     *
     * Without it the player walks until their centre is on the line, which puts
     * the eye inside the masonry -- the wall's own surface is then behind the
     * near plane, the clip discards it, and the room is seen from within its own
     * wall. The things that walk use one, which is twice as far; a bullet uses a
     * negative value and is held off nothing.
     */
    private static final int AWAY_FROM_WALL = 0;
    /** {@code cmp.w #32,d0}: under this and the line is flagged. */
    private static final int TOUCH_RANGE = 32;

    /**
     * Is there room to stand in a zone?
     *
     * Without this the player walks straight into a closed door, which is a zone
     * whose roof has come down to its floor: the volume is nil, so nothing draws
     * and there is no way back out except the way in. The engine's own control
     * code tests headroom properly, along with step height and crouching; this
     * is the one part of it here, and it is not a transcription.
     */
    private boolean fits(int zoneIndex) {
        Zone z = level.zone(zoneIndex);
        // the headroom of the storey being stood on, not always the lower one
        return stoodInTop
                ? z.upperFloorHeight - z.upperRoofHeight >= height
                : z.floorHeight - z.roofHeight >= height;
    }

    /**
     * Plants the eye at the standing height above the floor under it.
     *
     * {@code .cantmove} in jg.s: {@code move.l ToZoneFloor(a0),d0 /
     * tst.b PLR1_StoodInTop / beq notintop / move.l ToUpperFloor(a0),d0}, and
     * then the player's own height is taken off. Which floor is the one the
     * storey flag names, so standing upstairs in a room over a room puts the eye
     * on the upper floor rather than the one below it.
     */
    private void settleHeight() {
        Zone z = level.zone(camera.zone);
        camera.yoff = (stoodInTop ? z.upperFloorHeight : z.floorHeight) - height;
    }
}
