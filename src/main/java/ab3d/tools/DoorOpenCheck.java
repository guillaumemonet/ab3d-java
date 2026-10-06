package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.engine.Doors;
import ab3d.engine.Frame68k;
import ab3d.game.Player;

/**
 * Can the player actually walk up to a door and open it?
 *
 * {@link DoorProbe} writes the trigger flag into the floor line itself and then
 * runs the door, so it proves the door routine works and nothing about how the
 * flag gets there. The flag is written by the collision -- {@code or.w d0,14(a2)}
 * when a move is refused by a wall -- and read and cleared by the door in the
 * same frame, so the whole thing only works if the player's move, the switches
 * and the doors run in the right order with the right flags.
 *
 * This walks a real {@link Player} into each door of a level, pressing operate,
 * and watches whether the door moves.
 */
public final class DoorOpenCheck {

    private static final int STEPS = 120;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        String only = args.length > 0 ? args[0] : null;
        int opened = 0;
        int tried = 0;

        for (String name : game.diskLevels()) {
            if (only != null && !only.equals(name)) {
                continue;
            }
            Level lv = Level.load(game, name);
            Frame68k frame = new Frame68k(lv, game);
            int here = 0;
            int moved = 0;

            for (Doors.Door d : frame.doors.doors) {
                tried++;
                here++;
                if (walkInto(lv, game, d)) {
                    opened++;
                    moved++;
                }
            }
            if (here > 0) {
                System.out.printf("  %-8s %2d doors, %2d opened when walked into%n",
                                  name, here, moved);
            }
        }
        System.out.printf("%n%d of %d doors opened; %d of the %d that did not "
                          + "were never reached by the walk at all, which is this "
                          + "check's own limit and not the game's%n",
                          opened, tried, neverTouched, tried - opened);
    }

    /**
     * One door, with a player pushed at it from both sides.
     *
     * Which side the door is approached from decides whether the move is refused
     * at all, and an unlocked door needs nothing but that refusal, so trying
     * both is what makes a failure here mean something.
     */
    private static boolean walkInto(Level lv, GameData game, Doors.Door d)
            throws Exception {
        if (d.walls.isEmpty()) {
            return false;
        }
        int line = d.walls.get(0)[0];
        if (line < 0 || line >= lv.floorLines.length) {
            return false;
        }
        var fl = lv.floorLine(line);
        int midX = (fl.x1 + fl.x1 + fl.dx) / 2;
        int midZ = (fl.z1 + fl.z1 + fl.dz) / 2;

        boolean touched = false;
        for (int side = -1; side <= 1; side += 2) {
            // a fresh level each try: a door that opened stays open
            Level fresh = Level.load(game, lv.name);
            Frame68k frame = new Frame68k(fresh, game);
            Player p = new Player(fresh);
            // the line's normal, from its own direction
            int nx = -fl.dz, nz = fl.dx;
            int len = Math.max(1, (int) Math.sqrt((long) nx * nx + (long) nz * nz));
            p.camera.x = midX + side * nx * 200 / len;
            p.camera.z = midZ + side * nz * 200 / len;
            p.camera.zone = d.zone;

            Doors.Door mine = frame.doors.doors.get(indexOf(frame, d));
            int was = mine.height;
            for (int t = 0; t < STEPS; t++) {
                p.move(-side * nx * 40 / len, -side * nz * 40 / len);
                frame.updateSwitches(1, p.camera.x, p.camera.z, true);
                frame.updateDoors(1, true);
                frame.updateLifts(1, p.camera.zone, true);
                frame.updateObjects(p.camera.zone, p.camera.x, p.camera.z,
                                    p.camera.yoff, p.stoodInTop, 1,
                                    p.camera.angle);
                frame.armObjects(p.camera.zone);
                if (mine.height != was) {
                    return true;
                }
                // did the move ever meet the door's own wall at all?
                int flagAt = fresh.floorLine(line).offset + 14;
                if (fresh.data.s16(flagAt) != 0) {
                    touched = true;
                }
            }
        }
        if (!touched) {
            neverTouched++;
        }
        return false;
    }

    /** Doors whose wall the walk never even reached: a limit of this check. */
    private static int neverTouched;

    private static int indexOf(Frame68k frame, Doors.Door d) {
        var all = frame.doors.doors;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).at == d.at) {
                return i;
            }
        }
        return 0;
    }
}
