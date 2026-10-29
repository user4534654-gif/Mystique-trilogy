import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites an apk so that the given file extensions are stored without
 * compression.
 *
 * The sound files of the apk have to be uncompressed, SoundPool can only load
 * them when they are stored in the zip without deflate. The .NET zip
 * implementation can not do that, it always deflates, so this small helper
 * does the job.
 *
 * Usage: java StoreUncompressed <apk> <ext> [ext...]
 */
public class StoreUncompressed {

    public static void main(String[] args) throws Exception {
        Path apk = Paths.get(args[0]);
        Set<String> extensions = new HashSet<>();
        for (int i = 1; i < args.length; i++) {
            extensions.add(args[i].toLowerCase());
        }

        Path tmp = Paths.get(apk.toString() + ".repack");
        int stored = 0;
        try (ZipFile source = new ZipFile(apk.toFile());
             ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(tmp))) {
            target.setLevel(Deflater.DEFAULT_COMPRESSION);
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                boolean keep = extensions.contains(extension(entry.getName()));
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                if (entry.getExtra() != null) {
                    copy.setExtra(entry.getExtra());
                }
                if (keep) {
                    byte[] data = readAll(source.getInputStream(entry));
                    CRC32 crc = new CRC32();
                    crc.update(data, 0, data.length);
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(data.length);
                    copy.setCompressedSize(data.length);
                    copy.setCrc(crc.getValue());
                    target.putNextEntry(copy);
                    target.write(data);
                    target.closeEntry();
                    stored++;
                } else {
                    copy.setMethod(ZipEntry.DEFLATED);
                    target.putNextEntry(copy);
                    try (InputStream in = source.getInputStream(entry)) {
                        copyStream(in, target);
                    }
                    target.closeEntry();
                }
            }
        }
        Files.move(tmp, apk, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("stored " + stored + " entries without compression");
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        int slash = name.lastIndexOf('/');
        if (dot < slash) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        copyStream(in, buffer);
        return buffer.toByteArray();
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[65536];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }
}
