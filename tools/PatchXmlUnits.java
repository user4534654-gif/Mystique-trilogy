import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Rewrites the compiled android xml of a layout so that every dimension that
 * is stored in pixels becomes a density independent pixel.
 *
 * Chapters two and three of Mystique were built with a layout that uses
 * pixels instead of dp for every size. On a phone with a high density the
 * whole interface is then far too small, the inventory panel for example is
 * 75 pixels wide while the items inside it are 75 dp, so they do not fit into
 * it.
 *
 * The unit of a dimension is the lowest byte of its value, so the fix is to
 * change that byte from pixels (0) to dp (1). The number stays the same, which
 * is exactly what is wanted: 75 px becomes 75 dp.
 *
 * Usage: java PatchXmlUnits <file> [file...]
 */
public class PatchXmlUnits {

    private static final int CHUNK_XML = 0x0003;
    private static final int CHUNK_START_TAG = 0x0102;
    private static final int TYPE_DIMENSION = 0x05;
    private static final int UNIT_PX = 0x00;
    private static final int UNIT_DIP = 0x01;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("no file given");
            return;
        }
        for (String arg : args) {
            Path file = Paths.get(arg);
            if (!Files.exists(file)) {
                System.out.println("missing " + file);
                continue;
            }
            byte[] data = Files.readAllBytes(file);
            int changed = patch(data);
            if (changed > 0) {
                Files.write(file, data);
            }
            System.out.println(file.getFileName() + ": " + changed + " dimensions changed to dp");
        }
    }

    private static int patch(byte[] data) throws IOException {
        if (data.length < 8) {
            return 0;
        }
        int type = readShort(data, 0);
        if (type != CHUNK_XML) {
            throw new IOException("not a compiled xml file");
        }
        int changed = 0;
        int offset = readInt(data, 4) == 0 ? 8 : 8;
        while (offset + 8 <= data.length) {
            int chunkType = readShort(data, offset);
            int chunkSize = readInt(data, offset + 4);
            if (chunkSize <= 0) {
                break;
            }
            if (chunkType == CHUNK_START_TAG) {
                changed += patchTag(data, offset);
            }
            offset += chunkSize;
        }
        return changed;
    }

    private static int patchTag(byte[] data, int offset) {
        int attributeStart = readShort(data, offset + 24);
        int attributeSize = readShort(data, offset + 26);
        int attributeCount = readShort(data, offset + 28);
        int chunkSize = readInt(data, offset + 4);
        // the attributes are the last thing in the chunk, the offset in the
        // file is not reliable so it is taken from the size of the chunk
        int base = offset + chunkSize - attributeCount * attributeSize;
        if (attributeStart < 0 || attributeSize <= 0 || attributeCount <= 0) {
            return 0;
        }
        int changed = 0;
        for (int i = 0; i < attributeCount; i++) {
            int at = base + i * attributeSize;
            if (at + 20 > data.length) {
                break;
            }
            int dataType = data[at + 15] & 0xFF;
            if (dataType != TYPE_DIMENSION) {
                continue;
            }
            int value = readInt(data, at + 16);
            int unit = value & 0xFF;
            if (unit != UNIT_PX) {
                continue;
            }
            int fixed = (value & 0xFFFFFF00) | UNIT_DIP;
            writeInt(data, at + 16, fixed);
            changed++;
        }
        return changed;
    }

    private static int readShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }
}
