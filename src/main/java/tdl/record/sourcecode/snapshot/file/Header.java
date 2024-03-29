package tdl.record.sourcecode.snapshot.file;

import tdl.record.sourcecode.snapshot.helpers.ByteHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class Header {

    /**
     * The header size. 6 for magic bytes. 8 for timestamp.
     */
    public static int SIZE = 14;

    public static final byte[] MAGIC_BYTES = new byte[]{0x53 /*S*/, 0x52 /*R*/, 0x43 /*C*/, 0x53 /*S*/, 0x54 /*T*/, 0x4d /*M*/};

    private long timestamp;

    private byte[] magicBytes = MAGIC_BYTES;

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public byte[] getMagicBytes() {
        return magicBytes;
    }

    public void setMagicBytes(byte[] magicBytes) {
        this.magicBytes = magicBytes;
    }

    public boolean isValid() {
        return Arrays.equals(getMagicBytes(), MAGIC_BYTES);
    }

    public byte[] asBytes() {
        try (ByteArrayOutputStream byteArray = new ByteArrayOutputStream()) {
            byteArray.write(getMagicBytes());
            byteArray.write(ByteHelper.littleEndianLongToByteArray(getTimestamp(), 8));
            return byteArray.toByteArray();
        } catch (IOException ex) {
            return new byte[0];
        }
    }

}
