package ab3d.tools;

import ab3d.data.GameData;
import ab3d.data.VectorModel;

/**
 * Do the ten models parse, and does what they hold make sense?
 *
 * The format is not written down anywhere; it was read off the routine that
 * walks it. So the check that it was read right is that all ten files come apart
 * cleanly and land exactly on their own length -- a wrong stride would run off
 * the end or stop short, and a wrong header would give a nonsense count.
 */
public final class VectorCheck {

    public static void main(String[] args) throws Exception {
        GameData game = GameData.fromSystemProperty();
        int bad = 0;
        for (int slot = 0; slot < VectorModel.SLOTS.length; slot++) {
            try {
                VectorModel m = VectorModel.load(game, slot);
                int faces = 0;
                int edges = 0;
                int textured = 0;
                int gouraud = 0;
                int holes = 0;
                for (VectorModel.Part part : m.parts()) {
                    for (VectorModel.Polygon f : part.faces()) {
                        faces++;
                        edges += f.edges();
                        if (f.textureAt() != 0) {
                            textured++;
                        }
                        if (f.gouraud()) {
                            gouraud++;
                        }
                        if (f.holes()) {
                            holes++;
                        }
                        for (int p : f.point()) {
                            if (p < 0 || p >= m.pointCount()) {
                                System.out.printf("  %-10s face points at %d, "
                                                  + "outside its %d%n",
                                                  m.name(), p, m.pointCount());
                                bad++;
                            }
                        }
                    }
                }
                System.out.printf("  slot %d %-10s %3d points %2d frames "
                                  + "%2d parts %3d faces %4d edges  "
                                  + "%d textured, %d gouraud, %d holed%n",
                                  slot, m.name(), m.pointCount(), m.frameCount(),
                                  m.parts().length, faces, edges,
                                  textured, gouraud, holes);
            } catch (Exception e) {
                System.out.printf("  slot %d %-10s WOULD NOT PARSE: %s%n",
                                  slot, VectorModel.SLOTS[slot], e.getMessage());
                bad++;
            }
        }
        System.out.println(bad == 0
                ? "\nall ten models come apart cleanly"
                : "\n" + bad + " problems");
    }
}
