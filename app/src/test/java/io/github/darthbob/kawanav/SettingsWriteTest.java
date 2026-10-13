package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The ride-settings write frame, the KTRC mapping and the "may we write now" checks. */
public class SettingsWriteTest {

    static byte[] hx(String s) {
        return KawaProtocol.parseHex(s);
    }

    @Test
    public void frameLayout() {
        byte[] f = SettingsWrite.frame(1 /* Road */, 0 /* Full */, SettingsWrite.ktrcWire(2) /* level 2 */, 1, 0, "AB");
        assertEquals(45, f.length);
        assertEquals(0x48, f[0] & 0xFF);
        assertEquals(0x2A, f[1] & 0xFF);
        assertEquals(0x09, f[6] & 0xFF);
        assertEquals(1, f[7] & 0xFF);                       // write
        assertEquals((2 << 3) | 1, f[8] & 0xFF);            // KTRC level 2 -> wire 2, mode Road = 1
        assertEquals((1 << 5) | (0 << 2) | 0, f[9] & 0xFF); // KQS 1, KEBC 0, power 0
        assertEquals(0x05, f[15] & 0xFF);
        assertEquals(0x04, f[16] & 0xFF);
        assertEquals('A', f[17]);
        assertEquals('B', f[18]);
        assertEquals(0, f[19]);                             // zero padded
        assertEquals(0x05, f[25] & 0xFF);
        assertEquals(0x05, f[26] & 0xFF);                   // second name block
        assertEquals(0x05, f[35] & 0xFF);
        assertEquals(0x06, f[36] & 0xFF);
        assertEquals(0xFF, f[10] & 0xFF);                   // KECS bytes stay untouched
    }

    @Test
    public void ktrcWireMapping() {
        assertEquals(10, SettingsWrite.ktrcWire(0));  // OFF
        assertEquals(1, SettingsWrite.ktrcWire(1));
        assertEquals(5, SettingsWrite.ktrcWire(5));
        assertEquals(1, SettingsWrite.ktrcFromWire(1));
        assertEquals(0, SettingsWrite.ktrcFromWire(10));  // OFF back to the list position
        assertEquals(2, SettingsWrite.ktrcFromWire(2));
        assertEquals(-1, SettingsWrite.ktrcFromWire(9));
    }

    @Test
    public void longNameIsCutAtEighteenBytes() {
        byte[] f = SettingsWrite.frame(0, 0, 1, 0, 0, "abcdefghijklmnopqrstuvwxyz");
        assertEquals('a', f[17]);
        assertEquals('h', f[24]);   // first block: 8 bytes
        assertEquals('i', f[27]);
        assertEquals('p', f[34]);   // second block: bytes 8..15
        assertEquals('q', f[37]);
        assertEquals('r', f[38]);   // third block: bytes 16..17
    }

    @Test
    public void results() {
        assertEquals(0, SettingsWrite.result(hx("482a004800050900" + "ffffffffffffffffffffffffffffffffffffffff")));
        assertEquals(0x10, SettingsWrite.result(hx("482a004800050910" + "ffffffffffffffffffffffffffffffffffffffff")));
        assertEquals(-1, SettingsWrite.result(hx("4120")));       // not a 0x48 frame
        assertEquals(-1, SettingsWrite.result(null));
    }

    @Test
    public void preconditions() {
        BikeFrames.TuningConfig tuning = new BikeFrames.TuningConfig(hx(
                "471682470005120000ffffffffffffffffffffffffffffffffff"));
        // standing (speed 0), neutral (gear 0), throttle closed (0)
        byte[] v = new byte[55];
        java.util.Arrays.fill(v, (byte) 0xFF);
        v[0] = 0x4A;
        v[1] = 0x34;
        v[5] = 0x05;
        v[6] = 0x15;
        for (int i = 7; i <= 14; i++) v[i] = 0;
        v[15] = 0x05;
        v[16] = 0x16;
        for (int i = 17; i <= 24; i++) v[i] = 0;
        v[35] = 0x05;
        BikeFrames.InfoConfig cfg = (BikeFrames.InfoConfig) new BikeFrames.Decoder().decode(
                BikeFramesTest.infoConfig(0x04, 0x05, 0x00));
        BikeFrames.Telemetry live = new BikeFrames.Telemetry(v, cfg);
        assertEquals(0.0, live.speedKmh, 1e-9);
        assertEquals(0, live.gear);
        assertEquals(0.0, live.throttlePct, 1e-9);
        assertNull(SettingsWrite.blockedBecause(live, 500, tuning));
        assertTrue(SettingsWrite.blockedBecause(live, 10_000, tuning).contains("no current data"));
        assertTrue(SettingsWrite.blockedBecause(null, 0, tuning).contains("no current data"));
        v[10] = (byte) 50; // 50 km/h
        BikeFrames.Telemetry moving = new BikeFrames.Telemetry(v, cfg);
        assertEquals(50.0, moving.speedKmh, 1e-9);
        assertEquals("the bike must stand still", SettingsWrite.blockedBecause(moving, 200, tuning));
    }
}
