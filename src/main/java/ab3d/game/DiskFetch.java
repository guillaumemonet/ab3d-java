package ab3d.game;

import ab3d.tools.AdfTool;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fetching the two floppies from Dream17, for a player who does not have them
 * to hand.
 *
 * Dream17 is an Amiga preservation archive; the archive it serves for this game
 * holds both disk images in one file. It does not hold the source release, so
 * the other half of what this port needs still has to be pointed at -- four of
 * the twenty-two files read at runtime are on the disks and the rest were built
 * into the Amiga executable.
 *
 * Nothing here happens on its own. The first run offers it as one of two ways to
 * find the disks, and the other is to say where your own copies are.
 */
public final class DiskFetch {

    /** Dream17's archive page for Alien Breed 3D. */
    public static final String URL = "https://dream17.abime.net/download.php?id=48";
    /** An Amiga floppy is this many bytes, and the check refuses anything else. */
    private static final int ADF_SIZE = 901120;
    private static final int MAX_DOWNLOAD = 32 * 1024 * 1024;

    private DiskFetch() {
    }

    /**
     * Downloads the archive, takes the disk images out of it, and unpacks them.
     *
     * @param into  where the unpacked disks go, as {@code disk1} and {@code disk2}
     * @param say   told what is happening, so a window can show it
     * @return how many disks were unpacked
     */
    public static int fetch(Path into, Consumer<String> say) throws IOException {
        say.accept("Téléchargement depuis Dream17…");
        byte[] archive = download();
        say.accept("Archive reçue : " + archive.length / 1024 + " Ko");

        List<byte[]> disks = adfsIn(archive);
        if (disks.isEmpty()) {
            throw new IOException("l'archive ne contient aucune image de disquette "
                                  + "de la bonne taille");
        }

        int done = 0;
        for (byte[] image : disks) {
            AdfTool disk = new AdfTool(image);
            boolean second = disk.has("levels");
            say.accept("Extraction de la disquette " + (second ? "2" : "1") + "…");
            disk.extractAll(into.resolve(second ? "disk2" : "disk1"));
            done++;
        }
        if (!Files.isDirectory(into.resolve("disk2/levels"))) {
            throw new IOException("la disquette des niveaux n'est pas dans l'archive");
        }
        say.accept(done + " disquettes extraites");
        return done;
    }

    /** {@code GET} with the redirect followed, and a cap on what is accepted. */
    private static byte[] download() throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "ab3d-java")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("le serveur a répondu " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length > MAX_DOWNLOAD) {
                throw new IOException("réponse trop grande : " + body.length);
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("téléchargement interrompu", e);
        }
    }

    /**
     * The disk images inside a zip.
     *
     * Taken by their size rather than their name: every Amiga floppy is exactly
     * eight hundred and eighty kilobytes, and the names in these archives carry
     * whatever the person who made the images called them.
     */
    private static List<byte[]> adfsIn(byte[] archive) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(
                new java.io.ByteArrayInputStream(archive))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                byte[] data = zip.readAllBytes();
                if (data.length == ADF_SIZE) {
                    out.add(data);
                }
            }
        }
        return out;
    }
}
