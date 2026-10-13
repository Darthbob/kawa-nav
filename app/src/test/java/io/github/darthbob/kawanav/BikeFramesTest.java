package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Decoding of meter frames. The "Z500" frames are real frames from a Kawasaki Z500 (ER500F), taken from
 * Zen3515/homeassistant-kawasaki-rideology-ble, tests/test_sample_frames.py (Apache License 2.0).
 * The other frames are built here to check the arithmetic of fields the Z500 does not have.
 */
public class BikeFramesTest {

    static byte[] hx(String s) {
        return KawaProtocol.parseHex(s);
    }

    /** A frame of {@code length} FF bytes with the given header. */
    static byte[] blank(int id, int length) {
        byte[] b = new byte[length];
        java.util.Arrays.fill(b, (byte) 0xFF);
        b[0] = (byte) id;
        b[1] = (byte) (length - 3);
        b[2] = 0x10;
        return b;
    }

    static void put(byte[] b, int at, int... bytes) {
        for (int i = 0; i < bytes.length; i++) b[at + i] = (byte) bytes[i];
    }

    // ------------------------------------------------------------------ Z500 samples

    static final String Z500_03 = "03207a030002714d4c35455235303002724641444135353535027338ffffffffffffff";
    static final String Z500_1A = "1a0c7e1a00ffffffffffffffffffff";
    static final String Z500_1B = "1b0c894800ffffffffffffffffffff";
    static final String Z500_1D = "1d0c801d00ffffffffffffffffffff";
    static final String Z500_1E = "1e2a911e01ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
    static final String Z500_40 = "40207c40000511fc77d417ffffffffffffffffffffffffffffffffffffffffffffffff";
    static final String Z500_41 = "41528641000514000000000000009effffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
    static final String Z500_45 = "4534a4ffffffffffffffffffffffff05175000004c00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
    static final String Z500_47 = "4716824700051260ffffffffffffffffffffffffffffffffff";
    static final String Z500_48 = "48168c4800ffffffffffffffffffffffffffffffffffffffff";
    static final String Z500_4B = "4b2a94ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    @Test
    public void modelInfo() {
        BikeFrames.ModelInfo m = (BikeFrames.ModelInfo) new BikeFrames.Decoder().decode(hx(Z500_03));
        assertEquals("ML5ER500", m.model);
        assertEquals("ML5ER500FADA55558", m.vin);
    }

    @Test
    public void generalCapsWithoutNavigation() {
        BikeFrames.GeneralCaps c = (BikeFrames.GeneralCaps) new BikeFrames.Decoder().decode(hx(Z500_1A));
        assertTrue(c.valid);
        assertEquals(15, c.navi);
        assertFalse(c.turnByTurn);
        assertFalse(c.shiftLampSettable);
        assertFalse(c.clockSettable());
    }

    @Test
    public void generalSettingsAbsent() {
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(hx(Z500_1A));
        BikeFrames.GeneralSettings g = (BikeFrames.GeneralSettings) d.decode(hx(Z500_1B));
        assertEquals(-1, g.result);
        assertEquals(-1, g.day);
        assertEquals(-1, g.shiftLampLevel);
    }

    @Test
    public void maintenanceNotSupported() {
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        BikeFrames.ServiceConfig c = (BikeFrames.ServiceConfig) d.decode(hx(Z500_1D));
        assertFalse(c.valid); // the Z500's answer carries no 0x1D block
        for (int i = 0; i < 3; i++) assertFalse(c.supported(i));
        BikeFrames.ServiceIndicator s = (BikeFrames.ServiceIndicator) d.decode(hx(Z500_1E));
        for (BikeFrames.ServiceIndicator.Reminder r : s.reminders) {
            assertFalse(r.supported);
            assertEquals(-1, r.distanceKm);
            assertEquals(-1, r.notify);
        }
    }

    @Test
    public void acks() {
        String[][] samples = {
                {"200c871b00050a00ffffffffffffff", "1B", "0"},
                {"200c9208000a08017803e800c80064", "08", "0"},
                {"2002790300", "03", "0"},
                {"2002854100", "41", "0"},
                {"2002934500", "45", "0"},
                {"20029b4201", "42", "1"},
        };
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        for (String[] s : samples) {
            BikeFrames.Ack a = (BikeFrames.Ack) d.decode(hx(s[0]));
            assertEquals(s[0], Integer.parseInt(s[1], 16), a.cmd);
            assertEquals(s[0], Integer.parseInt(s[2]), a.result);
        }
    }

    @Test
    public void status() {
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        BikeFrames.Status s = (BikeFrames.Status) d.decode(hx("300c8bffff050900ffffffffffffff"));
        assertEquals(0x09, s.block);
        assertEquals(0, s.result);
        BikeFrames.Status t = (BikeFrames.Status) d.decode(
                hx("302084ffff0501486f6d654173736905027374616e74000000ffffffffffffffffffff"));
        assertEquals(-1, t.block);
        assertEquals("HomeAssistant", t.text);
    }

    @Test
    public void infoConfig() {
        BikeFrames.InfoConfig c = (BikeFrames.InfoConfig) new BikeFrames.Decoder().decode(hx(Z500_40));
        assertTrue(c.valid);
        int supported = 0;
        for (BikeFrames.Flag f : BikeFrames.InfoConfig.ALL) if (c.supported(f)) supported++;
        assertEquals(8, supported);
        assertTrue(c.supported(BikeFrames.InfoConfig.ECU_BATTERY));
        assertTrue(c.supported(BikeFrames.InfoConfig.WATER_TEMP));
        assertTrue(c.supported(BikeFrames.InfoConfig.INTAKE_TEMP));
        assertTrue(c.supported(BikeFrames.InfoConfig.ENGINE_SPEED));
        assertFalse(c.supported(BikeFrames.InfoConfig.OIL_TEMP));
        assertFalse(c.supported(BikeFrames.InfoConfig.ODOMETER));
    }

    @Test
    public void vehicleInfoZ500() {
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(hx(Z500_40));
        BikeFrames.VehicleInfo v = (BikeFrames.VehicleInfo) d.decode(hx(Z500_41));
        assertEquals(12.34375, v.ecuBatteryV, 1e-9);
        assertTrue(Double.isNaN(v.meterBatteryV));
        assertEquals(-1, v.odometerKm);
        assertEquals(-1, v.fuelBars);
    }

    @Test
    public void logExtZ500() {
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(hx(Z500_40));
        BikeFrames.LogExt e = (BikeFrames.LogExt) d.decode(hx(Z500_45));
        assertEquals(40, e.waterC, 0);
        assertEquals(36, e.intakeC, 0);
        assertTrue(Double.isNaN(e.oilC));
        assertTrue(Double.isNaN(e.totalDistanceKm));
        assertTrue(Double.isNaN(e.tyreFrontKpa));
        assertEquals(-1, e.tpmsWarnings);
    }

    @Test
    public void tuningNotSupported() {
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        BikeFrames.TuningConfig t = (BikeFrames.TuningConfig) d.decode(hx(Z500_47));
        assertEquals(2, t.flag(BikeFrames.TuningConfig.ECU));
        assertFalse(t.any());
        BikeFrames.VehicleSettings s = (BikeFrames.VehicleSettings) d.decode(hx(Z500_48));
        assertEquals(-1, s.ridingMode);
        assertEquals(-1, s.ktrc);
        assertEquals(-1, s.power);
    }

    @Test
    public void imuAbsent() {
        BikeFrames.ImuSusp i = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(hx(Z500_4B));
        assertTrue(Double.isNaN(i.frontStrokeMm));
        assertTrue(Double.isNaN(i.ayG));
    }

    @Test
    public void unknownFrameKeepsBytes() {
        BikeFrames.Frame f = new BikeFrames.Decoder().decode(hx("0a0000"));
        assertEquals(0x0A, f.id);
        assertEquals(3, f.raw.length);
        assertNull(new BikeFrames.Decoder().decode(new byte[0]));
    }

    // ------------------------------------------------------------------ built frames

    /** 0x40 config with chosen nibbles: byte 9 = wheel-speed mode (bits 2-3) + rpm mode (bits 0-1), etc. */
    static byte[] infoConfig(int b9, int b10, int b11) {
        byte[] v = blank(0x40, 35);
        put(v, 5, 0x05, 0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
        put(v, 9, b9, b10, b11);
        put(v, 14, 0x01); // flags "ready" (bit 0 set): no retry
        return v;
    }

    static BikeFrames.Telemetry telemetry(byte[] config, byte[] frame) {
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        if (config != null) d.decode(config);
        return (BikeFrames.Telemetry) d.decode(frame);
    }

    /** A 0x4A frame with the values used below: speed 100, rpm 4000, gear 3, throttle byte 255, lean 640. */
    static byte[] telemetryFrame() {
        byte[] v = blank(0x4A, 55);
        put(v, 5, 0x05, 0x15, 0x27, 0x10, 0x00, 0x64, 0x0F, 0xA0, 0x03, 0xFF);
        put(v, 15, 0x05, 0x16, 0x00, 0xC8, 0x02, 0x80, 0x00, 0xFF, 0xFF, 0x21);
        put(v, 35, 0x05, 0x18, 0x03, 0xFF, 0x80);
        return v;
    }

    @Test
    public void telemetryScalingFromNibbles() {
        // speed nibble 1 (raw 9 bit), rpm nibble 0 (15 bit), throttle nibble 1 (mechanical),
        // acceleration nibble 1 (no -2 offset), lean nibble 0 (11 bit)
        BikeFrames.Telemetry t = telemetry(infoConfig(0x04, 0x05, 0x00), telemetryFrame());
        assertEquals(100.0, t.speedKmh, 1e-9);
        assertEquals(100 * 0.01220703125, t.speedKmhAlt, 1e-9);
        assertEquals(4000, t.rpm);
        assertEquals(1000, t.rpmAlt);
        assertEquals(3, t.gear);
        assertEquals(100.0, t.throttlePct, 1e-6);
        assertEquals(200 * 0.01, t.accelG, 1e-9); // bytes 17-18 = 0x00C8, nibble 1: no -2 g offset
        assertEquals(25.0, t.leanDeg, 1e-9);
        assertEquals(2, t.tcsHigh);
        assertEquals(1, t.tcsLow);
        assertEquals(0x03, t.abs);
        assertEquals(0x80 * 0.1953125, t.brakeFrontBar, 1e-9);
    }

    @Test
    public void telemetryOtherScalings() {
        // speed nibble 0 (scaled), rpm nibble 1 (quarter), throttle nibble 0 (rider), accel nibble 0 (-2 g)
        BikeFrames.Telemetry t = telemetry(infoConfig(0x01, 0x00, 0x00), telemetryFrame());
        assertEquals(100 * 0.01220703125, t.speedKmh, 1e-9);
        assertEquals(100.0, t.speedKmhAlt, 1e-9);
        assertEquals(1000, t.rpm);
        assertEquals(4000, t.rpmAlt);
        assertEquals(255 * 0.78125, t.throttlePct, 1e-6);
        assertEquals(200 * 0.01 - 2.0, t.accelG, 1e-9);
    }

    @Test
    public void telemetryAbsentValues() {
        // nibbles 3 = absent for speed, rpm, throttle, acceleration and lean
        BikeFrames.Telemetry t = telemetry(infoConfig(0x0F, 0xFF, 0xFF), telemetryFrame());
        assertTrue(Double.isNaN(t.speedKmh));
        assertEquals(-1, t.rpm);
        assertEquals(-1, t.gear);
        assertTrue(Double.isNaN(t.throttlePct));
        assertTrue(Double.isNaN(t.accelG));
        assertTrue(Double.isNaN(t.leanDeg));
        assertTrue(Double.isNaN(t.wheelieDeg));
    }

    @Test
    public void telemetryAbsentBlocks() {
        BikeFrames.Telemetry t = telemetry(infoConfig(0x04, 0x05, 0x00), blank(0x4A, 55));
        assertTrue(Double.isNaN(t.speedKmh));
        assertEquals(-1, t.rpm);
        assertEquals(-1, t.gear);
        assertTrue(Double.isNaN(t.leanDeg));
        assertEquals(-1, t.abs);
    }

    @Test
    public void telemetryZ500SampleConfig() {
        // the Z500 sample config: speed nibble 1 (raw 9 bit), rpm nibble 0, throttle nibble 1, no lean
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(hx(Z500_40));
        byte[] v = telemetryFrame();
        BikeFrames.Telemetry t = (BikeFrames.Telemetry) d.decode(v);
        assertEquals(100.0, t.speedKmh, 1e-9);
        assertEquals(4000, t.rpm);
        assertTrue(Double.isNaN(t.leanDeg));
        assertTrue(Double.isNaN(t.accelG));
    }

    @Test
    public void vehicleInfoArithmetic() {
        byte[] v = blank(0x41, 85);
        put(v, 5, 0x05, 0x14, 0x00, 0x00, 0x30, 0x39, 0xFF, 0xFF, 0xFF, 0xA0);
        put(v, 25, 0x05, 0x16, 0x01, 0x86, 0xA0, 0x05, 0xFF, 0x00, 0xC8, 0x7E);
        put(v, 35, 0x05, 0x17, 0x00, 0x03, 0xE8, 0x00, 0x00, 0x7B, 0x48, 0x50);
        put(v, 45, 0x05, 0x18, 0x11, 0x2C, 0x00, 0x2D, 0x00, 0x96);
        BikeFrames.VehicleInfo i = (BikeFrames.VehicleInfo) new BikeFrames.Decoder().decode(v);
        assertEquals(123.45, i.totalFuelL, 1e-9);
        assertEquals(0xA0 * 20.0 / 256.0, i.ecuBatteryV, 1e-9);
        assertEquals(100000, i.odometerKm);
        assertEquals(5, i.fuelBars);
        assertEquals(20.0, i.avgMileage, 1e-9);
        assertEquals(12.6, i.meterBatteryV, 1e-9);
        assertEquals(100.0, i.tripAKm, 1e-9);
        assertEquals(12.3, i.tripBKm, 1e-9);
        assertEquals(72, i.avgSpeedKmh);
        assertEquals(20.0, i.outsideC, 0);
        assertEquals(1, i.rangeSymbol);
        assertEquals(300, i.rangeKm);
        assertEquals(4.5, i.fuelConsumption, 1e-9);
        assertEquals(150, i.totalTime);
    }

    @Test
    public void vehicleSettingsArithmetic() {
        byte[] v = blank(0x48, 45);
        put(v, 5, 0x05, 0x09, 0x01);
        put(v, 15, 0x05, 0x09, 0x01, 0x1A, 0x61);
        BikeFrames.VehicleSettings s = (BikeFrames.VehicleSettings) new BikeFrames.Decoder().decode(v);
        assertEquals(1, s.meterCapability);
        assertEquals(1, s.ecuCapability);
        assertEquals(3, s.ktrc);
        assertEquals(2, s.ridingMode);
        assertEquals(3, s.kqs);
        assertEquals(0, s.kebc);
        assertEquals(1, s.power);
        assertEquals(-1, s.kecsMode);
    }

    @Test
    public void generalSettingsClock() {
        byte[] caps = blank(0x1A, 15);
        put(caps, 5, 0x05, 0x0A, 0x00, 61, 1, 0x00, 0x00, 0x00, 0x00, 0x01);
        byte[] v = blank(0x1B, 15);
        put(v, 5, 0x05, 0x0A, 0x00, 0x40 | 25, 13, 10, 26, 18, 30, 5);
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        BikeFrames.GeneralCaps c = (BikeFrames.GeneralCaps) d.decode(caps);
        assertEquals(0, c.clockGate);
        assertEquals(0, c.shiftLampMode);
        assertTrue(c.shiftLampSettable);
        assertTrue(c.clockSettable());
        BikeFrames.GeneralSettings g = (BikeFrames.GeneralSettings) d.decode(v);
        assertEquals(0, g.result);
        assertEquals(1, g.shiftLampStatus);
        assertEquals(25, g.shiftLampLevel);
        assertEquals(9000, g.shiftLampRpm());
        assertEquals(13, g.day);
        assertEquals(10, g.month);
        assertEquals(2026, g.year);
        assertEquals(18, g.hour);
        assertEquals(30, g.minute);
        assertEquals(5, g.second);
    }

    @Test
    public void clockGateAndLampMode() {
        byte[] caps = blank(0x1A, 15);
        put(caps, 5, 0x05, 0x0A, 0x40, 61, 1, 0x00, 0x00, 0x00, 0x00, 0x01); // gate 1, lamp mode 0
        BikeFrames.GeneralCaps c = (BikeFrames.GeneralCaps) new BikeFrames.Decoder().decode(caps);
        assertEquals(1, c.clockGate);
        assertFalse(c.clockSettable());
        assertTrue(c.shiftLampSettable);
        put(caps, 7, 0x01); // lamp mode 1: not settable
        c = (BikeFrames.GeneralCaps) new BikeFrames.Decoder().decode(caps);
        assertFalse(c.shiftLampSettable);
    }

    @Test
    public void maintenanceReminder() {
        byte[] v = blank(0x1E, 45);
        put(v, 5, 0x05, 0x0C, 0x01, 0x14, 0x0F, 0x1B, 0xFF, 0x00, 0x75, 0x30);
        BikeFrames.ServiceIndicator s = (BikeFrames.ServiceIndicator) new BikeFrames.Decoder().decode(v);
        BikeFrames.ServiceIndicator.Reminder k = s.reminders[BikeFrames.ServiceIndicator.KAWASAKI];
        assertEquals(1, k.notify);
        assertEquals(4, k.month);
        assertEquals(15, k.day);
        assertEquals(2027, k.year);
        assertEquals(30000, k.distanceKm);
        assertTrue(k.hasDate());
        assertEquals(-1, s.reminders[BikeFrames.ServiceIndicator.OIL].distanceKm);
    }

    @Test
    public void logExtDistanceAndTyres() {
        byte[] v = blank(0x45, 55);
        put(v, 5, 0x05, 0x15, 0x00, 0x01, 0x86, 0xA0, 0x00, 0x32);
        put(v, 45, 0xFE, 0xFE, 0x64, 0x6E, 0x01);
        BikeFrames.LogExt e = (BikeFrames.LogExt) new BikeFrames.Decoder().decode(v);
        assertEquals(10000.0, e.totalDistanceKm, 1e-9);
        assertEquals(1.0, e.fuelRate, 1e-9);
        assertEquals(100 * 1.373 + 100, e.tyreFrontKpa, 1e-9);
        assertEquals(110 * 1.373 + 100, e.tyreRearKpa, 1e-9);
        assertEquals(1, e.tpmsWarnings);
    }
}
