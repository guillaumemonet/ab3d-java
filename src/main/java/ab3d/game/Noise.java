package ab3d.game;

import ab3d.data.Samples;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * {@code MakeSomeNoise}, from source/jg.s, and the four channels it writes to.
 *
 * The routine is given a sample number, a position relative to the player, a
 * volume and an identifying number, and it works out for itself how loud the
 * sound should be on each side. That last part is why the mixing is done here
 * rather than handed to a three-dimensional audio engine: the original computes
 * an explicit left and right volume from the distance and the sideways offset,
 * and honouring those two numbers is closer to it than asking something else to
 * place a point in space and hoping it agrees.
 *
 * The distance rule is the whole of it. The volume is multiplied by sixty-four,
 * capped, and then divided by a quarter of the distance plus one -- so a sound
 * at arm's length is at its stated volume and one across a room is a fraction of
 * it. The result is capped again at sixty-four, which is all the Amiga's
 * hardware could do.
 *
 * {@code notifplaying} is the other half and it is what stops a room of aliens
 * becoming a wall of noise: when it is clear, a sound whose identifying number
 * is already playing on some channel is dropped rather than started again.
 *
 * Four voices, because the machine had four channels. The original keeps eight
 * slots of {@code CHANNELDATA} and plays four of them.
 */
public final class Noise implements ab3d.engine.Sound {

    /** The hardware's four channels. */
    public static final int VOICES = 4;
    /** {@code cmp.w #64,d3 / ble notooloud}: as loud as the hardware goes. */
    public static final int MAX_VOLUME = 64;
    /** {@code cmp.l #32767,d3}. */
    private static final int HEADROOM = 32767;
    /** How many of its own samples the mixer keeps ahead of the card. */
    private static final int BUFFER = 1024;

    private final Samples samples;
    private SourceDataLine line;
    private Thread pump;
    private volatile boolean running;

    /** One channel: what it is playing, how far through, and how loud each side. */
    private static final class Voice {
        byte[] data;
        int at;
        int left, right;
        int id = -1;
    }

    private final Voice[] voices = new Voice[VOICES];
    /**
     * {@code mt_music}, mixed into the same buffer.
     *
     * The Amiga has four channels and the music and the effects share them; this
     * keeps the two apart in the code but adds them together at the end, which
     * comes to the same thing at the speaker.
     */
    public final ModPlayer music = new ModPlayer();
    /** How many of its own frames the mixer has left before the next music tick. */
    private double untilTick;

    /** Sounds asked for and sounds actually started, for checking. */
    public int asked, played, dropped, noRoom;

    public Noise(Samples samples) {
        this.samples = samples;
        for (int i = 0; i < VOICES; i++) {
            voices[i] = new Voice();
        }
    }

    /** Opens the audio line. A machine with no sound simply stays quiet. */
    public boolean open() {
        try {
            AudioFormat fmt = new AudioFormat(Samples.RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, BUFFER * 8);
            line.start();
            running = true;
            pump = new Thread(this::mix, "ab3d-sound");
            pump.setDaemon(true);
            pump.start();
            return true;
        } catch (Exception e) {
            line = null;
            return false;
        }
    }

    public void close() {
        running = false;
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
    }

    /**
     * {@code MakeSomeNoise}.
     *
     * @param sample       {@code Samplenum}
     * @param x            {@code Noisex}, sideways from the player
     * @param z            {@code Noisez}, in front of them
     * @param volume       {@code Noisevol}
     * @param id           {@code IDNUM}, which says what is making the sound
     * @param notIfPlaying {@code notifplaying}: drop it if this id is already on
     */
    @Override
    public void play(int sample, int x, int z, int volume, int id,
                     boolean notIfPlaying) {
        asked++;
        if (line == null || !samples.has(sample)) {
            return;
        }
        if (notIfPlaying) {
            for (Voice v : voices) {
                if (v.data != null && v.id == id) {
                    dropped++;
                    return;                          // SameAsMe: rts
                }
            }
        }

        // dontworry: how loud, from the distance
        long sq = (long) x * x + (long) z * z;
        int d3 = MAX_VOLUME;
        int dist = 1;
        if (sq != 0) {
            dist = isqrt(sq);
            long v = Math.min(HEADROOM, (long) volume << 6);
            int div = (dist >> 2) + 1;               // asr.w #2,d0 / addq #1,d0
            d3 = (int) (v / div);
            if (d3 > MAX_VOLUME) {
                d3 = MAX_VOLUME;
            }
        }

        // and how it splits: the sideways offset takes from one side
        int d4 = d3;
        int d2 = (dist << 3) == 0 ? 0 : (d3 * x) / (dist << 3);
        if (d2 > 0) {
            d3 = Math.max(0, d3 - d2);               // quietleft
        } else {
            d4 = Math.max(0, d4 + d2);
        }
        if (d3 == 0 && d4 == 0) {
            return;
        }

        Voice free = null;
        for (Voice v : voices) {
            if (v.data == null) {
                free = v;
                break;
            }
        }
        if (free == null) {
            // the importance test: the quietest channel gives way, and only to
            // something louder than it
            Voice worst = voices[0];
            for (Voice v : voices) {
                if (v.left + v.right < worst.left + worst.right) {
                    worst = v;
                }
            }
            if (worst.left + worst.right >= d3 + d4) {
                noRoom++;
                return;
            }
            free = worst;
        }
        synchronized (this) {
            free.data = samples.get(sample).data();
            free.at = 0;
            free.left = d4;
            free.right = d3;
            free.id = id;
        }
        played++;
    }

    /** The same integer square root the rest of the engine uses. */
    private static int isqrt(long a) {
        int top = 31;
        while (top > 0 && (a & (1L << top)) == 0) {
            top--;
        }
        int d0 = 1 << (top >> 1);
        for (int i = 0; i < 2; i++) {
            long d3 = ((long) d0 * d0 - a) / 2 / d0;
            d0 -= (int) d3;
            if (d0 <= 0) {
                d0 = 1;
            }
        }
        return d0;
    }

    /** Adds the voices together and hands the result to the card. */
    private void mix() {
        byte[] out = new byte[BUFFER * 4];
        while (running) {
            java.util.Arrays.fill(out, (byte) 0);
            synchronized (this) {
                // the replayer runs once a display frame, so its ticks are
                // spaced through the buffer rather than taken all at once
                int done = 0;
                while (done < BUFFER) {
                    if (untilTick <= 0) {
                        music.tick();
                        untilTick = Samples.RATE / ModPlayer.HZ;
                    }
                    int run = (int) Math.min(BUFFER - done, Math.ceil(untilTick));
                    byte[] part = new byte[run * 4];
                    music.mix(part, run, Samples.RATE);
                    System.arraycopy(part, 0, out, done * 4, run * 4);
                    untilTick -= run;
                    done += run;
                }
                for (Voice v : voices) {
                    if (v.data == null) {
                        continue;
                    }
                    for (int i = 0; i < BUFFER; i++) {
                        if (v.at >= v.data.length) {
                            v.data = null;
                            v.id = -1;
                            break;
                        }
                        int s = v.data[v.at++];      // signed eight-bit
                        add(out, i * 4, s * v.left * 4);
                        add(out, i * 4 + 2, s * v.right * 4);
                    }
                }
            }
            line.write(out, 0, out.length);
        }
    }

    private static void add(byte[] out, int at, int value) {
        int now = (short) ((out[at] & 0xff) | (out[at + 1] << 8));
        int sum = Math.max(-32768, Math.min(32767, now + value));
        out[at] = (byte) sum;
        out[at + 1] = (byte) (sum >> 8);
    }
}
