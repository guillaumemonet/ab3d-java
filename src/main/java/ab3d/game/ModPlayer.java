package ab3d.game;

import ab3d.data.Module;

/**
 * {@code mt_music}, from source/jg.s: the ProTracker replayer.
 *
 * It runs once a frame. A counter climbs to {@code mt_speed} and then a new row
 * is read -- {@code mt_getnew} -- and on every other frame only the running
 * effects are stepped, which is {@code mt_checkcom}. So the music's timing is
 * the display's: six frames a row at the default speed, which on a fifty-hertz
 * machine is the tempo everything was written at.
 *
 * {@code UseAllChannels} decides how much of it is heard. Clear, as it is while
 * a level is being played, only the first two voices sound and the other two are
 * left for the game's own noises. The two jingles set it and take all four.
 *
 * In this build the background music never starts: the loop calls the replayer
 * only when byte three of {@code Prefsfile} is {@code 'b'}, and the assembled
 * value is {@code 'k4nx'}. The win and lose jingles are not gated that way and
 * do play.
 */
public final class ModPlayer {

    /** {@code move.b #$6,mt_speed}. */
    private static final int DEFAULT_SPEED = 6;
    /** The replayer runs once a display frame. */
    public static final int HZ = 50;
    /** The Amiga's clock, which a period divides into a sample rate. */
    private static final int PAL_CLOCK = 3546895;

    /**
     * {@code mt_periods}: three octaves of ProTracker's own period table.
     *
     * A note in a pattern is stored as its period, not as a note number, and the
     * table exists so the effects that move between notes -- arpeggio and tone
     * portamento -- can find the neighbouring one.
     */
    private static final int[] PERIODS = {
        856, 808, 762, 720, 678, 640, 604, 570, 538, 508, 480, 453,
        428, 404, 381, 360, 339, 320, 302, 285, 269, 254, 240, 226,
        214, 202, 190, 180, 170, 160, 151, 143, 135, 127, 120, 113,
    };

    /** One of the four voices. */
    private static final class Voice {
        int sample;
        int period, wantPeriod;
        int volume;
        int command, argument;
        int portaSpeed, portaTarget;
        int vibPos, vibCommand;
        int start, length, loopStart, loopLength;
        double at;
        boolean on;
    }

    private final Voice[] voices = new Voice[Module.CHANNELS];
    private Module mod;
    private byte[] pcm;

    private int counter, speed = DEFAULT_SPEED;
    private int position, row;
    private boolean useAllChannels;
    private int breakRow = -1, jumpTo = -1;

    /** Whether the module has run off its end, which the jingles wait for. */
    public boolean reachedEnd;

    public ModPlayer() {
        for (int i = 0; i < voices.length; i++) {
            voices[i] = new Voice();
        }
    }

    /** {@code mt_init}: start a module from its first row. */
    public void init(Module m, boolean allChannels) {
        this.mod = m;
        this.pcm = m.pcm();
        this.useAllChannels = allChannels;
        speed = DEFAULT_SPEED;                      // move.b #$6,mt_speed
        counter = 0;
        position = 0;
        row = 0;
        breakRow = -1;
        jumpTo = -1;
        reachedEnd = false;
        for (Voice v : voices) {
            v.on = false;
            v.volume = 0;
            v.period = 0;
            v.vibPos = 0;
        }
    }

    /** {@code mt_end}: silence, and nothing more played. */
    public void stop() {
        mod = null;
        for (Voice v : voices) {
            v.on = false;
        }
    }

    public boolean playing() {
        return mod != null;
    }

    /**
     * {@code mt_music}: one frame.
     *
     * The counter reaching the speed is what makes a row happen; every other
     * frame only steps the effects already running.
     */
    public void tick() {
        if (mod == null) {
            return;
        }
        counter++;                                  // addq.b #$1,mt_counter
        if (counter < speed) {
            for (int i = 0; i < channels(); i++) {
                checkCommand(voices[i]);            // mt_nonew
            }
            return;
        }
        counter = 0;
        newRow();                                   // mt_getnew
    }

    /** {@code tst.b UseAllChannels}: two voices in a level, four in a jingle. */
    private int channels() {
        return useAllChannels ? 4 : 2;
    }

    /** {@code mt_getnew}: read one row and start what it says. */
    private void newRow() {
        int pattern = mod.order(position);
        for (int i = 0; i < channels(); i++) {
            playVoice(voices[i], mod.cell(pattern, row, i));
        }

        row++;
        if (breakRow >= 0 || jumpTo >= 0 || row >= Module.ROWS) {
            int next = jumpTo >= 0 ? jumpTo : position + 1;
            row = breakRow >= 0 ? breakRow : 0;
            breakRow = -1;
            jumpTo = -1;
            if (next >= mod.songLength()) {
                next = 0;
                reachedEnd = true;                  // st reachedend
            }
            position = next;
        }
    }

    /** {@code mt_playvoice}: a cell is a sample, a period and a command. */
    private void playVoice(Voice v, int cell) {
        int period = (cell >> 16) & 0xfff;
        int sample = ((cell >> 24) & 0xf0) | ((cell >> 12) & 0x0f);
        v.command = (cell >> 8) & 0xf;
        v.argument = cell & 0xff;

        if (sample != 0) {
            Module.Sample s = mod.sample(sample - 1);
            v.sample = sample;
            v.volume = s.volume();
            v.start = s.start();
            v.length = s.length();
            v.loopStart = s.loops() ? s.start() + s.loopStart() : s.start();
            v.loopLength = s.loops() ? s.loopLength() : 0;
        }

        if (period != 0) {
            if (v.command == 3 || v.command == 5) {
                // mt_setmyport: a tone portamento does not restart the sample,
                // it only names where the pitch is heading
                v.portaTarget = period;
            } else {
                v.wantPeriod = period;
                v.period = period;
                v.at = 0;
                v.on = true;                        // the DMA is restarted
                v.vibPos = 0;
            }
        }
        rowCommand(v);
    }

    /** The commands that act once, when the row is read. */
    private void rowCommand(Voice v) {
        int x = (v.argument >> 4) & 0xf;
        int y = v.argument & 0xf;
        switch (v.command) {
            case 3 -> {
                if (v.argument != 0) {
                    v.portaSpeed = v.argument;      // mt_setmyport
                }
            }
            case 4 -> {
                if (v.argument != 0) {
                    v.vibCommand = v.argument;
                }
            }
            case 9 -> v.at = (x * 16 + y) * 256;    // sample offset
            case 11 -> jumpTo = v.argument;         // position jump
            case 12 -> v.volume = Math.min(64, v.argument);   // set volume
            case 13 -> breakRow = Math.min(63, x * 10 + y);   // pattern break
            case 15 -> {
                if (v.argument != 0) {
                    speed = v.argument;             // set speed
                }
            }
            default -> { }
        }
    }

    /**
     * {@code mt_checkcom}: the commands that run on every frame of a row.
     *
     * Arpeggio is the one that shows the replayer's age: it divides the frame
     * counter by three and picks the note, its third or its fifth from the
     * period table by walking it until it finds the one the current period sits
     * at or below.
     */
    private void checkCommand(Voice v) {
        int x = (v.argument >> 4) & 0xf;
        int y = v.argument & 0xf;
        switch (v.command) {
            case 0 -> {
                if (v.argument != 0) {
                    v.period = arpeggio(v, counter % 3 == 0 ? 0
                                           : counter % 3 == 1 ? x : y);
                }
            }
            case 1 -> v.period = Math.max(113, v.period - v.argument);
            case 2 -> v.period = Math.min(856, v.period + v.argument);
            case 3 -> tonePorta(v);
            case 4 -> vibrato(v);
            case 5 -> {
                tonePorta(v);
                volumeSlide(v, x, y);
            }
            case 6 -> {
                vibrato(v);
                volumeSlide(v, x, y);
            }
            case 10 -> volumeSlide(v, x, y);
            default -> { }
        }
    }

    /** {@code mt_arploop}: the note this many steps up the table. */
    private int arpeggio(Voice v, int steps) {
        if (steps == 0) {
            return v.wantPeriod;
        }
        for (int i = 0; i < PERIODS.length; i++) {
            if (PERIODS[i] <= v.wantPeriod) {
                return PERIODS[Math.min(PERIODS.length - 1, i + steps)];
            }
        }
        return v.wantPeriod;
    }

    /** {@code mt_toneportamento}: move toward the target and stop there. */
    private void tonePorta(Voice v) {
        if (v.portaTarget == 0) {
            return;
        }
        if (v.period < v.portaTarget) {
            v.period = Math.min(v.portaTarget, v.period + v.portaSpeed);
        } else if (v.period > v.portaTarget) {
            v.period = Math.max(v.portaTarget, v.period - v.portaSpeed);
        }
    }

    /** {@code mt_vibrato}: a sine swing either side of the note. */
    private static final int[] SINE = {
        0, 24, 49, 74, 97, 120, 141, 161, 180, 197, 212, 224,
        235, 244, 250, 253, 255, 253, 250, 244, 235, 224, 212, 197,
        180, 161, 141, 120, 97, 74, 49, 24,
    };

    private void vibrato(Voice v) {
        int pos = (v.vibPos >> 2) & 31;
        int depth = v.vibCommand & 0xf;
        int add = SINE[pos] * depth / 128;
        v.period = v.wantPeriod + (v.vibPos < 0 ? -add : add);
        v.vibPos += ((v.vibCommand >> 4) & 0xf) * 4;
        if (v.vibPos >= 128) {
            v.vibPos -= 128;
        }
    }

    private void volumeSlide(Voice v, int up, int down) {
        v.volume = up > 0 ? Math.min(64, v.volume + up)
                          : Math.max(0, v.volume - down);
    }

    /**
     * Mixes one buffer of the music.
     *
     * A period is what the Amiga's hardware counted down, so the rate a sample
     * runs at is the machine's clock over it -- which is why a lower period is a
     * higher note.
     *
     * @param out   interleaved stereo, sixteen bits a side
     * @param rate  the rate the buffer is played back at
     */
    public void mix(byte[] out, int frames, float rate) {
        if (mod == null) {
            return;
        }
        for (int i = 0; i < channels(); i++) {
            Voice v = voices[i];
            if (!v.on || v.period <= 0 || v.length <= 0) {
                continue;
            }
            double step = (PAL_CLOCK / (double) v.period) / rate;
            // the outer voices sit to one side, as the hardware put them
            int left = (i == 0 || i == 3) ? v.volume : v.volume / 3;
            int right = (i == 0 || i == 3) ? v.volume / 3 : v.volume;
            for (int f = 0; f < frames; f++) {
                int at = (int) v.at;
                if (at >= v.length) {
                    if (v.loopLength > 2) {
                        v.at -= v.length - (v.loopStart - v.start);
                        at = (int) v.at;
                    } else {
                        v.on = false;
                        break;
                    }
                }
                int index = v.start + at;
                int s = index >= 0 && index < pcm.length ? pcm[index] : 0;
                add(out, f * 4, s * left * 2);
                add(out, f * 4 + 2, s * right * 2);
                v.at += step;
            }
        }
    }

    private static void add(byte[] out, int at, int value) {
        if (at + 1 >= out.length) {
            return;
        }
        int now = (short) ((out[at] & 0xff) | (out[at + 1] << 8));
        int sum = Math.max(-32768, Math.min(32767, now + value));
        out[at] = (byte) sum;
        out[at + 1] = (byte) (sum >> 8);
    }
}
