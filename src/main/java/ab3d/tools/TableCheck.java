package ab3d.tools;

import ab3d.data.BulletAnims;
import ab3d.data.ColBox;
import ab3d.data.Controls;
import ab3d.data.EndZones;
import ab3d.data.GameData;
import ab3d.data.GunAnims;
import ab3d.data.GunData;
import ab3d.data.LevelNames;
import ab3d.data.MenuData;
import ab3d.data.Samples;
import ab3d.data.SpriteBank;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Does the saved table say what the assembly says?
 *
 * Every class that reads the original's source now has a second way in: when the
 * source is not there it takes the answer {@link TableGen} wrote down. The two
 * had better agree, and nothing in the build makes them -- the generated file is
 * committed, the source tree is not, so a parser could be changed without the
 * table being rewritten and nobody would notice until a gun behaved oddly.
 *
 * This loads each one twice, once against a real source tree and once against a
 * directory that holds nothing, and compares. A failure here means the table is
 * stale: run {@link TableGen} again.
 */
public final class TableCheck {

    private static int bad;

    public static void main(String[] args) throws Exception {
        GameData real = GameData.fromSystemProperty();
        GameData none = GameData.from(Path.of("no-such-source-tree"), real.disk());

        EndZones a = EndZones.load(real);
        EndZones b = EndZones.load(none);
        int[] za = new int[a.count()];
        int[] zb = new int[b.count()];
        for (int i = 0; i < za.length; i++) {
            za[i] = a.of(i);
            zb[i] = b.of(i);
        }
        same("END_ZONES", za, zb);

        ColBox ca = ColBox.load(real);
        ColBox cb = ColBox.load(none);
        int[] wa = new int[ca.count()];
        int[] wb = new int[cb.count()];
        int[] ha = new int[ca.count()];
        int[] hb = new int[cb.count()];
        for (int i = 0; i < wa.length; i++) {
            wa[i] = ca.width(i);
            ha[i] = ca.height(i);
            wb[i] = cb.width(i);
            hb[i] = cb.height(i);
        }
        same("COL_WIDTH", wa, wb);
        same("COL_HEIGHT", ha, hb);

        GunData ga = GunData.load(real);
        GunData gb = GunData.load(none);
        int[] da = new int[GunData.GUNS * GunData.RECORD];
        int[] db = new int[da.length];
        for (int g = 0; g < GunData.GUNS; g++) {
            for (int i = 0; i < GunData.RECORD; i++) {
                da[g * GunData.RECORD + i] = ga.byteAt(g, i);
                db[g * GunData.RECORD + i] = gb.byteAt(g, i);
            }
        }
        same("GUN_DATA", da, db);

        GunAnims na = GunAnims.load(real);
        GunAnims nb = GunAnims.load(none);
        int steps = 0;
        for (int i = 0; i < 8; i++) {
            same("GUN_ANIMS[" + i + "]", na.frames(i), nb.frames(i));
            steps += na.frames(i).length;
        }
        ok("GUN_ANIMS", "8 lists, " + steps + " frames");

        BulletAnims ba = BulletAnims.load(real);
        BulletAnims bb = BulletAnims.load(none);
        int shots = 0;
        for (int i = 0; i < BulletAnims.SLOTS; i++) {
            same("BULLET_FLYING_SIZE[" + i + "]",
                 new int[]{ba.flyingSize(i)}, new int[]{bb.flyingSize(i)});
            same("BULLET_BURST_SIZE[" + i + "]",
                 new int[]{ba.burstSize(i)}, new int[]{bb.burstSize(i)});
            same("BULLET_FORCE[" + i + "]",
                 new int[]{ba.force(i)}, new int[]{bb.force(i)});
            same("BULLET_FLIGHT[" + i + "]", flat(ba.flight(i)), flat(bb.flight(i)));
            same("BULLET_BURST[" + i + "]", flat(ba.burst(i)), flat(bb.burst(i)));
            shots += ba.flight(i).length + ba.burst(i).length;
        }
        ok("BULLET_*", BulletAnims.SLOTS + " slots, " + shots + " steps");

        Controls ka = Controls.load(real);
        Controls kb = Controls.load(none);
        int[] ya = new int[Controls.Action.values().length];
        int[] yb = new int[ya.length];
        for (Controls.Action act : Controls.Action.values()) {
            ya[act.ordinal()] = ka.key(act);
            yb[act.ordinal()] = kb.key(act);
        }
        same("CONTROL_KEYS", ya, yb);
        if (ka.nameCount() != kb.nameCount()) {
            fail("KEY_NAMES", ka.nameCount() + " names against " + kb.nameCount());
        } else {
            for (int i = 0; i < ka.nameCount(); i++) {
                if (!Objects.equals(ka.name(i), kb.name(i))) {
                    fail("KEY_NAMES[" + i + "]",
                         "'" + ka.name(i) + "' against '" + kb.name(i) + "'");
                }
            }
            ok("KEY_NAMES", ka.nameCount() + " entries");
        }

        Samples sa = Samples.load(real);
        Samples sb = Samples.load(none);
        if (sa.count() != sb.count()) {
            fail("SAMPLES", sa.count() + " against " + sb.count());
        } else {
            for (int i = 0; i < sa.count(); i++) {
                Samples.Sample one = sa.get(i);
                Samples.Sample two = sb.get(i);
                if (!one.name().equals(two.name())
                        || one.statedLength() != two.statedLength()
                        || !Arrays.equals(one.data(), two.data())) {
                    fail("SAMPLES[" + i + "]", one.name() + " against " + two.name());
                }
            }
            ok("SAMPLES", sa.count() + " entries, sound included");
        }

        LevelNames la = LevelNames.load(real);
        LevelNames lb = LevelNames.load(none);
        for (int i = 0; i < LevelNames.COUNT; i++) {
            if (!la.line(i).equals(lb.line(i))) {
                fail("LEVEL_NAMES[" + i + "]",
                     "'" + la.line(i) + "' against '" + lb.line(i) + "'");
            }
        }
        ok("LEVEL_NAMES", la.count() + " lines");

        MenuData ma = MenuData.load(real);
        MenuData mb = MenuData.load(none);
        if (ma.screens.size() != mb.screens.size()) {
            fail("MENU", ma.screens.size() + " screens against "
                         + mb.screens.size());
        } else {
            for (int i = 0; i < ma.screens.size(); i++) {
                MenuData.Screen one = ma.screens.get(i);
                MenuData.Screen two = mb.screens.get(i);
                if (!one.name().equals(two.name())) {
                    fail("MENU_NAMES[" + i + "]",
                         one.name() + " against " + two.name());
                    continue;
                }
                for (int r = 0; r < MenuData.ROWS; r++) {
                    if (!Arrays.equals(one.text()[r], two.text()[r])) {
                        fail("MENU_TEXT[" + one.name() + "][" + r + "]",
                             "'" + new String(one.text()[r]) + "'\n        against '"
                             + new String(two.text()[r]) + "'");
                    }
                }
                if (!one.options().equals(two.options())) {
                    fail("MENU_OPTIONS[" + one.name() + "]",
                         one.options() + " against " + two.options());
                }
            }
            ok("MENU_*", ma.screens.size() + " screens, "
                         + ma.screens.size() * MenuData.ROWS + " lines");
        }

        SpriteBank pa = new SpriteBank(real);
        SpriteBank pb = new SpriteBank(none);
        for (int slot = 0; slot < 32; slot++) {
            if (pa.frameCount(slot) != pb.frameCount(slot)) {
                fail("SPRITE_FRAMES[" + slot + "]",
                     pa.frameCount(slot) + " frames against " + pb.frameCount(slot));
                continue;
            }
            for (int i = 0; i < pa.frameCount(slot); i++) {
                if (!pa.frame(slot, i).equals(pb.frame(slot, i))) {
                    fail("SPRITE_FRAMES[" + slot + "][" + i + "]",
                         pa.frame(slot, i) + " against " + pb.frame(slot, i));
                }
            }
        }
        ok("SPRITE_FRAMES", "32 slots");

        System.out.println(bad == 0
                ? "\nthe saved tables say what the assembly says"
                : "\n" + bad + " disagree -- run TableGen again");
    }

    private static int[] flat(BulletAnims.Step[] steps) {
        int[] out = new int[steps.length * 4];
        for (int i = 0; i < steps.length; i++) {
            out[i * 4] = steps[i].size();
            out[i * 4 + 1] = steps[i].slot();
            out[i * 4 + 2] = steps[i].frame();
            out[i * 4 + 3] = steps[i].rise();
        }
        return out;
    }

    private static void same(String what, int[] fromSource, int[] fromTable) {
        if (Arrays.equals(fromSource, fromTable)) {
            if (!what.contains("[")) {
                ok(what, fromSource.length + " numbers");
            }
            return;
        }
        fail(what, Arrays.toString(fromSource) + "\n        against "
                   + Arrays.toString(fromTable));
    }

    private static void ok(String what, String detail) {
        System.out.printf("  %-22s ok    %s%n", what, detail);
    }

    private static void fail(String what, String detail) {
        bad++;
        System.out.printf("  %-22s WRONG %s%n", what, detail);
    }
}
