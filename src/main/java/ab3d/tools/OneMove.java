package ab3d.tools;

import ab3d.data.FloorLine;
import ab3d.data.GameData;
import ab3d.data.Level;
import ab3d.m68k.M68k;

/** Replays one move against one line and prints what each test decides. */
public final class OneMove {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        Level lv = Level.load(game, args[0]);
        int line = Integer.parseInt(args[1]);
        int ox = Integer.parseInt(args[2]), oz = Integer.parseInt(args[3]);
        int nx = Integer.parseInt(args[4]), nz = Integer.parseInt(args[5]);
        int extLen = 40;

        FloorLine fl = lv.floorLine(line);
        System.out.printf("line %d: (%d,%d) d(%d,%d) len %d toZone %d%n",
                          line, fl.x1, fl.z1, fl.dx, fl.dz, fl.length, fl.toZone);
        System.out.printf("move (%d,%d) -> (%d,%d)%n", ox, oz, nx, nz);

        int dxl = M68k.w(fl.dx), dzl = M68k.w(fl.dz);
        int cross = M68k.muls(dzl, M68k.w(nx - fl.x1)) - M68k.muls(dxl, M68k.w(nz - fl.z1));
        int len = M68k.w(fl.length + extLen);
        System.out.printf("  cross(new) %d, len+ext %d, d = %d%n",
                          cross, len, M68k.w(M68k.divsInto(cross, len)));

        // first pass: the projection and the segment test
        int d7 = M68k.w(M68k.divsInto(cross, len));
        int px = M68k.w(-M68k.w(M68k.divsInto(M68k.muls(d7, dzl), len)) + nx);
        int pz = M68k.w(M68k.w(M68k.divsInto(M68k.muls(d7, dxl), len)) + nz);
        int rx = M68k.w(px - fl.x1), rz = M68k.w(pz - fl.z1);
        System.out.printf("  pass 1: projected (%d,%d), relative (%d,%d)%n", px, pz, rx, rz);
        System.out.printf("          on the segment? %s  (|dx| %d |dz| %d)%n",
                          onSeg(rx, rz, dxl, dzl), Math.abs(dxl), Math.abs(dzl));
        System.out.printf("          cross after the slide would be %d%n",
                          M68k.muls(dzl, M68k.w(px - fl.x1))
                        - M68k.muls(dxl, M68k.w(pz - fl.z1)));

        // second pass: the straddle test
        int g = M68k.w(nx - ox), h = M68k.w(nz - oz);
        long first = (long) M68k.muls(M68k.w(fl.x1 - ox), h) - M68k.muls(M68k.w(fl.z1 - oz), g);
        long second = (long) M68k.muls(M68k.w(M68k.w(fl.x1 + dxl) - ox), h)
                    - M68k.muls(M68k.w(M68k.w(fl.z1 + dzl) - oz), g);
        System.out.printf("  pass 2: straddle first %d (needs <= 0), second %d (needs >= 0)%n",
                          first, second);
        long num = (long) M68k.muls(h, M68k.w(ox - fl.x1)) + M68k.muls(g, M68k.w(fl.z1 - oz));
        long den = (long) M68k.muls(h, dxl) - M68k.muls(g, dzl);
        System.out.printf("          parameter num %d den %d -> %s%n", num, den,
                          den == 0 ? "no crossing"
                                   : (den > 0 ? (num >= 0 && den >= num) : (num <= 0 && den <= num))
                                     ? "crosses" : "outside [0,1]");
    }

    private static boolean onSeg(int rx, int rz, int dx, int dz) {
        int ax = Math.abs(dx), az = Math.abs(dz);
        if (az > ax) {
            return rz > 0 ? (dz >= -4 && rz <= dz + 4) : (dz <= 4 && rz >= dz - 4);
        }
        return rx > 0 ? (dx >= -4 && rx <= dx + 4) : (dx <= 4 && rx >= dx - 4);
    }
}
