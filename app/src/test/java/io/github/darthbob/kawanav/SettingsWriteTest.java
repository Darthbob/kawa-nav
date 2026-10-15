package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        // the bike's own numbers: Road = 2, Full = 1, KQS ON = 2, KEBC OFF = 1
        byte[] f = SettingsWrite.frame(2 /* Road */, 1 /* Full */, SettingsWrite.ktrcWire(2) /* level 2 */, 2, 1, "AB");
        assertEquals(45, f.length);
        assertEquals(0x48, f[0] & 0xFF);
        assertEquals(0x2A, f[1] & 0xFF);
        assertEquals(0x09, f[6] & 0xFF);
        assertEquals(1, f[7] & 0xFF);                       // write
        assertEquals((2 << 3) | 2, f[8] & 0xFF);            // KTRC level 2 -> wire 2, mode Road = 2
        assertEquals((2 << 5) | (1 << 2) | 1, f[9] & 0xFF); // KQS ON 2, KEBC OFF 1, power Full 1
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

    /** Rideology's tables put "error" at 0: the bike's 1 is Sport, Full, OFF. */
    @Test
    public void namesStartAtOne() {
        assertEquals("Sport (1)", SettingsWrite.named(SettingsWrite.MODES, 1));
        assertEquals("Rider (4)", SettingsWrite.named(SettingsWrite.MODES, 4));
        assertEquals("Rider 2 (5)", SettingsWrite.named(SettingsWrite.MODES, 5));
        assertEquals("? (0)", SettingsWrite.named(SettingsWrite.MODES, 0));
        assertEquals("Full (1)", SettingsWrite.named(SettingsWrite.POWER, 1));
        assertEquals("OFF (1)", SettingsWrite.named(SettingsWrite.ON_OFF, 1));
        assertEquals("ON (2)", SettingsWrite.named(SettingsWrite.ON_OFF, 2));
        assertEquals("Light (3)", SettingsWrite.named(SettingsWrite.KEBC, 3));
        assertEquals("OFF (10)", SettingsWrite.ktrcName(10));
        assertEquals("7 (7)", SettingsWrite.ktrcName(7));     // readable even though only 1-5 are writable
        assertEquals("-5 (1)", SettingsWrite.adjustment(1));
        assertEquals("0 (6)", SettingsWrite.adjustment(6));
        assertEquals("+5 (11)", SettingsWrite.adjustment(11));
    }

    /** A 0 or a number off the end would be a setting the bike never offered: never sent. */
    /** A 0x47 frame whose riding-mode nibble (byte 8 bits 5-4) has the given value. */
    private static BikeFrames.TuningConfig tuning(int modeNibble) {
        byte[] v = new byte[25];
        java.util.Arrays.fill(v, (byte) 0xFF);
        v[0] = 0x47;
        v[1] = 22;
        v[5] = 0x05;
        v[6] = 0x12;
        v[8] = (byte) (modeNibble << 4);
        v[14] = 0x01;   // ready: bit 0 set means the meter has finished preparing the config
        return (BikeFrames.TuningConfig) new BikeFrames.Decoder().decode(v);
    }

    @Test
    public void modeCountFollowsTheBike() {
        assertEquals(7, SettingsWrite.modeCount(tuning(1)));
        assertEquals(4, SettingsWrite.modeCount(tuning(0)));
        assertEquals(4, SettingsWrite.modeCount(tuning(3)));   // the bike says it has none
        assertEquals(4, SettingsWrite.modeCount(null));
        // a config that has not arrived yet must never open the longer list
        byte[] notReady = new byte[25];
        java.util.Arrays.fill(notReady, (byte) 0xFF);
        notReady[0] = 0x47;
        notReady[1] = 22;
        notReady[5] = 0x05;
        notReady[6] = 0x12;
        notReady[8] = 0x10;
        notReady[14] = 0x00;   // header present but bit 0 clear: still being prepared
        BikeFrames.TuningConfig pending = (BikeFrames.TuningConfig) new BikeFrames.Decoder().decode(notReady);
        assertFalse(pending.valid);
        assertEquals(4, SettingsWrite.modeCount(pending));
    }

    @Test
    public void theRiderModeGoesOnTheWireAsSeven() {
        // mode 7 with KTRC 4: byte 8 = (4 << 3) | 7
        byte[] f = SettingsWrite.frame(7, 1, 4, 2, 1, "");
        assertEquals(7, f[8] & 0x07);
        assertEquals(4, (f[8] & 0xF8) >> 3);
    }

    @Test
    public void invalidFollowsTheBikesCount() {
        assertNull(SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES_7, 7, 1, 0, 1, 0));
        assertTrue(SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES, 5, 1, 0, 1, 0).contains("riding mode"));
        assertTrue(SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES_7, 8, 1, 0, 1, 0).contains("riding mode"));
    }

    @Test
    public void outOfRangeValuesAreRefused() {
        assertNull(SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES, 1, 1, 0, 1, 0));    // Sport, Full, KTRC OFF, KQS OFF, no KEBC
        assertNull(SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES, 4, 3, 5, 2, 3));    // Rider, Low, KTRC 5, KQS ON, KEBC Light
        assertTrue(SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES, 0, 1, 0, 1, 0).contains("riding mode"));
        assertTrue(SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES, 5, 1, 0, 1, 0).contains("riding mode"));
        assertTrue(SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES, 1, 0, 0, 1, 0).contains("power"));
        assertTrue(SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES, 1, 1, 6, 1, 0).contains("KTRC"));
        assertTrue(SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES, 1, 1, 0, 0, 0).contains("quickshifter"));
        assertTrue(SettingsWrite.invalid(SettingsWrite.WRITABLE_MODES, 1, 1, 0, 1, 4).contains("engine brake"));
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
