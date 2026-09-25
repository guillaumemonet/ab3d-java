package ab3d;

import ab3d.data.Border;
import ab3d.data.Controls;
import ab3d.data.FloorTexture;
import ab3d.data.GameData;
import ab3d.data.GunData;
import ab3d.data.Level;
import ab3d.data.SineTable;
import ab3d.data.SpriteBank;
import ab3d.data.WallTextures;
import ab3d.game.Hud;
import ab3d.game.Noise;
import ab3d.game.KeyMap;
import ab3d.game.Shell;
import ab3d.game.Player;
import ab3d.engine.EngineState;
import ab3d.engine.Frame68k;
import ab3d.render.Framebuffer;
import ab3d.render.PortalRenderer;

import com.jme3.app.SimpleApplication;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.event.JoyAxisEvent;
import com.jme3.input.event.JoyButtonEvent;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.input.event.TouchEvent;
import com.jme3.system.AppSettings;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.ui.Picture;
import com.jme3.util.BufferUtils;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Alien Breed 3D, rendered by the original software rasterizer logic and put on
 * screen through jMonkeyEngine.
 *
 * jME's job here is deliberately small: open a window, own the input, and blit
 * one texture. Everything inside the view is computed on the CPU.
 *
 * The internal resolution follows {@code -Dab3d.res=N}: the engine's own view is
 * 192 x 80 displayed pixels, which is the default, and N multiplies both axes
 * without touching the field of view.
 */
public final class Ab3dGame extends SimpleApplication
        implements RawInputListener {

    /** Roughly how wide the window should be, before rounding to a whole scale. */
    private static final int TARGET_WINDOW_WIDTH = 1152;
    /**
     * The screen the original describes, read off three independent places.
     *
     * The status picture is three hundred and twenty across and ninety-six deep.
     * The border sprites sit at horizontal positions sixty-four and a hundred
     * and ninety-two and are sixty-four pixels wide, so they take the two edges
     * and leave a hundred and ninety-two between them -- which is the engine's
     * ninety-six columns doubled. And they run from line fifty-two to line two
     * hundred and twelve, a hundred and sixty lines, which is its eighty rows
     * doubled. So the whole display is a PAL screen: view and borders over the
     * panel, and every part of it a whole multiple of what the engine draws.
     */
    private static final int SCREEN_WIDTH = 320;
    private static final int PANEL_HEIGHT = 96;
    private static final int VIEW_HEIGHT = 160;
    private static final int SCREEN_HEIGHT = VIEW_HEIGHT + PANEL_HEIGHT;
    /** {@code move.w #52*256+64,borders}: where the view starts across. */
    private static final int VIEW_LEFT = 64;
    private static final int VIEW_WIDTH = 192;
    /**
     * The key code for each letter, A to Z.
     *
     * jME's codes are the keyboard's own scan codes, which run along the rows
     * rather than through the alphabet, so the letters are named one by one
     * instead of counted from {@code KEY_A}.
     */
    private static final int[] LETTER_KEYS = {
        KeyInput.KEY_A, KeyInput.KEY_B, KeyInput.KEY_C, KeyInput.KEY_D,
        KeyInput.KEY_E, KeyInput.KEY_F, KeyInput.KEY_G, KeyInput.KEY_H,
        KeyInput.KEY_I, KeyInput.KEY_J, KeyInput.KEY_K, KeyInput.KEY_L,
        KeyInput.KEY_M, KeyInput.KEY_N, KeyInput.KEY_O, KeyInput.KEY_P,
        KeyInput.KEY_Q, KeyInput.KEY_R, KeyInput.KEY_S, KeyInput.KEY_T,
        KeyInput.KEY_U, KeyInput.KEY_V, KeyInput.KEY_W, KeyInput.KEY_X,
        KeyInput.KEY_Y, KeyInput.KEY_Z,
    };
    /** Height of the debug line drawn under everything else. */
    private static final int STATUS_HEIGHT = 24;

    private static final int WALK_SPEED = 260;   // level units per second
    private static final int TURN_SPEED = 1400;  // sine-table steps per second

    private static int resolution() {
        return Math.max(1, Math.min(8, Integer.getInteger("ab3d.res", 1)));
    }

    private static int displayScale(int viewWidth) {
        return Math.max(1, TARGET_WINDOW_WIDTH / viewWidth);
    }

    /** The composite screen: the view and its borders, the status panel below. */
    private byte[] screen;
    private Hud panel;
    /**
     * {@code Energy} and {@code Ammo}, which jg.s reads afresh every frame:
     * {@code move.w PLR1_energy,Energy}, and the ammunition from the selected
     * gun's own record, {@code move.w (a6),d0 / asr.w #3,d0}.
     *
     * Medikits and ammunition clips move them. Nothing takes them down yet,
     * because that needs the shooting, but the path from the object to the bar
     * is the original's throughout.
     */
    private int energy = Border.ENERGY_MAX, ammo = Border.AMMO_MAX;
    /** The title screen and the option screens, up until the level starts. */
    private Shell shell;
    /** {@code MakeSomeNoise} and the four channels it writes to. */
    private Noise noise;

    private String levelName = "level_a";

    private Level level;
    private Player player;
    private PortalRenderer renderer;
    /** The transcribed chain, used unless -Dab3d.renderer=old is given. */
    private Frame68k frame;
    private boolean useTranscribed = !"old".equals(System.getProperty("ab3d.renderer"));
    private SineTable sine;
    private Framebuffer framebuffer;

    private ByteBuffer imageBuffer;
    private Image image;
    private byte[] rgba;
    private BitmapText hud;

    /** {@code KeyMap}: which raw keys are down. */
    private final KeyMap keys = new KeyMap();
    /**
     * {@code CONTROLBUFFER}: which raw key each action is bound to.
     *
     * The shell owns it, because {@code CHANGECONTROLS} is a menu screen and
     * writes into the same buffer the game reads. Two copies would let a
     * rebinding show on the screen and change nothing in play.
     */
    private Controls controls;
    /** {@code PLR1_SPCTAP}: set on the press, cleared once the doors have seen it. */
    private boolean spaceTapped;
    /** {@code OldSpace}: so a held key opens a door once rather than every frame. */
    private boolean oldSpace;
    /** {@code PLR1_fire} and {@code PLR1_clicked}, from PLR1CONTROL.s. */
    private boolean oldFire;

    public static void main(String[] args) {
        Ab3dGame app = new Ab3dGame();
        if (args.length > 0) {
            app.levelName = args[0];
        }

        int scale = displayScale(SCREEN_WIDTH);

        AppSettings settings = new AppSettings(true);
        settings.setTitle("Alien Breed 3D - Java / jMonkeyEngine");
        settings.setResolution(SCREEN_WIDTH * scale,
                               SCREEN_HEIGHT * scale + STATUS_HEIGHT);
        settings.setResizable(true);
        settings.setVSync(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setDisplayStatView(false);
        app.setDisplayFps(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        framebuffer = new Framebuffer(resolution());
        try {
            GameData game = GameData.fromSystemProperty();
            level = Level.load(game, levelName);
            sine = SineTable.load(game);
            panel = new Hud(game);
            noise = new Noise(ab3d.data.Samples.load(game));
            if (!noise.open()) {
                System.out.println("no audio output; the game runs silent");
            }
            shell = new Shell(game);
            controls = shell.controls;
            shell.setLevel(levelName.charAt(levelName.length() - 1) - 'a');
            if (useTranscribed) {
                frame = new Frame68k(level, game);
                frame.setSound(noise);
            } else {
                renderer = new PortalRenderer(level, sine, new WallTextures(game),
                        FloorTexture.load(game), new SpriteBank(game), framebuffer);
            }
            player = new Player(level);
        } catch (Exception e) {
            throw new RuntimeException("Could not load the original AB3D data. "
                    + "Point -Dab3d.root at the ab3d-rtg checkout and -Dab3d.disk at "
                    + "the extracted floppies.", e);
        }

        flyCam.setEnabled(false);
        inputManager.setCursorVisible(true);

        rgba = new byte[framebuffer.rgbaSize()];
        screen = new byte[SCREEN_WIDTH * SCREEN_HEIGHT * 4];
        imageBuffer = BufferUtils.createByteBuffer(screen.length);
        image = new Image(Image.Format.RGBA8, SCREEN_WIDTH, SCREEN_HEIGHT,
                imageBuffer, ColorSpace.sRGB);

        Texture2D texture = new Texture2D(image);
        texture.setMagFilter(Texture.MagFilter.Nearest);
        texture.setMinFilter(Texture.MinFilter.NearestNoMipMaps);

        int scale = displayScale(SCREEN_WIDTH);
        Picture view = new Picture("ab3d-view");
        view.setTexture(assetManager, texture, false);
        view.setWidth(SCREEN_WIDTH * scale);
        view.setHeight(SCREEN_HEIGHT * scale);
        view.setPosition(0, STATUS_HEIGHT);
        guiNode.attachChild(view);

        hud = new BitmapText(guiFont);
        hud.setSize(14);
        hud.setLocalTranslation(6, STATUS_HEIGHT - 6, 0);
        guiNode.attachChild(hud);

        // Every key goes into KeyMap, and the bindings decide what each one
        // does -- which is the original's own arrangement, and the only one in
        // which CHANGECONTROLS can mean anything.
        inputManager.addRawInputListener(this);
    }

    @Override
    public void onKeyEvent(KeyInputEvent e) {
        if (e.isRepeating()) {
            return;
        }
        keys.set(e.getKeyCode(), e.isPressed());
        if (!e.isPressed()) {
            return;
        }
        if (shell != null && shell.state != Shell.State.PLAY) {
            menuKey(e.getKeyCode());
            return;
        }
        int raw = KeyMap.raw(e.getKeyCode());
        if (raw == controls.key(Controls.Action.DUCK)) {
            player.toggleDuck();                    // clr.b (a5,d7.w): once only
            keys.take(raw);
        }
        // pickweap: the number keys one to five, through GUNVALS -- which is
        // 0, 7, 1, 4, 2, not the slots in order -- and only for a gun the
        // player actually has, which is what the gotgun byte says
        if (raw >= 0x01 && raw <= GunData.GUNVALS.length && useTranscribed) {
            int slot = GunData.GUNVALS[raw - 1];
            if (frame.gunData.has(slot)) {             // tst.b 7(a3,d2.w)
                frame.gunSelected = slot;
                frame.gunFrame = 0;
            }
        }
    }

    @Override
    public void beginInput() {
    }

    @Override
    public void endInput() {
    }

    @Override
    public void onJoyAxisEvent(JoyAxisEvent e) {
    }

    @Override
    public void onJoyButtonEvent(JoyButtonEvent e) {
    }

    @Override
    public void onMouseMotionEvent(MouseMotionEvent e) {
    }

    @Override
    public void onMouseButtonEvent(MouseButtonEvent e) {
    }

    @Override
    public void onTouchEvent(TouchEvent e) {
    }

    /**
     * {@code CHECKMENU}: up and down move without wrapping, and the fire
     * button or the space bar chooses.
     *
     * The screens this cannot yet go to -- the controls, the credits and the
     * password -- are left where the original leaves them, as separate entries
     * of {@code MENUDATA}; showing one and coming back is all that is missing.
     */
    private void menuKey(int code) {
        int raw = KeyMap.raw(code);

        if (shell.waitingForKey()) {
            shell.bindKey(raw);                     // move.b d1,(a1,d0.w)
            return;
        }
        if (shell.typingPassword()) {
            switch (code) {
                case KeyInput.KEY_RETURN, KeyInput.KEY_ESCAPE -> shell.cancelPassword();
                case KeyInput.KEY_BACK, KeyInput.KEY_DELETE -> shell.backspace();
                default -> {
                    String name = controls.name(raw).trim();
                    if (name.length() == 1) {
                        shell.type(name.charAt(0));
                    }
                }
            }
            if (shell.levelChanged) {
                shell.levelChanged = false;
                loadLevel(shell.levelFile());
            }
            return;
        }
        switch (code) {
            case KeyInput.KEY_UP -> shell.up();
            case KeyInput.KEY_DOWN -> shell.down();
            case KeyInput.KEY_ESCAPE -> shell.show("ONEPLAYERMENU_TXT");
            case KeyInput.KEY_SPACE, KeyInput.KEY_RETURN, KeyInput.KEY_NUMPADENTER -> {
                switch (shell.select()) {
                    case PLAY -> shell.state = Shell.State.PLAY;
                    case PASSWORD -> shell.startPassword();
                    case CREDITS -> shell.show("CREDITMENU_TXT");
                    case CONTROLS -> shell.show("CONTROL_TXT");
                    default -> { }
                }
            }
            default -> { }
        }
    }

    /**
     * Loads another level, as {@code PLAYTHEGAME} does when it comes back round.
     *
     * The port has four of the sixteen the password can name, so a level that is
     * not on the disk leaves the one already loaded in place rather than failing
     * -- the menu will still be showing its name, which is the honest state of
     * things: the name comes from {@code LEVEL_OPTS}, the level from the disk.
     */
    private void loadLevel(String name) {
        try {
            GameData game = GameData.fromSystemProperty();
            Level next = Level.load(game, name);
            level = next;
            levelName = name;
            player = new Player(level);
            if (useTranscribed) {
                frame = new Frame68k(level, game);
                frame.setSound(noise);
            }
        } catch (Exception e) {
            // not on this disk; the menu keeps naming it, nothing else changes
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (shell.state != Shell.State.PLAY) {
            shell.tick();
            java.util.Arrays.fill(screen, (byte) 0);
            shell.draw(screen, SCREEN_WIDTH * 4);
            blit();
            hud.setText(shell.state == Shell.State.TITLE
                        ? "" : "cursors to choose, space or return to select");
            return;
        }
        update(tpf);

        // PLR1_alwayskeys: the operate key counts once per press, not per frame
        boolean operate = keys.down(controls.key(Controls.Action.OPERATE));
        if (operate && !oldSpace) {
            spaceTapped = true;
        }
        oldSpace = operate;

        // PLR1CONTROL.s: fire is the key held, clicked is the frame it went down
        boolean fire = keys.down(controls.key(Controls.Action.FIRE));
        boolean clicked = fire && !oldFire;
        oldFire = fire;

        if (useTranscribed) {
            frame.lookBehind = keys.down(controls.key(Controls.Action.LOOK_BEHIND));
            frame.updateSwitches(1, player.camera.x, player.camera.z, spaceTapped);
            frame.updateDoors(1, spaceTapped);
            frame.updateLifts(1, player.camera.zone, spaceTapped);
            frame.updateObjects(player.camera.zone, player.camera.x,
                                player.camera.z, player.camera.yoff,
                                player.stoodInTop, 1, player.camera.angle);
            frame.armObjects(player.camera.zone);
            // Player1Shot is the first thing objmoveanim does, but the aim it
            // reads is worked out from the frame just drawn
            frame.firePlayer(fire, clicked, 1, player.camera.x, player.camera.z,
                             player.camera.yoff, Player.EYE_HEIGHT,
                             player.camera.angle, player.camera.zone,
                             player.stoodInTop);
            // move.w PLR1_energy,Energy, and the ammunition from the gun in hand
            // USEPLR1 takes what the enemies did, then the medikits give back
            frame.objectHandler.energy = frame.usePlayer(frame.objectHandler.energy);
            energy = frame.objectHandler.energy;
            ammo = frame.gunData.shownAmmo(frame.gunSelected);
            spaceTapped = false;
            frame.render(player.camera.zone, player.camera.x, player.camera.z,
                         player.camera.yoff, player.camera.angle);
            toRgba(frame.state(), rgba);
        } else {
            renderer.render(framebuffer, player.camera);
            framebuffer.toRgba(rgba);
        }
        stretchView();
        panel.setKeys(useTranscribed ? frame.conditions : 0);
        panel.setBars(energy, ammo);
        drawBorders();
        drawPanel();

        blit();

        hud.setText(useTranscribed ? String.format(
                "%s  zone %3d   x %5d  z %5d   angle %4d   "
                + "zones %d  walls %d  floors %d  sprites %d",
                level.name, player.camera.zone, player.camera.x, player.camera.z,
                player.camera.angle, frame.zonesDrawn, frame.wallsDrawn,
                frame.surfacesDrawn, frame.spritesDrawn)
            : String.format(
                "%s  zone %3d   x %5d  z %5d   angle %4d   zones %d  sprites %d  [old]",
                level.name, player.camera.zone, player.camera.x, player.camera.z,
                player.camera.angle, renderer.lastVisitCount, renderer.lastSpriteCount));
    }

    /** jME textures start at the bottom row, the composite screen at the top. */
    private void blit() {
        imageBuffer.clear();
        int stride = SCREEN_WIDTH * 4;
        for (int y = SCREEN_HEIGHT - 1; y >= 0; y--) {
            imageBuffer.put(screen, y * stride, stride);
        }
        imageBuffer.flip();
        image.setUpdateNeeded();
    }

    /** The rendered view into the middle of the screen, between the borders. */
    private void stretchView() {
        int fw = framebuffer.width, fh = framebuffer.height;
        int src = fw * 4, dst = SCREEN_WIDTH * 4;
        for (int y = 0; y < VIEW_HEIGHT; y++) {
            int sy = y * fh / VIEW_HEIGHT;
            for (int x = 0; x < VIEW_WIDTH; x++) {
                int sx = x * fw / VIEW_WIDTH;
                System.arraycopy(rgba, sy * src + sx * 4, screen,
                                 y * dst + (VIEW_LEFT + x) * 4, 4);
            }
        }
    }

    /**
     * The two border sprites down the edges, over the same lines as the view.
     *
     * Colour zero is the sprites' transparent one, and here it is simply black:
     * there is nothing behind them to show through, since the view does not
     * reach that far.
     */
    private void drawBorders() {
        int[] pal = panel.border.palette;
        int dst = SCREEN_WIDTH * 4;
        for (int pair = 0; pair < 2; pair++) {
            byte[] px = panel.border.pixels(pair);
            int x0 = pair == 0 ? 0 : VIEW_LEFT + VIEW_WIDTH;
            for (int y = 0; y < VIEW_HEIGHT; y++) {
                int at = y * dst + x0 * 4;
                for (int x = 0; x < Border.WIDTH; x++) {
                    int c = pal[px[y * Border.WIDTH + x] & 0xf];
                    screen[at++] = (byte) (c >> 16);
                    screen[at++] = (byte) (c >> 8);
                    screen[at++] = (byte) c;
                    screen[at++] = (byte) 0xff;
                }
            }
        }
    }

    /** The status panel under it, one pixel of the picture to one of the screen. */
    private void drawPanel() {
        byte[] px = panel.pixels();
        int[] pal = panel.palette();
        int dst = SCREEN_WIDTH * 4;
        for (int y = 0; y < PANEL_HEIGHT; y++) {
            int at = (VIEW_HEIGHT + y) * dst;
            for (int x = 0; x < SCREEN_WIDTH; x++) {
                int c = pal[px[y * Hud.WIDTH + x] & 0xff];
                screen[at++] = (byte) (c >> 16);
                screen[at++] = (byte) (c >> 8);
                screen[at++] = (byte) c;
                screen[at++] = (byte) 0xff;
            }
        }
    }

    /**
     * The 96 x 80 view buffer into the RGBA image.
     *
     * The engine's rows are 104 four-byte slots wide and the view uses the first
     * 96 of them, so a pixel is two words apart. The display is twice as wide as
     * it is deep in engine columns -- 192 by 80 -- so the horizontal and vertical
     * scales are worked out separately; using one for both squashes the view into
     * the top half of the window.
     */
    private void toRgba(EngineState st, byte[] out) {
        int sx = Math.max(1, framebuffer.width / EngineState.VIEW_COLUMNS);
        int sy = Math.max(1, framebuffer.height / EngineState.VIEW_ROWS);
        int stride = framebuffer.width * 4;
        for (int row = 0; row < EngineState.VIEW_ROWS; row++) {
            int base = st.rowStart(row);
            for (int col = 0; col < EngineState.VIEW_COLUMNS; col++) {
                int c = st.screen[base + EngineState.columnWord(col)] & 0x0fff;
                int r = (c >> 8) & 0xf, g = (c >> 4) & 0xf, b = c & 0xf;
                byte rr = (byte) (r | (r << 4));
                byte gg = (byte) (g | (g << 4));
                byte bb = (byte) (b | (b << 4));
                for (int dy = 0; dy < sy; dy++) {
                    int y = row * sy + dy;
                    if (y >= framebuffer.height) {
                        break;
                    }
                    for (int dx = 0; dx < sx; dx++) {
                        int x = col * sx + dx;
                        if (x >= framebuffer.width) {
                            break;
                        }
                        int at = y * stride + x * 4;
                        out[at] = rr;
                        out[at + 1] = gg;
                        out[at + 2] = bb;
                        out[at + 3] = (byte) 0xff;
                    }
                }
            }
        }
    }

    /**
     * The movement half of {@code PLR1CONTROL.s}, read through {@code KeyMap}.
     *
     * The sidestep swap is the original's: while the force-sidestep key is held,
     * {@code templeftkey} and {@code temprightkey} take the sidestep keys' place
     * and the turn keys are set to two hundred and fifty-five -- a code no key
     * has, so turning simply stops rather than being tested for.
     */
    private void update(float tpf) {
        player.tick();

        int left = controls.key(Controls.Action.TURN_LEFT);
        int right = controls.key(Controls.Action.TURN_RIGHT);
        int stepLeft = controls.key(Controls.Action.SIDESTEP_LEFT);
        int stepRight = controls.key(Controls.Action.SIDESTEP_RIGHT);
        if (keys.down(controls.key(Controls.Action.FORCE_SIDESTEP))) {
            stepLeft = left;
            stepRight = right;
            left = 255;                             // move.b #255,templeftkey
            right = 255;
        }

        int turn = 0;
        if (keys.down(left)) {
            turn -= 1;
        }
        if (keys.down(right)) {
            turn += 1;
        }
        if (turn != 0) {
            player.turn(Math.round(turn * TURN_SPEED * tpf));
        }

        int fwd = 0, side = 0;
        if (keys.down(controls.key(Controls.Action.FORWARD))) {
            fwd += 1;
        }
        if (keys.down(controls.key(Controls.Action.BACKWARD))) {
            fwd -= 1;
        }
        if (keys.down(stepRight)) {
            side += 1;
        }
        if (keys.down(stepLeft)) {
            side -= 1;
        }
        if (fwd == 0 && side == 0) {
            return;
        }

        boolean running = keys.down(controls.key(Controls.Action.RUN));
        float speed = WALK_SPEED * tpf * (running ? 2f : 1f);
        // PLR1_clumptime: the footsteps, which the forward speed drives
        if (noise != null && fwd != 0) {
            player.clump(fwd * (running ? 50 : 25), noise);
        }
        double sin = player.camera.sin(sine) / 32768.0;
        double cos = player.camera.cos(sine) / 32768.0;
        double dx = (fwd * sin + side * cos) * speed;
        double dz = (fwd * cos - side * sin) * speed;
        player.move((int) Math.round(dx), (int) Math.round(dz));
    }
}
