package ab3d.game;

import ab3d.data.SineTable;

/** Player / view state, in the engine's own units. */
public final class Camera {

    /** Horizontal position, in level units (the same units as the point table). */
    public int x, z;
    /** Eye height. Y grows downwards and is about 256 times finer than x/z. */
    public int yoff;
    /** Facing, 0..4095 for a full turn, matching the sine table. */
    public int angle;
    /** Zone the camera is currently standing in. */
    public int zone;

    public int sin(SineTable t) {
        return t.sin(angle);
    }

    public int cos(SineTable t) {
        return t.cos(angle);
    }
}
