package ab3d.game;

import com.jme3.input.KeyInput;

/**
 * {@code KeyMap}: a byte per Amiga raw key, set while the key is down.
 *
 * The game reads the keyboard through this and nothing else --
 * {@code move.b operate_key,d7 / tst.b (a5,d7.w)} -- so the configured bindings
 * are raw key codes and every routine that wants a key indexes the same array.
 * Keeping that shape here rather than binding named actions to jME keys is what
 * lets {@code CHANGECONTROLS} mean anything: rebinding writes a number into
 * {@code CONTROLBUFFER}, and the number is only useful if the key state is
 * indexed the same way.
 *
 * {@code clr.b (a5,d7.w)} appears where the original wants a key to count once
 * rather than repeat -- the duck key does it -- so clearing an entry is part of
 * reading it, not a convenience added here.
 */
public final class KeyMap {

    /** Raw key codes run from nought to {@code $67}. */
    public static final int SIZE = 0x68;

    private final boolean[] down = new boolean[SIZE];

    /**
     * jME key code to Amiga raw key.
     *
     * The two are unrelated numberings -- jME's are the keyboard's own scan
     * codes -- so the crossing is written out. Anything not here simply never
     * reaches the game, which is the same as a key the Amiga had no code for.
     */
    private static final int[][] CROSSING = {
        {KeyInput.KEY_GRAVE, 0x00},
        {KeyInput.KEY_1, 0x01}, {KeyInput.KEY_2, 0x02}, {KeyInput.KEY_3, 0x03},
        {KeyInput.KEY_4, 0x04}, {KeyInput.KEY_5, 0x05}, {KeyInput.KEY_6, 0x06},
        {KeyInput.KEY_7, 0x07}, {KeyInput.KEY_8, 0x08}, {KeyInput.KEY_9, 0x09},
        {KeyInput.KEY_0, 0x0a}, {KeyInput.KEY_MINUS, 0x0b},
        {KeyInput.KEY_EQUALS, 0x0c}, {KeyInput.KEY_BACKSLASH, 0x0d},
        {KeyInput.KEY_Q, 0x10}, {KeyInput.KEY_W, 0x11}, {KeyInput.KEY_E, 0x12},
        {KeyInput.KEY_R, 0x13}, {KeyInput.KEY_T, 0x14}, {KeyInput.KEY_Y, 0x15},
        {KeyInput.KEY_U, 0x16}, {KeyInput.KEY_I, 0x17}, {KeyInput.KEY_O, 0x18},
        {KeyInput.KEY_P, 0x19}, {KeyInput.KEY_LBRACKET, 0x1a},
        {KeyInput.KEY_RBRACKET, 0x1b},
        {KeyInput.KEY_NUMPAD1, 0x1d}, {KeyInput.KEY_NUMPAD2, 0x1e},
        {KeyInput.KEY_NUMPAD3, 0x1f},
        {KeyInput.KEY_A, 0x20}, {KeyInput.KEY_S, 0x21}, {KeyInput.KEY_D, 0x22},
        {KeyInput.KEY_F, 0x23}, {KeyInput.KEY_G, 0x24}, {KeyInput.KEY_H, 0x25},
        {KeyInput.KEY_J, 0x26}, {KeyInput.KEY_K, 0x27}, {KeyInput.KEY_L, 0x28},
        {KeyInput.KEY_SEMICOLON, 0x29}, {KeyInput.KEY_APOSTROPHE, 0x2a},
        {KeyInput.KEY_NUMPAD4, 0x2d}, {KeyInput.KEY_NUMPAD5, 0x2e},
        {KeyInput.KEY_NUMPAD6, 0x2f},
        {KeyInput.KEY_Z, 0x31}, {KeyInput.KEY_X, 0x32}, {KeyInput.KEY_C, 0x33},
        {KeyInput.KEY_V, 0x34}, {KeyInput.KEY_B, 0x35}, {KeyInput.KEY_N, 0x36},
        {KeyInput.KEY_M, 0x37}, {KeyInput.KEY_COMMA, 0x38},
        {KeyInput.KEY_PERIOD, 0x39}, {KeyInput.KEY_SLASH, 0x3a},
        {KeyInput.KEY_NUMPAD7, 0x3d}, {KeyInput.KEY_NUMPAD8, 0x3e},
        {KeyInput.KEY_NUMPAD9, 0x3f},
        {KeyInput.KEY_SPACE, 0x40}, {KeyInput.KEY_BACK, 0x41},
        {KeyInput.KEY_TAB, 0x42}, {KeyInput.KEY_NUMPADENTER, 0x43},
        {KeyInput.KEY_RETURN, 0x44}, {KeyInput.KEY_ESCAPE, 0x45},
        {KeyInput.KEY_DELETE, 0x46}, {KeyInput.KEY_SUBTRACT, 0x4a},
        {KeyInput.KEY_UP, 0x4c}, {KeyInput.KEY_DOWN, 0x4d},
        {KeyInput.KEY_RIGHT, 0x4e}, {KeyInput.KEY_LEFT, 0x4f},
        {KeyInput.KEY_F1, 0x50}, {KeyInput.KEY_F2, 0x51}, {KeyInput.KEY_F3, 0x52},
        {KeyInput.KEY_F4, 0x53}, {KeyInput.KEY_F5, 0x54}, {KeyInput.KEY_F6, 0x55},
        {KeyInput.KEY_F7, 0x56}, {KeyInput.KEY_F8, 0x57}, {KeyInput.KEY_F9, 0x58},
        {KeyInput.KEY_F10, 0x59},
        {KeyInput.KEY_DIVIDE, 0x5c}, {KeyInput.KEY_MULTIPLY, 0x5d},
        {KeyInput.KEY_ADD, 0x5e},
        {KeyInput.KEY_LSHIFT, 0x60}, {KeyInput.KEY_RSHIFT, 0x61},
        {KeyInput.KEY_CAPITAL, 0x62}, {KeyInput.KEY_LCONTROL, 0x63},
        {KeyInput.KEY_RCONTROL, 0x63},
        {KeyInput.KEY_LMENU, 0x64}, {KeyInput.KEY_RMENU, 0x65},
        {KeyInput.KEY_LMETA, 0x66}, {KeyInput.KEY_RMETA, 0x67},
    };

    private static final int[] TO_RAW = buildToRaw();

    private static int[] buildToRaw() {
        int max = 0;
        for (int[] pair : CROSSING) {
            max = Math.max(max, pair[0]);
        }
        int[] out = new int[max + 1];
        java.util.Arrays.fill(out, -1);
        for (int[] pair : CROSSING) {
            out[pair[0]] = pair[1];
        }
        return out;
    }

    /** The raw key a jME key code stands for, or -1. */
    public static int raw(int jmeCode) {
        return jmeCode >= 0 && jmeCode < TO_RAW.length ? TO_RAW[jmeCode] : -1;
    }

    public void set(int jmeCode, boolean pressed) {
        int r = raw(jmeCode);
        if (r >= 0 && r < SIZE) {
            down[r] = pressed;
        }
    }

    /** {@code tst.b (a5,d7.w)}. */
    public boolean down(int rawKey) {
        return rawKey >= 0 && rawKey < SIZE && down[rawKey];
    }

    /** {@code clr.b (a5,d7.w)}: read it once and put it back down. */
    public boolean take(int rawKey) {
        if (!down(rawKey)) {
            return false;
        }
        down[rawKey] = false;
        return true;
    }

    public void clear() {
        java.util.Arrays.fill(down, false);
    }
}
