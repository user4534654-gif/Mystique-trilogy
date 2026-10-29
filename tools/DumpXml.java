import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Prints the attributes of a compiled android xml file, to see how the
 * dimensions are stored.
 */
public class DumpXml {

    public static void main(String[] args) throws Exception {
        Path file = Paths.get(args[0]);
        byte[] data = Files.readAllBytes(file);
        List<String> strings = readStrings(data);
        int offset = 8;
        while (offset + 8 <= data.length) {
            int type = readShort(data, offset);
            int size = readInt(data, offset + 4);
            if (size <= 0) {
                break;
            }
            if (type == 0x0102) {
                int name = readInt(data, offset + 20);
                int attributeSize = readShort(data, offset + 26);
                int attributeCount = readShort(data, offset + 28);
                int base = offset + size - attributeCount * attributeSize;
                System.out.println("E: " + str(strings, name));
                for (int i = 0; i < attributeCount; i++) {
                    int at = base + i * attributeSize;
                    int attrName = readInt(data, at + 4);
                    int dataType = data[at + 15] & 0xFF;
                    int value = readInt(data, at + 16);
                    System.out.println("    " + str(strings, attrName)
                            + " type=0x" + Integer.toHexString(dataType)
                            + " data=0x" + Integer.toHexString(value)
                            + " unit=" + (value & 0xFF)
                            + " number=" + (value >> 8));
                }
            }
            offset += size;
        }
    }

    private static String str(List<String> strings, int index) {
        if (index < 0 || index >= strings.size()) {
            return "?";
        }
        return strings.get(index);
    }

    private static List<String> readStrings(byte[] data) {
        List<String> result = new ArrayList<String>();
        int pool = 8;
        int count = readInt(data, pool + 8);
        int flags = readInt(data, pool + 16);
        int start = readInt(data, pool + 20);
        boolean utf8 = (flags & 0x100) != 0;
        for (int i = 0; i < count; i++) {
            int at = pool + start + readInt(data, pool + 28 + i * 4);
            if (utf8) {
                int len = data[at + 1];
                result.add(new String(data, at + 2, len, java.nio.charset.StandardCharsets.UTF_8));
            } else {
                int len = readShort(data, at);
                result.add(new String(data, at + 2, len * 2, java.nio.charset.StandardCharsets.UTF_16LE));
            }
        }
        return result;
    }

    private static int readShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }
}
