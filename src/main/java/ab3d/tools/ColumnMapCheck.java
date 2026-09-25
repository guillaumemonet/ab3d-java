package ab3d.tools;

import ab3d.data.GameData;
import ab3d.engine.EngineState;

import java.nio.file.Files;

/**
 * Checks the column-to-word mapping against {@code xtocopx} itself.
 *
 * The rule -- one unused slot after every thirty-two columns -- is derived from
 * the floor routine's block handling, so it has to agree with the table the wall
 * routine indexes, entry for entry, or walls and floors will not line up.
 */
public final class ColumnMapCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        byte[] raw = Files.readAllBytes(game.root().resolve("includes/xtocopx"));
        int n = raw.length / 2;
        int bad = 0;
        for (int i = 0; i < n && i < EngineState.VIEW_COLUMNS; i++) {
            int table = ((raw[i * 2] & 0xff) << 8) | (raw[i * 2 + 1] & 0xff);
            int derived = EngineState.columnWord(i) * 2;      // back to bytes
            if (table != derived) {
                if (bad < 10) {
                    System.out.printf("  column %2d: table %4d, derived %4d%n",
                                      i, table, derived);
                }
                bad++;
            }
        }
        System.out.printf("xtocopx: %d entries, %d disagree with columnWord%n", n, bad);
    }
}
