package ab3d.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Amiga disk images (.adf) so the shipped game data can be taken straight
 * off the original floppies.
 *
 * Both AB3D disks are ordinary 880K AmigaDOS images: disk 1 is OFS
 * ({@code DOS\0}), disk 2 is FFS ({@code DOS\1}). The only difference that
 * matters here is that OFS data blocks carry a 24-byte header and hold 488 bytes
 * of payload, while FFS blocks are 512 bytes of pure data.
 */
public final class AdfTool {

    private static final int BS = 512;
    private static final int ROOT_BLOCK = 880;
    /** Hash table entries in a root or directory block. */
    private static final int HT_SIZE = 72;

    private static final int ST_USERDIR = 2;
    private static final int ST_FILE = -3;

    private final byte[] img;
    private final boolean ffs;

    public AdfTool(byte[] img) {
        this.img = img;
        this.ffs = (img[3] & 1) != 0;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: AdfTool <disk.adf> [outputDir]");
            System.exit(2);
        }
        Path adf = Path.of(args[0]);
        AdfTool disk = new AdfTool(Files.readAllBytes(adf));

        System.out.println(adf.getFileName() + "  volume '" + disk.volumeName()
                + "'  " + (disk.ffs ? "FFS" : "OFS"));

        List<Entry> entries = disk.list();
        if (args.length < 2) {
            for (Entry e : entries) {
                System.out.printf("  %-44s %8d%n", e.path, e.size);
            }
            System.out.println("  " + entries.size() + " files");
            return;
        }

        Path out = Path.of(args[1]);
        for (Entry e : entries) {
            Path dst = out.resolve(e.path);
            Files.createDirectories(dst.getParent());
            Files.write(dst, disk.read(e));
        }
        System.out.println("  extracted " + entries.size() + " files to " + out);
    }

    /** A file on the disk. */
    public record Entry(String path, int size, int headerBlock) {}

    public String volumeName() {
        return nameOf(ROOT_BLOCK);
    }

    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        walk(ROOT_BLOCK, "", out);
        return out;
    }

    private void walk(int block, String prefix, List<Entry> out) {
        for (int i = 0; i < HT_SIZE; i++) {
            int entry = s32(block * BS + 24 + i * 4);
            while (entry > 0 && (entry + 1) * BS <= img.length) {
                int secType = s32(entry * BS + 508);
                String name = prefix + nameOf(entry);
                if (secType == ST_USERDIR) {
                    walk(entry, name + "/", out);
                } else if (secType == ST_FILE) {
                    out.add(new Entry(name, s32(entry * BS + 324), entry));
                }
                entry = s32(entry * BS + 496);   // hash chain
            }
        }
    }

    /** Reads a file by following its header block and any extension blocks. */
    public byte[] read(Entry e) throws IOException {
        byte[] out = new byte[e.size];
        int written = 0;
        int header = e.headerBlock;

        while (header > 0) {
            int used = s32(header * BS + 8);              // high_seq
            for (int i = 0; i < used && written < out.length; i++) {
                // Block pointers are stored back to front
                int block = s32(header * BS + 24 + (HT_SIZE - 1 - i) * 4);
                if (block <= 0 || (block + 1) * BS > img.length) {
                    continue;
                }
                int from = block * BS + (ffs ? 0 : 24);
                int len = Math.min(ffs ? BS : BS - 24, out.length - written);
                System.arraycopy(img, from, out, written, len);
                written += len;
            }
            header = s32(header * BS + 504);              // extension block
        }
        if (written < out.length) {
            throw new IOException("Short read for " + e.path
                    + ": got " + written + " of " + out.length);
        }
        return out;
    }

    private String nameOf(int block) {
        int len = img[block * BS + 432] & 0xff;
        return new String(img, block * BS + 433, Math.min(len, 30),
                java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private int s32(int off) {
        return ((img[off] & 0xff) << 24) | ((img[off + 1] & 0xff) << 16)
             | ((img[off + 2] & 0xff) << 8) | (img[off + 3] & 0xff);
    }
}
