package io.github.darthbob.kawanav;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Frames KawaNav sends, against the example frames in PROTOCOL.md and Rideology's captured requests. */
public class KawaProtocolTest {

    static String hex(byte[] b) {
        return KawaProtocol.hex(b).replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    public void navigationFramesFromProtocolDoc() {
        assertEquals("140800ffff057000ffffffffff10ff0571020000faffffffffffffffffffffffffffff",
                hex(KawaProtocol.naviFrame(0, 2, 1, 250)));
        assertEquals("140800ffff057000ffffffffff10ff057101000050ffffffffffffffffffffffffffff",
                hex(KawaProtocol.naviFrame(0, 1, 1, 80)));
        assertEquals("140800ffff057000ffffffffff10ff05711f000190ffffffffffffffffffffffffffff",
                hex(KawaProtocol.naviFrame(0, 31, 1, 400)));
        assertEquals("140800ffff057030fffffffffff0ff0571ff000000ffffffffffffffffffffffffffff",
                hex(KawaProtocol.clearFrame()));
    }

    @Test
    public void readRequests() {
        assertEquals("1b0c00ffff050a00ffffffffffffff", hex(KawaProtocol.generalSettingsRead()));
        assertEquals("482a00ffff050900" + "ff".repeat(37), hex(KawaProtocol.vehicleSettingsRead()));
        assertEquals("1e2a01ffff050c00ffffffffffffff050d00ffffffffffffff050e00" + "ff".repeat(17),
                hex(KawaProtocol.serviceIndicatorRead()));
        assertEquals("080c00ffff0a08017803e800c80064", hex(KawaProtocol.meterInitFrame()));
        assertArrayEquals(new byte[]{0x41, 0, 0}, KawaProtocol.readRequest(0x41));
    }

    @Test
    public void phoneNameLikeRideology() {
        assertEquals("0b2000ffff0501486f6d654173736905027374616e7400000000000000000000000000",
                hex(KawaProtocol.phoneNameFrame("HomeAssistant")));
    }

    @Test
    public void startupOrder() {
        List<String> ids = new ArrayList<>();
        for (BikeRequests.Request r : KawaProtocol.startup(KawaProtocol.INIT_FULL, "Pixel 9", null)) {
            ids.add(String.format("%02X", r.id()));
        }
        assertEquals(List.of("03", "40", "1A", "1D", "47", "0B", "41", "1B", "48", "1E", "08", "45"), ids);
        ids.clear();
        for (BikeRequests.Request r : KawaProtocol.startup(KawaProtocol.INIT_READ, null, null)) {
            ids.add(String.format("%02X", r.id()));
        }
        assertEquals(List.of("03", "40", "1A", "1D", "47", "41", "1B", "48", "1E"), ids);
        assertTrue(KawaProtocol.startup(KawaProtocol.INIT_NONE, null, null).size() == 1);
    }
}
