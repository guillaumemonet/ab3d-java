package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Samples;

/**
 * Does every slot of the sound table point at a file of the length it claims?
 *
 * {@code SFX_NAMES} states each sample's length beside its name, and the files
 * on the disk were written separately, so the two agreeing is evidence that the
 * table was read with its commented-out entries in the right places. Getting
 * that wrong shifts every sound past the tenth by a slot, which would be
 * inaudible as a bug and obvious as a wrong noise.
 */
public final class SampleCheck {

    public static void main(String[] args) throws Exception {
        Samples s = Samples.load(GameData.fromSystemProperty());
        System.out.printf("%d slots, played at %.0f samples a second "
                          + "(the clock over a period of %d)%n%n",
                          s.count(), Samples.RATE, Samples.PERIOD);
        int agree = 0, missing = 0;
        for (int i = 0; i < s.count(); i++) {
            Samples.Sample x = s.get(i);
            boolean ok = x.data().length == x.statedLength();
            if (x.data().length == 0) {
                missing++;
            } else if (ok) {
                agree++;
            }
            System.out.printf("%2d %-14s %6d stated, %6d on disk %s%n", i,
                              x.name(), x.statedLength(), x.data().length,
                              x.data().length == 0 ? "<-- no file"
                                      : ok ? "" : "<-- differs");
        }
        System.out.printf("%n%d of %d match the stated length, %d have no file%n",
                          agree, s.count(), missing);
    }
}
