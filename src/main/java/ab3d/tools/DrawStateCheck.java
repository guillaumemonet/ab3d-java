package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.GameObject;
import ab3d.data.Level;
import ab3d.engine.Frame68k;
import ab3d.engine.Obj;

/**
 * Does what the drawing reads still agree with what the engine wrote?
 *
 * This port keeps two copies of an object: the record in the level data, which
 * every routine writes into exactly as the original does, and a
 * {@link GameObject} beside it, which is what {@code ObjDraw} actually reads.
 * The original has one copy and cannot drift. This one can, and did -- the
 * graphic, its frame, the height it settles at, its brightness and its storey
 * were all read once when the level loaded and never again, so a dying alien
 * went on drawing the pose it had when the level started.
 *
 * Nothing caught it, because every enemy check reads the record and the record
 * was always right. So this compares the two, field by field, after running the
 * objects for a while -- the one thing that would have noticed.
 */
public final class DrawStateCheck {

    private static final int FRAMES = 600;

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        int bad = 0;
        for (String name : game.diskLevels()) {
            bad += run(game, name);
        }
        System.out.println(bad == 0
                ? "\nwhat the drawing reads is what the engine wrote"
                : "\n" + bad + " fields had drifted");
    }

    private static int run(GameData game, String name) throws Exception {
        Level lv = Level.load(game, name);
        Frame68k frame = new Frame68k(lv, game);

        // stand on the first enemy so things wake, move and die
        int watch = -1, wx = 0, wz = 0, wy = 0;
        for (int i = 0; i < lv.objects.size(); i++) {
            int base = lv.ptrObjects + i * GameObject.SIZE;
            int type = lv.data.u8(base + Obj.TYPE);
            if (type == 0 || type == 12 || type == 18 || type == 19 || type == 8) {
                int pt = lv.data.s16(base + Obj.POINT);
                watch = lv.data.s16(base + Obj.ZONE);
                wx = lv.objectPointX[pt];
                wz = lv.objectPointZ[pt];
                wy = lv.data.s16(base + Obj.HEIGHT) << 7;
                break;
            }
        }
        if (watch < 0) {
            return 0;
        }

        for (int t = 0; t < FRAMES; t++) {
            frame.updateObjects(watch, wx, wz, wy, false, 1, 0);
            frame.armObjects(watch);
        }

        int bad = 0;
        int moved = 0;
        for (int i = 0; i < lv.objects.size(); i++) {
            int base = lv.ptrObjects + i * GameObject.SIZE;
            if (lv.data.s16(base) < 0) {
                break;
            }
            GameObject o = lv.objects.get(i);
            bad += same(name, i, "slot", lv.data.s16(base + Obj.SLOT), o.slot);
            bad += same(name, i, "frame", lv.data.s16(base + Obj.FRAME), o.frame);
            bad += same(name, i, "height", lv.data.s16(base + Obj.HEIGHT), o.height);
            bad += same(name, i, "bright", lv.data.s16(base + Obj.BRIGHT),
                        o.brightness);
            bad += same(name, i, "room", lv.data.s16(base + Obj.GRAPHIC_ROOM),
                        o.zone);
            bad += same(name, i, "storey", lv.data.u8(base + Obj.IN_TOP) != 0 ? 1 : 0,
                        o.inUpperStorey ? 1 : 0);
            bad += same(name, i, "width", lv.data.u8(base + Obj.SPRITE_SIZE),
                        o.spriteWidth);
            bad += same(name, i, "height", lv.data.u8(base + Obj.SPRITE_SIZE + 1),
                        o.spriteHeight);
            if (lv.data.s16(base + Obj.FRAME) != 0) {
                moved++;
            }
        }
        System.out.printf("  %-8s %3d records, %3d on a frame other than nought, "
                          + "%s%n", name, lv.objects.size(), moved,
                          bad == 0 ? "in step" : bad + " WRONG");
        return bad;
    }

    private static int same(String lvl, int i, String what, int record, int drawn) {
        if (record == drawn) {
            return 0;
        }
        System.out.printf("  %-8s object %3d %-6s record %6d, drawn %6d%n",
                          lvl, i, what, record, drawn);
        return 1;
    }
}
