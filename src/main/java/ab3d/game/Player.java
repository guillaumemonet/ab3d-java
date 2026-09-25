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

    public void move(int dx, int dz) {
        mover.enterStorey(stoodInTop);       // move.b PLR1_StoodInTop,StoodInTop
        mover.move(camera.zone, camera.x, camera.z,
                   camera.x + dx, camera.z + dz,
                   camera.yoff, camera.yoff,
                   EXT_LEN, height, ducked ? STEP_UP_DUCKED : STEP_UP, STEP_DOWN,
                   ab3d.engine.Doors.PLAYER1, AWAY_FROM_WALL);
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
