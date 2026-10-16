package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrameReaderTest {

    static final byte[] V = KawaProtocol.parseHex("41 52 86 41 00 05 14 01 02 03 04 ff ff ff 9e ff ff");

    @Test
    public void numbers() {
        assertEquals(0x41, FrameReader.u8(V, 0));
        assertEquals(-1, FrameReader.u8(V, 99));
        assertEquals(0x0102, FrameReader.be16(V, 7));
        assertEquals(0x010203, FrameReader.be24(V, 7));
        assertEquals(0x01020304L, FrameReader.be32(V, 7));
        assertEquals(-1, FrameReader.be16(V, 16));
        assertEquals(2, FrameReader.bits(V, 14, 0xC0)); // 0x9e = 10 011110
        assertEquals(0x0E, FrameReader.bits(V, 14, 0x0F));
    }

    @Test
    public void blocks() {
        assertTrue(FrameReader.blockPresent(V, 5));
        assertFalse(FrameReader.blockPresent(V, 15));
        assertEquals(0x14, FrameReader.blockId(V, 5));
        assertEquals(5, FrameReader.blockOf(14));
        assertEquals(15, FrameReader.blockOf(15));
        assertTrue(FrameReader.inPresentBlock(V, 10));
        assertFalse(FrameReader.inPresentBlock(V, 16));
        assertTrue(FrameReader.allFF(V, 11, 14));
    }

    @Test
    public void text() {
        assertEquals("HomeAssistant", FrameReader.textChunks(KawaProtocol.parseHex(
                "0b2000ffff0501486f6d654173736905027374616e7400000000000000000000000000")));
    }
}
