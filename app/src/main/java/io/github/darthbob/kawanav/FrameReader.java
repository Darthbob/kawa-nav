package io.github.darthbob.kawanav;

/**
 * Bounds-checked reads from meter frames. Frames are a 5-byte header ([0] type, [1] length - 3,
 * [2] counter, [3..4] echoed request or FF) followed by 10-byte blocks: [marker][block id][8 data
 * bytes], marker 05 for data, FF when the block is absent. Pure Java (no Android) for JVM tests.
 */
public final class FrameReader {
    private FrameReader() {}

    /** Marker byte of a present data block. */
    public static final int BLOCK_MARKER = 0x05;

    /** Unsigned byte, -1 past the end. */
    public static int u8(byte[] v, int i) {
        return i >= 0 && i < v.length ? v[i] & 0xFF : -1;
    }

    /** Big-endian unsigned 16 bit, -1 past the end. */
    public static int be16(byte[] v, int i) {
        if (i < 0 || i + 1 >= v.length) return -1;
        return (v[i] & 0xFF) << 8 | (v[i + 1] & 0xFF);
    }

    /** Big-endian unsigned 24 bit, -1 past the end. */
    public static int be24(byte[] v, int i) {
        if (i < 0 || i + 2 >= v.length) return -1;
        return (v[i] & 0xFF) << 16 | (v[i + 1] & 0xFF) << 8 | (v[i + 2] & 0xFF);
    }

    /** Big-endian unsigned 32 bit, -1 past the end. */
    public static long be32(byte[] v, int i) {
        if (i < 0 || i + 3 >= v.length) return -1;
        return ((long) (v[i] & 0xFF) << 24) | (v[i + 1] & 0xFF) << 16 | (v[i + 2] & 0xFF) << 8 | (v[i + 3] & 0xFF);
    }

    /** The bits of byte {@code i} under {@code mask}, shifted down; -1 past the end. */
    public static int bits(byte[] v, int i, int mask) {
        int b = u8(v, i);
        return b < 0 ? -1 : (b & mask) >>> Integer.numberOfTrailingZeros(mask);
    }

    /** The block starting at {@code offset} (5, 15, 25…) is present: its marker is 05. */
    public static boolean blockPresent(byte[] v, int offset) {
        return u8(v, offset) == BLOCK_MARKER;
    }

    /** Block id of the block at {@code offset}, -1 if absent. */
    public static int blockId(byte[] v, int offset) {
        return blockPresent(v, offset) ? u8(v, offset + 1) : -1;
    }

    /** All bytes from {@code from} to {@code to} (exclusive) are FF, or past the end. */
    public static boolean allFF(byte[] v, int from, int to) {
        for (int i = from; i < to && i < v.length; i++) {
            if ((v[i] & 0xFF) != 0xFF) return false;
        }
        return true;
    }

    /** Start (marker index) of the 10-byte block holding byte {@code i}. */
    public static int blockOf(int i) {
        return i < 5 ? -1 : 5 + (i - 5) / 10 * 10;
    }

    /** Byte {@code i} lies in a present block. */
    public static boolean inPresentBlock(byte[] v, int i) {
        return i < v.length && blockPresent(v, blockOf(i));
    }

    /** UTF-8 text of the {@code 05 <n> <8 bytes>} chunks from byte 5 on, in chunk order; zero/FF padding dropped. */
    public static String textChunks(byte[] v) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 5; i + 1 < v.length; i += 10) {
            if ((v[i] & 0xFF) != BLOCK_MARKER) continue;
            for (int k = i + 2; k < i + 10 && k < v.length; k++) {
                int b = v[k] & 0xFF;
                if (b != 0 && b != 0xFF) out.write(b);
            }
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
    }
}
