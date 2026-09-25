package ab3d.game;

import ab3d.data.Controls;
import ab3d.data.GameData;
import ab3d.data.LevelNames;
import ab3d.data.MenuData;
import ab3d.data.OptFont;
import ab3d.data.TitleScreen;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Everything before the game starts: the title picture, its fade, and the option
 * screens over it.
 *
 * source/CONTROLLOOP.s opens with the order written out as a comment -- load the
 * title screen, fade it up, select options, play the level, come back -- and
 * that is what this is. The parts it leaves out are the ones that have nowhere
 * to go here: the disk prompt, the module player, and the copy protection.
 *
 * The colours are worth a word because they are not in any palette. An option
 * screen is drawn into five hardware sprites, so a pixel has three states --
 * glyph, highlight, or both -- and {@code OPTCOP} in source/titlecop.s sets the
 * three colours afresh on every one of the two hundred and fifty-six scanlines.
 * Two of them ramp over eight lines and back, which is the shimmer down the
 * text; the third is overwritten by {@code putinrain} from includes/optcop, a
 * grey ripple of the same period. So the menu's own colours change down the
 * screen, and none of it is a constant anyone chose here.
 */
public final class Shell {

    public enum State {
        /** The picture fading up, before anything can be chosen. */
        TITLE,
        /** An option screen over it. */
        MENU,
        /** {@code PLAYTHEGAME}. */
        PLAY,
        /**
         * The picture between the level and the menu.
         *
         * {@code LOADTITLESCRN2} puts up a second painting, fades it up over
         * sixty-four steps and straight back down, and goes to the menu. There
         * is nothing written on it: the level's own reward is the password,
         * which {@code CALCPASSWORD} has by then written onto the menu's own
         * password line, waiting there when the player arrives.
         */
        ENDED,
    }

    /** What {@code READMAINMENU} does with each option of the first screen. */
    public enum Action { NONE, TWO_PLAYER, PLAY, CONTROLS, CREDITS, PASSWORD }

    /** {@code PASSWORDLINE}, and the column the letters start at. */
    private static final int PASSWORD_ROW = 23, PASSWORD_COL = 12;
    /** {@code CURRENTLEVELLINE}. */
    private static final int LEVEL_ROW = 11;

    /** {@code move.w #63,FADEAMOUNT} and {@code addq.w #4,d0}. */
    private static final int FADE_STEP = 4, FADE_FULL = 256;

    /**
     * {@code dc.w col3,$448} and the seven that follow it, then {@code col2}.
     *
     * These are literals inside a {@code REPT 32} whose body is eight scanlines,
     * so the two ramps run down the screen and repeat every eight lines.
     */
    private static final int[] GLYPH_LIT = {0x448, 0x77a, 0xaac, 0xccf,
                                            0xccf, 0xaac, 0x77a, 0x448};
    private static final int[] BEHIND_LIT = {0x200, 0x400, 0x600, 0x800,
                                             0x800, 0x600, 0x400, 0x200};

    private final TitleScreen title;
    /** includes/titlescrnraw1, which {@code LOADTITLESCRN2} puts up after a level. */
    private final TitleScreen after;
    private final MenuData menu;
    private final OptionScreen option;
    /** includes/optcop: the colour {@code putinrain} gives the text, per line. */
    private final int[] rain;
    /** {@code LEVEL_OPTS}, for the line above the options. */
    private final LevelNames levels;

    /** {@code MAXLEVEL}, which only a password changes. */
    public int maxLevel;
    /** Set when a password has just been accepted, for the caller to act on. */
    public boolean levelChanged;
    /** Whether the password line is being typed into, and what is in it. */
    private boolean typing;
    private final StringBuilder typed = new StringBuilder();
    /** {@code CONTROLBUFFER}, and which action is waiting for its new key. */
    public final Controls controls;
    private Controls.Action rebinding;

    public State state = State.TITLE;
    /** {@code OptScrn} and {@code OPTNUM}. */
    public int screen = MenuData.ONE_PLAYER;
    public int selected = 1;
    /** {@code FADEVAL}. */
    private int fade;
    private boolean fadingUp = true;
    private boolean drawn;

    public Shell(GameData game) throws IOException {
        this.title = TitleScreen.load(game);
        this.after = TitleScreen.load(game, "titlescrnraw1");
        this.menu = MenuData.load(game);
        this.option = new OptionScreen(OptFont.load(game));
        this.rain = readRain(Files.readAllBytes(game.include("optcop")));
        this.levels = LevelNames.load(game);
        this.controls = Controls.load(game);
        setLevel(0);
        redraw();
    }

    private static int[] readRain(byte[] file) {
        int[] out = new int[256];
        for (int i = 0; i < out.length && i * 2 + 1 < file.length; i++) {
            out[i] = ((file[i * 2] & 0xff) << 8) | (file[i * 2 + 1] & 0xff);
        }
        return out;
    }

    /**
     * {@code move.w MAXLEVEL,d0 / ... / bsr PUTINLINE}.
     *
     * The level line is written over from {@code LEVEL_OPTS}, which is why the
     * menu can name sixteen levels while the port has four on disk: the name
     * comes from the table, not from the file.
     */
    public void setLevel(int level) {
        maxLevel = Math.max(0, Math.min(LevelNames.COUNT - 1, level));
        menu.screens.get(MenuData.ONE_PLAYER)
            .setLine(LEVEL_ROW, levels.line(maxLevel));
        drawn = false;
    }

    /** {@code add.b #'a',d0}: which file the chosen level is in. */
    public String levelFile() {
        return LevelNames.fileName(maxLevel);
    }

    /** One frame of the fade, then whatever the fade was leading to. */
    public void tick() {
        if (state == State.ENDED) {
            // up over sixty-four steps and straight back down again
            fade += fadingUp ? FADE_STEP : -FADE_STEP;
            if (fade >= FADE_FULL) {
                fade = FADE_FULL;
                fadingUp = false;
            } else if (fade <= 0 && !fadingUp) {
                fade = FADE_FULL;
                fadingUp = true;
                state = State.MENU;
                show("ONEPLAYERMENU_TXT");
                selected = 1;
            }
            return;
        }
        if (state != State.TITLE) {
            return;
        }
        fade += fadingUp ? FADE_STEP : -FADE_STEP;
        if (fade >= FADE_FULL) {
            fade = FADE_FULL;
            state = State.MENU;
        }
    }

    /**
     * {@code end}: the level is over, one way or the other.
     *
     * {@code tst.w Energy / bgt wevewon} is the whole of the difference. Winning
     * adds one to {@code MAXLEVEL} and sets {@code FINISHEDLEVEL}, which is what
     * makes {@code CALCPASSWORD} run at all -- a level lost leaves the password
     * line holding whatever it held before, so dying cannot be used to earn one.
     */
    public void endLevel(boolean won, Password.State state) {
        if (won) {
            setLevel(maxLevel + 1);                 // add.w #1,MAXLEVEL
            writePassword(Password.encode(
                    new Password.State(state.energy(), maxLevel,
                                       state.gunFlags(), state.gunAmmo())));
        }
        this.won = won;
        this.state = State.ENDED;
        fade = 0;
        fadingUp = true;
        drawn = false;
    }

    /** Which of the two the last level ended as, for the caller and the checks. */
    public boolean won;

    /** {@code PASSWORDLINE}: what the menu's password line says now. */
    public String passwordLine() {
        char[] row = menu.screens.get(MenuData.ONE_PLAYER).text()[PASSWORD_ROW];
        return new String(row, PASSWORD_COL, Password.LETTERS);
    }

    /** {@code putinpassline}: the new password, onto the menu's own line. */
    private void writePassword(String word) {
        char[] row = menu.screens.get(MenuData.ONE_PLAYER).text()[PASSWORD_ROW];
        for (int i = 0; i < Password.LETTERS; i++) {
            int at = PASSWORD_COL + i;
            if (at < MenuData.COLUMNS && i < word.length()) {
                row[at] = word.charAt(i);
            }
        }
        drawn = false;
    }

    /** {@code sub.w #1,d0 / bge}: up stops at the first option, it does not wrap. */
    public void up() {
        if (state == State.MENU && selected > 0) {
            selected--;
            drawn = false;
        }
    }

    /** {@code add.w #1,d0 / tst.w (a0,d0.w*8) / bge}: down stops at the last. */
    public void down() {
        if (state == State.MENU && selected + 1 < options()) {
            selected++;
            drawn = false;
        }
    }

    private int options() {
        return menu.screens.get(screen).options().size();
    }

    /**
     * {@code READMAINMENU}'s own mapping of the returned {@code OPTNUM}.
     *
     * Nought is the two-player toggle, one starts the level, and the rest are
     * screens of their own. It looks upside down -- the first option is not the
     * one that plays -- but the line above it names the level, which is not
     * selectable at all: {@code playgame} takes {@code MAXLEVEL}, and only the
     * password changes that.
     */
    public Action select() {
        if (state == State.TITLE) {
            state = State.MENU;                     // a key ends the fade early
            fade = FADE_FULL;
            return Action.NONE;
        }
        if (state != State.MENU || typing) {
            return Action.NONE;
        }
        if (screen == controlScreen()) {
            // twelve bindings and then MAIN MENU, which is option twelve
            if (chooseControl()) {
                return Action.NONE;
            }
            show("ONEPLAYERMENU_TXT");
            selected = 2;
            return Action.NONE;
        }
        if (screen != MenuData.ONE_PLAYER) {
            // SHOWCREDITS and the rest wait for a key and come back; only the
            // first screen's options mean anything, and reading them against
            // another screen's list would act on whatever happened to line up
            show("ONEPLAYERMENU_TXT");
            selected = 1;
            return Action.NONE;
        }
        return switch (selected) {
            case 0 -> Action.TWO_PLAYER;
            case 1 -> Action.PLAY;
            case 2 -> Action.CONTROLS;
            case 3 -> Action.CREDITS;
            case 4 -> Action.PASSWORD;
            default -> Action.PLAY;
        };
    }

    /**
     * The password option: sixteen blanks, then whatever is typed into them.
     *
     * {@code READMAINMENU} clears the line first -- {@code move.b #32,(a0)+}
     * sixteen times -- so an old password is never left there to be accepted by
     * pressing return.
     */
    public void startPassword() {
        typing = true;
        typed.setLength(0);
        writePasswordLine();
    }

    public boolean typingPassword() {
        return typing;
    }

    /**
     * One key of the password.
     *
     * The original takes anything from A to Z although only A to P mean
     * anything, deletes on backspace, gives up on return or escape, and
     * validates the moment the sixteenth letter lands rather than waiting to be
     * told.
     */
    public void type(char c) {
        if (!typing) {
            return;
        }
        c = Character.toUpperCase(c);
        if (c < 'A' || c > 'Z') {
            return;                                 // blt/bgt .ENTERPASS
        }
        typed.append(c);
        writePasswordLine();
        if (typed.length() >= Password.LETTERS) {
            finishPassword();
        }
    }

    /** {@code cmp.l #'<-- ',(a1,d2.w*4)}. */
    public void backspace() {
        if (typing && typed.length() > 0) {
            typed.setLength(typed.length() - 1);
            writePasswordLine();
        }
    }

    /** {@code cmp.l #'RTN '} or {@code #'ESC '}: leave it, and check nothing. */
    public void cancelPassword() {
        typing = false;
        typed.setLength(0);
        writePasswordLine();
    }

    /** {@code bsr PASSLINETOGAME / tst.w d0 / bne .FORGETIT / bsr GETSTATS}. */
    private void finishPassword() {
        typing = false;
        Password.State got = Password.decode(typed.toString());
        if (got != null) {
            setLevel(got.level());
            levelChanged = true;
        }
        typed.setLength(0);
        writePasswordLine();
    }

    private void writePasswordLine() {
        char[] row = menu.screens.get(MenuData.ONE_PLAYER).text()[PASSWORD_ROW];
        for (int i = 0; i < Password.LETTERS; i++) {
            int at = PASSWORD_COL + i;
            if (at < MenuData.COLUMNS) {
                row[at] = i < typed.length() ? typed.charAt(i) : ' ';
            }
        }
        drawn = false;
    }

    /**
     * {@code CHANGECONTROLS}: choosing a line blanks its key and waits.
     *
     * {@code move.l #$20202020,(a0)} puts four spaces where the name was, so the
     * screen shows which line is being waited on, and the next key pressed --
     * any key at all, the routine does not filter -- becomes the binding.
     */
    public boolean chooseControl() {
        if (screen != controlScreen() || selected >= Controls.MAIN_MENU_OPTION) {
            return false;                           // cmp.w #12,d0 / beq
        }
        rebinding = Controls.Action.values()[selected];
        writeControlLine(rebinding, "    ");
        return true;
    }

    public boolean waitingForKey() {
        return rebinding != null;
    }

    /** {@code move.b d1,(a1,d0.w)}, then the name back onto the line. */
    public void bindKey(int rawKey) {
        if (rebinding == null) {
            return;
        }
        if (rawKey >= 0) {
            controls.bind(rebinding, rawKey);
        }
        writeControlLine(rebinding, controls.name(controls.key(rebinding)));
        rebinding = null;
    }

    private void writeControlLine(Controls.Action a, String text) {
        MenuData.Screen sc = menu.screen("CONTROL_TXT");
        if (sc == null) {
            return;
        }
        char[] row = sc.text()[Controls.FIRST_ROW + a.ordinal()];
        for (int i = 0; i < 4; i++) {
            int at = Controls.KEY_COLUMN + i;
            if (at < MenuData.COLUMNS) {
                row[at] = i < text.length() ? text.charAt(i) : ' ';
            }
        }
        drawn = false;
    }

    private int controlScreen() {
        for (int i = 0; i < menu.screens.size(); i++) {
            if (menu.screens.get(i).name().equals("CONTROL_TXT")) {
                return i;
            }
        }
        return -1;
    }

    /** Moves to another of {@code MENUDATA}'s screens. */
    public void show(String name) {
        for (int i = 0; i < menu.screens.size(); i++) {
            if (menu.screens.get(i).name().equals(name)) {
                screen = i;
                selected = 0;
                drawn = false;
                return;
            }
        }
    }

    private void redraw() {
        option.draw(menu.screens.get(screen), state == State.MENU ? selected : -1);
        drawn = true;
    }

    /**
     * The title picture with the option screen over it, into an RGBA buffer.
     *
     * @param out    the screen, at least three hundred and twenty by two hundred
     *               and fifty-six
     * @param stride bytes to the next row
     */
    public void draw(byte[] out, int stride) {
        if (!drawn) {
            redraw();
        }
        TitleScreen picture = state == State.ENDED ? after : title;
        for (int y = 0; y < TitleScreen.HEIGHT; y++) {
            int at = y * stride;
            int lit = y & 7;
            for (int x = 0; x < TitleScreen.WIDTH; x++) {
                int over = state == State.TITLE || state == State.ENDED
                        ? 0 : option.pixels[y * OptionScreen.WIDTH + x] & 3;
                int c = switch (over) {
                    case 1 -> rgb12(rain[y]);
                    case 2 -> rgb12(BEHIND_LIT[lit]);
                    case 3 -> rgb12(GLYPH_LIT[lit]);
                    default -> faded(picture.palette[
                            picture.pixels[y * TitleScreen.WIDTH + x] & 0xff]);
                };
                out[at++] = (byte) (c >> 16);
                out[at++] = (byte) (c >> 8);
                out[at++] = (byte) c;
                out[at++] = (byte) 0xff;
            }
        }
    }

    /** {@code PUTIN32}: each gun multiplied by {@code FADEVAL} and shifted down. */
    private int faded(int c) {
        if (fade >= FADE_FULL) {
            return c;
        }
        int r = (((c >> 16) & 0xff) * fade) >> 8;
        int g = (((c >> 8) & 0xff) * fade) >> 8;
        int b = ((c & 0xff) * fade) >> 8;
        return r << 16 | g << 8 | b;
    }

    private static int rgb12(int v) {
        int r = (v >> 8) & 0xf, g = (v >> 4) & 0xf, b = v & 0xf;
        return (r * 17) << 16 | (g * 17) << 8 | (b * 17);
    }
}
