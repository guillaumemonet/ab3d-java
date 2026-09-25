package ab3d.tools;

import ab3d.data.Controls;
import ab3d.data.GameData;
import ab3d.data.MenuData;

/**
 * Do the twelve defaults agree with what the controls screen prints?
 *
 * The original says each binding twice -- once as a byte in
 * {@code CONTROLBUFFER}, once as four characters sitting at column thirty-two of
 * {@code CONTROL_TXT} -- and nothing in the assembly makes the two agree. So
 * looking up each byte in {@code KVALTOASC} and comparing it against the printed
 * line checks the raw-key table and the defaults against each other, using two
 * places in the source that were written by hand and separately.
 */
public final class ControlCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Controls c = Controls.load(game);
        MenuData menu = MenuData.load(game);
        MenuData.Screen screen = menu.screen("CONTROL_TXT");

        System.out.printf("KVALTOASC holds %d names%n%n", c.nameCount());
        int agree = 0, total = 0;
        for (Controls.Action a : Controls.Action.values()) {
            int row = Controls.FIRST_ROW + a.ordinal();
            String line = new String(screen.text()[row]);
            String printed = line.substring(Controls.KEY_COLUMN,
                                            Controls.KEY_COLUMN + 4);
            String named = c.name(c.key(a));
            total++;
            boolean ok = printed.trim().equals(named.trim());
            if (ok) {
                agree++;
            }
            System.out.printf("  %-26s $%02x  %-5s printed %-5s %s%n",
                              a.label, c.key(a), "'" + named.trim() + "'",
                              "'" + printed.trim() + "'", ok ? "" : "  <-- differ");
            if (!line.contains(a.label)) {
                System.out.printf("      row %d does not name this action: |%s|%n",
                                  row, line);
            }
        }
        System.out.printf("%n%d of %d defaults match the printed screen%n",
                          agree, total);
    }
}
