package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.EngineState;
import ab3d.engine.StripDraw;

/**
 * Checks the claim the vertical texture walk rests on: whatever the depth, the
 * table's base plus forty steps lands on the same value, so the texture is
 * anchored at the horizon and only its rate of change depends on distance.
 */
public final class AnchorCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, "level_b");
        StripDraw sd = new StripDraw(new EngineState(lv), game);

        int reference = sd.anchorAt(1);
        int mismatches = 0, tested = 0;
        for (int d = 1; d < 8191; d++) {
            tested++;
            if (sd.anchorAt(d) != reference) {
                mismatches++;
            }
        }
        System.out.printf("anchor at row %d is %d (= 2^%.1f)%n",
                EngineState.CENTRE_Y, reference, Math.log(reference) / Math.log(2));
        System.out.printf("depths tested %d, differing from the anchor: %d%n",
                tested, mismatches);
    }
}
