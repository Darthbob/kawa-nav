package io.github.darthbob.kawanav;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FrameReplayTest {

    @Test
    public void parsesReceivedFrames() {
        Object[] f = FrameReplay.parse("1760371200123 RX 02fad1bd 4152864100");
        assertEquals(1760371200123L, (long) f[0]);
        assertEquals(KawaProtocol.NOTIFY_CHARS[1], f[1]);
        assertArrayEquals(new byte[]{0x41, 0x52, (byte) 0x86, 0x41, 0x00}, (byte[]) f[2]);
    }

    @Test
    public void skipsSentFramesAndJunk() {
        assertNull(FrameReplay.parse("1760371200123 TX - 410000"));
        assertNull(FrameReplay.parse("garbage"));
        assertNull(FrameReplay.parse("x RX 3aabbb34 41"));
    }
}
