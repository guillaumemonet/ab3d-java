package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Module;
import ab3d.data.Samples;
import ab3d.game.ModPlayer;

/**
 * Do the three modules read back as ProTracker files, and does the replayer
 * produce anything?
 *
 * A module that parses wrongly still parses: the pattern count comes from
 * walking the order list, and the sample offsets from adding up lengths, so one
 * mistake in either moves every sample and the music becomes noise rather than
 * silence. The checks are that the sample lengths add up to the file, that the
 * order list names patterns the file has room for, and that mixing a few seconds
 * of it actually moves the speaker.
 *
 * {@code UseAllChannels} is the other claim. A level's music uses two voices and
 * a jingle uses four, so the same module mixed both ways should not come out the
 * same.
 */
public final class MusicCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        for (String name : new String[]{"ingame", "welldone", "gameover"}) {
            Module m = Module.load(game, name);
            int bytes = m.pcm().length;
            int patternEnd = 0x43c + m.patternCount() * 64 * 4 * 4;
            int sampleBytes = 0;
            int used = 0;
            for (int i = 0; i < Module.SAMPLES; i++) {
                Module.Sample s = m.sample(i);
                sampleBytes += s.length();
                if (s.length() > 0) {
                    used++;
                }
            }
            System.out.printf("%-9s %s, %d bytes: %2d patterns, %3d positions, "
                              + "%2d samples%n", name,
                              m.isProTracker() ? "M.K." : "NOT M.K.", bytes,
                              m.patternCount(), m.songLength(), used);
            System.out.printf("          patterns end at %6d, samples add to "
                              + "%6d, together %6d %s%n", patternEnd, sampleBytes,
                              patternEnd + sampleBytes,
                              patternEnd + sampleBytes == bytes
                                      ? "-- exactly the file"
                                      : "<-- does not fill the file");

            // mix four seconds each way and see what comes out
            for (boolean all : new boolean[]{false, true}) {
                ModPlayer p = new ModPlayer();
                p.init(m, all);
                long loud = 0;
                int nonZero = 0;
                byte[] buf = new byte[512 * 4];
                for (int frame = 0; frame < 4 * ModPlayer.HZ; frame++) {
                    p.tick();
                    java.util.Arrays.fill(buf, (byte) 0);
                    p.mix(buf, 512, Samples.RATE);
                    for (int i = 0; i < 512; i++) {
                        int v = (short) ((buf[i * 4] & 0xff) | (buf[i * 4 + 1] << 8));
                        loud += Math.abs(v);
                        if (v != 0) {
                            nonZero++;
                        }
                    }
                }
                System.out.printf("          %s voices: %5.1f%% of samples sound, "
                                  + "average level %d%n", all ? "four" : "two ",
                                  nonZero * 100.0 / (4 * ModPlayer.HZ * 512),
                                  loud / (4L * ModPlayer.HZ * 512));
            }
            System.out.println();
        }
        System.out.println("The background music is not gated here but is in the "
                + "game: jg.s runs the replayer only when byte three of Prefsfile "
                + "is 'b', and it assembles as 'k4nx'. The two jingles are not "
                + "gated, and are what this build plays.");
    }
}
