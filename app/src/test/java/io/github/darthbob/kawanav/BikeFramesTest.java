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
    public void ackConfirmsTheFrameTypeItNames() {
        // Z900: the status icons' ACK echoes the icon block, the arrow's the first navigation block
        assertTrue(BikeFrames.Ack.confirms(hx("200c6c130005002010ffffffffffff"), 0x13));
        assertTrue(BikeFrames.Ack.confirms(hx("20086d1400057000ffffff"), 0x14));
        assertFalse(BikeFrames.Ack.confirms(hx("20086d1400057000ffffff"), 0x13));
        // a rejection still confirms the frame arrived
        assertTrue(BikeFrames.Ack.confirms(hx("20029b4201"), 0x42));
        // data frames name the phone's last frame in byte 3 too, but are no ACK
        assertFalse(BikeFrames.Ack.confirms(hx("41520314000514"), 0x14));
        assertFalse(BikeFrames.Ack.confirms(hx("200213"), 0x13));
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
        assertEquals(9, supported);   // a change detector: the real checks are the ones below
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

    /**
     * 00 is the bottom of a temperature's scale (-40 C coolant and intake, -60 C oil): a sensor that reads
     * nothing, not a reading. The Z500 sends it as its oil temperature.
     */
    @Test
    public void logExtTemperatureZeroIsNone() {
        byte[] cfg = hx(Z500_40);
        put(cfg, 8, 0x57);                                 // a config that says all three are there
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(cfg);
        byte[] v = hx(Z500_45);
        BikeFrames.LogExt e = (BikeFrames.LogExt) d.decode(v);
        assertTrue(Double.isNaN(e.oilC));                  // byte 19 is 00
        assertEquals(40, e.waterC, 0);
        assertEquals(36, e.intakeC, 0);
        put(v, 19, 0x8C);
        e = (BikeFrames.LogExt) d.decode(v);
        assertEquals(80, e.oilC, 0);
        put(v, 19, 0x01);
        e = (BikeFrames.LogExt) d.decode(v);
        assertEquals(-59, e.oilC, 0);                      // one step above the bottom is a reading
        put(v, 17, 0x00);
        put(v, 20, 0x00);
        e = (BikeFrames.LogExt) d.decode(v);
        assertTrue(Double.isNaN(e.waterC));
        assertTrue(Double.isNaN(e.intakeC));
    }

    @Test
    public void temperaturesWaitForTheConfig() {
        // the meter streams 0x45 before the 0x40 is in; the Z500 sends 00 as the oil temperature it has not
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        BikeFrames.LogExt early = (BikeFrames.LogExt) d.decode(hx(Z500_45));
        assertTrue(Double.isNaN(early.oilC));      // not -60 °C
        assertTrue(Double.isNaN(early.waterC));
        assertTrue(Double.isNaN(early.intakeC));
        d.decode(hx(Z500_40));
        BikeFrames.LogExt later = (BikeFrames.LogExt) d.decode(hx(Z500_45));
        assertEquals(40, later.waterC, 0);
        assertEquals(36, later.intakeC, 0);
        assertTrue(Double.isNaN(later.oilC));      // the config says the bike has none
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
    public void logExtLongForm() {
        // the long 0x45 repeats the dash's own values, with their valid bits in byte 57
        byte[] v = blank(0x45, 65);
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(infoConfigNoNibbleMasks());
        put(v, 5, 0x05, 0x15);
        put(v, 45, 0x05, 0x19);
        put(v, 57, 0x00, 0x23, 0x45);   // flag clear, odometer 0x2345 = 9029 … set below instead
        put(v, 57, 0x00, 0x12, 0x34);   // odometer 0x1234 = 4660
        put(v, 60, 0x06);
        put(v, 62, 0x02, 0xAB);         // average mileage 0x2AB = 683 -> 68.3
        put(v, 64, 0x7B);               // meter battery 123 -> 12.3
        BikeFrames.LogExt e = (BikeFrames.LogExt) d.decode(v);
        assertEquals(4660, e.odometerKm);
        assertEquals(6, e.fuelBars);
        assertEquals(68.3, e.avgMileage, 1e-9);
        assertEquals(12.3, e.meterBatteryV, 1e-9);
        assertEquals(0, e.odometerValid);
        assertEquals(0, e.meterBatteryValid);
        assertEquals(0, e.avgMileageValid);
    }

    @Test
    public void logExtLongFormFlagsDropTheValues() {
        // byte 57 bits 6/5/4 set: the dash says those three are not current any more
        byte[] v = blank(0x45, 65);
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(infoConfigNoNibbleMasks());
        put(v, 5, 0x05, 0x15);
        put(v, 45, 0x05, 0x19);
        put(v, 57, 0x70, 0x12, 0x34);   // bits 6/5/4: odometer, meter battery, mileage not current
        put(v, 60, 0x06);
        put(v, 62, 0x02, 0xAB);
        put(v, 64, 0x7B);
        BikeFrames.LogExt e = (BikeFrames.LogExt) d.decode(v);
        assertEquals(-1, e.odometerKm);
        assertTrue(Double.isNaN(e.avgMileage));
        assertTrue(Double.isNaN(e.meterBatteryV));
        assertEquals(1, e.odometerValid);
        assertEquals(1, e.meterBatteryValid);
        assertEquals(1, e.avgMileageValid);
        assertEquals(6, e.fuelBars);    // the gauge has no bit of its own
    }

    @Test
    public void logExtShortFrameHasNoLongForm() {
        // the 55-byte form the Z500 sends (the Z900 sends the 65-byte one): every long-form value absent, not zero
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(hx(Z500_40));
        BikeFrames.LogExt e = (BikeFrames.LogExt) d.decode(hx(Z500_45));
        assertEquals(-1, e.odometerKm);
        assertEquals(-1, e.fuelBars);
        assertTrue(Double.isNaN(e.avgMileage));
        assertTrue(Double.isNaN(e.meterBatteryV));
        assertEquals(-1, e.odometerValid);
    }

    @Test
    public void logExtInstantFuelValidBit() {
        // the 0x40 nibble at byte 33 bit 4 (value 0) says the instant fuel carries a "not current" bit,
        // which is byte 27 bit 4 of the 0x45 frame
        byte[] v = blank(0x45, 55);
        put(v, 5, 0x05, 0x15);
        put(v, 25, 0x05, 0x18);
        put(v, 27, 0x00, 0x30);
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(infoConfigNoNibbleMasks());
        BikeFrames.LogExt current = (BikeFrames.LogExt) d.decode(v);
        assertEquals(4.8, current.instantFuel, 1e-9);
        assertEquals(0, current.instantFuelValid);
        put(v, 27, 0x10, 0x30);        // bit 4 set: the dash says the value is not current
        BikeFrames.LogExt stale = (BikeFrames.LogExt) d.decode(v);
        assertTrue(Double.isNaN(stale.instantFuel));
        assertEquals(1, stale.instantFuelValid);
        // a bike that sends no such bit (nibble 1): the value is used as it comes and the flag is unknown
        byte[] noBit = infoConfigNoNibbleMasks();
        put(noBit, 33, 0x10);
        BikeFrames.Decoder d2 = new BikeFrames.Decoder();
        d2.decode(noBit);
        put(v, 27, 0x10, 0x30);
        BikeFrames.LogExt plain = (BikeFrames.LogExt) d2.decode(v);
        assertEquals(4.8, plain.instantFuel, 1e-9);
        assertEquals(-1, plain.instantFuelValid);
    }

    @Test
    public void instantFuelPlaceholderIsDropped() {
        // 0x3FF is the dash's "no value" (102.3), dropped like Rideology does; 0x3FE is a reading
        byte[] v = blank(0x45, 55);
        put(v, 5, 0x05, 0x15);
        put(v, 25, 0x05, 0x18);
        put(v, 27, 0x03, 0xFF);
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(infoConfigNoNibbleMasks());
        assertTrue(Double.isNaN(((BikeFrames.LogExt) d.decode(v)).instantFuel));
        put(v, 27, 0x03, 0xFE);
        assertEquals(102.2, ((BikeFrames.LogExt) d.decode(v)).instantFuel, 1e-9);
    }

    @Test
    public void outsideTemperatureValidBitIsRead() {
        // the last flag of the set (0x40 byte 33 bit 0); nothing gates on it, but the debug card lists it
        BikeFrames.InfoConfig cfg = (BikeFrames.InfoConfig) new BikeFrames.Decoder()
                .decode(infoConfigNoNibbleMasks());
        assertEquals(0, cfg.flag(BikeFrames.InfoConfig.OUTSIDE_TEMP_VALID));
        byte[] other = infoConfigNoNibbleMasks();
        put(other, 33, 0x01);
        BikeFrames.InfoConfig with = (BikeFrames.InfoConfig) new BikeFrames.Decoder().decode(other);
        assertEquals(1, with.flag(BikeFrames.InfoConfig.OUTSIDE_TEMP_VALID));
    }

    @Test
    public void capabilityAnswerDecidesWhetherFramesGoOut() {
        // a ready 0x1A that says "no" is the only thing that stops them
        byte[] no = blank(0x1A, 15);
        put(no, 5, 0x05, 0x0B);
        put(no, 11, 0x0F);          // status icons: the low nibble at 15
        put(no, 12, 0xFF, 0xFF);    // navi nibble 15 and both icon sub-fields of the byte at 3
        put(no, 13, 0xFF);
        put(no, 14, 0x01);          // ready
        BikeFrames.GeneralCaps caps = (BikeFrames.GeneralCaps) new BikeFrames.Decoder().decode(no);
        assertTrue(caps.valid);
        assertFalse(caps.turnByTurn);
        assertFalse(caps.statusIcons);
        assertFalse(BikeFrames.GeneralCaps.allowed(caps, caps.turnByTurn, false));
        assertFalse(BikeFrames.GeneralCaps.allowed(caps, caps.statusIcons, false));
        // the debug override wins
        assertTrue(BikeFrames.GeneralCaps.allowed(caps, caps.turnByTurn, true));

        // nothing known: keep sending (a frame that has not arrived must not silence anything)
        assertTrue(BikeFrames.GeneralCaps.allowed(null, false, false));
        byte[] pending = blank(0x1A, 15);
        put(pending, 5, 0x05, 0x0B);
        put(pending, 11, 0x0F);
        put(pending, 14, 0x00);      // header present, bit 0 clear: still being prepared
        BikeFrames.GeneralCaps notReady = (BikeFrames.GeneralCaps) new BikeFrames.Decoder().decode(pending);
        assertFalse(notReady.valid);
        assertTrue(BikeFrames.GeneralCaps.allowed(notReady, false, false));

        // a dash that has them: everything goes out, and the two booleans say so
        byte[] yes = blank(0x1A, 15);
        put(yes, 5, 0x05, 0x0B);
        put(yes, 11, 0x00);
        put(yes, 12, 0x00, 0x00);
        put(yes, 13, 0x00);
        put(yes, 14, 0x01);
        BikeFrames.GeneralCaps full = (BikeFrames.GeneralCaps) new BikeFrames.Decoder().decode(yes);
        assertTrue(full.valid);
        assertTrue(full.turnByTurn);
        assertTrue(full.statusIcons);
        assertTrue(BikeFrames.GeneralCaps.allowed(full, full.turnByTurn, false));
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

    /** 0x40 with every value nibble at 0 (present) and the valid-bit flags present. */
    static byte[] infoConfigNoNibbleMasks() {
        byte[] v = blank(0x40, 35);
        put(v, 5, 0x05, 0x11);
        put(v, 27, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
        put(v, 14, 0x01);
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
        assertEquals(100.0, t.throttlePct, 1e-6); // mode 1: FF is a full throttle
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
        // mode 0 counts to 128: an FF byte is the meter's "no value", not 199 %
        assertTrue(Double.isNaN(t.throttlePct));
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
    public void telemetryMoreValues() {
        byte[] v = blank(0x4A, 90);
        System.arraycopy(telemetryFrame(), 0, v, 0, 55);
        // torque block: 0x01F4 (500 × 0.2 = 100), 0x0064 (20), 0x4032 (the 0x40 bits are masked: 0x32 × 0.2 = 10)
        put(v, 25, 0x05, 0x17, 0x01, 0xF4, 0x00, 0x64, 0x40, 0x32);
        put(v, 35, 0x05, 0x18, 0x03, 0x22, 0x80, 0x11, 0x33);
        // every field gets a value of its own, so a swapped mask or bit cannot pass unnoticed
        put(v, 45, 0x05, 0x19, 0xA1, 0x01);
        put(v, 75, 0x05, 0x1C, 0x07, 0x06);
        put(v, 85, 0x05, 0x1D, 0x02, 0x78);
        BikeFrames.Telemetry t = telemetry(infoConfig(0x04, 0x05, 0x00), v);
        assertEquals(100, t.riderTorque);
        assertEquals(20, t.engineTorqueRequest);
        assertEquals(10, t.engineTorque);
        assertEquals(0x22, t.brakeFrontMaster);
        assertEquals(0x11, t.brakeRearMaster);
        assertEquals(0x33, t.brakeRearCaliper);
        assertEquals(5, t.absTarget);   // 0xA1 = 101 0 0 001: target 5, indicator 1
        assertEquals(1, t.absIndicator);
        assertEquals(1, t.absSwitching);
        assertEquals(7, t.damping);
        assertEquals(1, t.suspCalibration); // 0x06 = 110: calibration 1, status 1, ECU 0
        assertEquals(1, t.suspStatus);
        assertEquals(0, t.suspEcu);
        assertEquals(1, t.cruiseOn);        // 0x02: working, but mode 0
        assertEquals(0, t.cruiseMode);
        assertEquals(120, t.wheelSpeedSetting); // byte 88, Rideology's wheel_speed_setting
    }

    @Test
    public void telemetryTorqueRawNibbleAndAbsentBlocks() {
        byte[] cfg = infoConfig(0x04, 0x05, 0x00);
        put(cfg, 12, 0x15); // rider torque 1 (raw), engine request 1, engine torque 1
        byte[] v = blank(0x4A, 90);
        System.arraycopy(telemetryFrame(), 0, v, 0, 55);
        put(v, 25, 0x05, 0x17, 0xF1, 0x23, 0x00, 0x10, 0x00, 0x00);
        BikeFrames.Telemetry t = telemetry(cfg, v);
        assertEquals(0x123, t.riderTorque); // 12 bits: the high nibble is not part of it
        assertEquals(0x10, t.engineTorqueRequest);
        assertEquals(0, t.engineTorque);
        // blocks 45, 75 and 85 are FF: their values are absent, not 7 or 255
        assertEquals(-1, t.absTarget);
        assertEquals(-1, t.damping);
        assertEquals(-1, t.cruiseOn);
        assertEquals(-1, t.wheelSpeedSetting);
        // a short frame (the Z500 sends 55 bytes) has none of them
        BikeFrames.Telemetry s = telemetry(cfg, telemetryFrame());
        assertEquals(-1, s.riderTorque);
        assertEquals(-1, s.cruiseOn);
    }

    @Test
    public void allOnesMeansNoValue() {
        // a meter that has the blocks but no values fills them with FF: none of it may read as a number
        byte[] v = blank(0x4A, 93);
        put(v, 5, 0x05, 0x15);
        put(v, 15, 0x05, 0x16);
        put(v, 25, 0x05, 0x17);
        put(v, 35, 0x05, 0x18);
        put(v, 45, 0x05, 0x19);
        put(v, 75, 0x05, 0x1C);
        put(v, 85, 0x05, 0x1D);
        BikeFrames.Telemetry t = telemetry(infoConfig(0x00, 0x00, 0x00), v);
        assertEquals(-1, t.gear);            // not 15
        assertTrue(Double.isNaN(t.throttlePct)); // not 199 %
        assertEquals(-1, t.wheelie);
        assertEquals(-1, t.tcsHigh);
        assertEquals(-1, t.abs);             // not "fault"
        assertEquals(-1, t.brakeFrontMaster);
        assertEquals(-1, t.riderTorque);
        assertEquals(-1, t.absTarget);
        assertEquals(-1, t.damping);
        assertEquals(-1, t.cruiseOn);
        assertEquals(-1, t.wheelSpeedSetting);
        byte[] m = blank(0x4B, 53);
        put(m, 5, 0x05, 0x01);
        put(m, 15, 0x05, 0x02);
        put(m, 25, 0x05, 0x03);
        put(m, 35, 0x05, 0x04);
        put(m, 45, 0x05, 0x19);
        BikeFrames.ImuSusp i = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(m);
        assertEquals(-1, i.tcsLevelHigh);
        assertEquals(-1, i.wheelieFlag);
        assertTrue(Double.isNaN(i.frontWheelKmh));
    }

    @Test
    public void imuMoreValues() {
        byte[] v = blank(0x4B, 53);
        put(v, 15, 0x05, 0x01, 0x00, 0x80);             // rate 1: 0x8000 → 0
        put(v, 21, 0x00, 0x40);                        // ay: 0x4000 (hi in byte 22) → -2.09 g
        put(v, 25, 0x05, 0x02, 0x00, 0xC0);            // rate 2: 0xC000 → +81.92
        put(v, 45, 0x05, 0x03, 0x0A, 0x00, 0x14, 0x00, 0xA6, 0x01); // 31.3 km/h, 62.5 km/h, flags below
        BikeFrames.ImuSusp i = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(v);
        assertEquals(0.0, i.rate1, 1e-9);
        assertEquals(81.92, i.rate2, 1e-9);
        assertTrue(Double.isNaN(i.rate3));
        assertEquals(-2.0884, i.ayG, 1e-3);            // 0x4000 × 4.1768 / 32768 − 4.1768
        assertEquals(0x0A00 * 400.0 / 32768.0, i.frontWheelKmh, 1e-9);
        assertEquals(0x1400 * 400.0 / 32768.0, i.rearWheelKmh, 1e-9);
        assertEquals(5, i.tcsLevelHigh);               // 0xA6 = 101 001 1 0
        assertEquals(1, i.tcsLevelLow);
        assertEquals(1, i.kqsDown);
        assertEquals(0, i.kqsUp);
        assertEquals(1, i.wheelieFlag);
    }

    @Test
    public void imuMoreValuesAbsent() {
        BikeFrames.ImuSusp i = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(hx(Z500_4B));
        assertTrue(Double.isNaN(i.rate1));
        assertTrue(Double.isNaN(i.frontWheelKmh));
        assertEquals(-1, i.tcsLevelHigh);
        assertEquals(-1, i.wheelieFlag);
    }

    @Test
    public void theImuAxesAreTurnedToTheBikesDirections() {
        // the measured mounting: upright and still, the raw pair reads -0.70 and -0.71 g, and turning it
        // by 45 degrees gives no forward acceleration and one g down (PROTOCOL.md, a recorded ride)
        assertEquals(0.0, BikeFrames.ImuSusp.longitudinal(-0.70, -0.71), 0.02);
        assertEquals(1.0, BikeFrames.ImuSusp.vertical(-0.70, -0.71), 0.02);
        // the pairs measured while accelerating (the bike said +0.52 g) and braking (it said -0.60 g):
        // the turned value keeps the sign and is close to the bike's own figure
        assertEquals(0.41, BikeFrames.ImuSusp.longitudinal(-0.51, -1.09), 0.02);
        assertEquals(1.13, BikeFrames.ImuSusp.vertical(-0.51, -1.09), 0.02);
        assertEquals(-0.69, BikeFrames.ImuSusp.longitudinal(-1.21, -0.24), 0.02);
        // the third axis is used as it comes
        assertEquals(-0.31, BikeFrames.ImuSusp.lateral(-0.31), 1e-9);
        // and from a frame: no IMU sent, no derived value either
        BikeFrames.ImuSusp none = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(hx(Z500_4B));
        assertTrue(Double.isNaN(none.longitudinalG));
        assertTrue(Double.isNaN(none.verticalG));
    }

    @Test
    public void theLastBlockOf0x4BNeedsBothHeaderBytesToBeAbsent() {
        // 45 = 05, 46 = FF: Rideology still reads the block (only both FF means "not there")
        byte[] v = blank(0x4B, 53);
        put(v, 45, 0x05, 0xFF, 0x0A, 0x00, 0x14, 0x00, 0xA6, 0x01);
        BikeFrames.ImuSusp one = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(v);
        assertEquals(31.25, one.frontWheelKmh, 1e-9);
        assertEquals(5, one.tcsLevelHigh);
        byte[] both = blank(0x4B, 53);
        put(both, 45, 0xFF, 0xFF);
        BikeFrames.ImuSusp none = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(both);
        assertTrue(Double.isNaN(none.frontWheelKmh));
        assertEquals(-1, none.tcsLevelHigh);
    }

    /** A real 0x4B from a Z900 (2026, standing, engine off): the IMU's three CAN messages and block 45. */
    static final String Z900_4B = "4b341bffffffffffffffffffffffff017408800000336b05a40178fc7f0530126a052e017c12800100277b05b503e0000000000000ffff";

    @Test
    public void crc8IsSaeJ1850() {
        assertEquals(0x4B, BikeFrames.ImuSusp.crc8("123456789".getBytes(), 0, 9));   // the standard check value
        // two IMU blocks from a capture: the last byte is the CRC of the seven before it, or is not
        byte[] good = hx("8c7e0000eb69088e"), torn = hx("44800000aa6c01a0");
        assertEquals(0x8E, BikeFrames.ImuSusp.crc8(good, 0, 7));
        assertTrue(BikeFrames.ImuSusp.crc8(torn, 0, 7) != 0xA0);
    }

    @Test
    public void imuBlocksWhoseCrcChecksAreRead() {
        BikeFrames.ImuSusp i = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(hx(Z900_4B));
        assertEquals(5, i.imuCounter);
        assertEquals(0, i.imuDropped);
        assertEquals(0.04, i.rate1, 1e-9);                                   // 0x8008
        assertEquals(0x6B33 * 4.1768 / 32768.0 - 4.1768, i.ayG, 1e-9);
        assertFalse(Double.isNaN(i.axG) || Double.isNaN(i.azG) || Double.isNaN(i.rate2) || Double.isNaN(i.rate3));
    }

    @Test
    public void anImuBlockWhoseCrcFailsIsLeftOut() {
        byte[] v = hx(Z900_4B);
        v[31] ^= 0x01;                                    // block 25's acceleration, one bit off
        BikeFrames.ImuSusp i = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(v);
        assertTrue(Double.isNaN(i.axG));
        assertTrue(Double.isNaN(i.rate2));
        assertTrue(Double.isNaN(i.longitudinalG));       // turned from the pair, so it goes too
        assertFalse(Double.isNaN(i.ayG) || Double.isNaN(i.azG) || Double.isNaN(i.rate1) || Double.isNaN(i.rate3));
        assertEquals(1, i.imuDropped);
        assertEquals(5, i.imuCounter);
        assertEquals(0.0, i.frontWheelKmh, 1e-9);        // block 45 has no CRC: read as before (standing)
        // block 15 failing takes the counter with it
        byte[] w = hx(Z900_4B);
        w[17] ^= 0x01;
        BikeFrames.ImuSusp j = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(w);
        assertTrue(Double.isNaN(j.ayG));
        assertEquals(-1, j.imuCounter);
    }

    @Test
    public void theImuRatesAreTurnedToTheBikesAxes() {
        // the IMU's x and y rates sit at 45° like its accelerations; its z rate is the bike's pitch
        double c = Math.sqrt(0.5);
        assertEquals(2 * c, BikeFrames.ImuSusp.yaw(-1, 1), 1e-9);       // turning right
        assertEquals(0, BikeFrames.ImuSusp.roll(-1, 1), 1e-9);
        assertEquals(0, BikeFrames.ImuSusp.yaw(1, 1), 1e-9);
        assertEquals(-2 * c, BikeFrames.ImuSusp.roll(1, 1), 1e-9);      // rolling left
        assertEquals(0.5, BikeFrames.ImuSusp.pitch(0.5), 1e-9);
        assertTrue(Double.isNaN(BikeFrames.ImuSusp.yaw(Double.NaN, 1)));
        // from the Z900's frame: standing still, a few hundredths of a degree a second
        BikeFrames.ImuSusp i = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(hx(Z900_4B));
        assertEquals(-0.02, i.rate2, 1e-9);                                 // 0x7FFC
        assertEquals(0.09, i.rate3, 1e-9);                                  // 0x8012
        assertEquals(0.11 * c, i.yawRate, 1e-9);
        assertEquals(-0.07 * c, i.rollRate, 1e-9);
        assertEquals(i.rate1, i.pitchRate, 1e-9);
        // a block left out takes the turned rates that need it along
        byte[] v = hx(Z900_4B);
        v[31] ^= 0x01;
        BikeFrames.ImuSusp j = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(v);
        assertTrue(Double.isNaN(j.yawRate) && Double.isNaN(j.rollRate));
        assertEquals(0.04, j.pitchRate, 1e-9);
    }

    @Test
    public void imuBlocksThatMostlyFailTheCrcAreAllKept() {
        // two of three failing: this bike does not send the CRC (or not this one), so nothing is left out
        byte[] v = hx(Z900_4B);
        v[31] ^= 0x01;
        v[41] ^= 0x01;
        BikeFrames.ImuSusp i = (BikeFrames.ImuSusp) new BikeFrames.Decoder().decode(v);
        assertFalse(Double.isNaN(i.axG) || Double.isNaN(i.azG) || Double.isNaN(i.ayG));
        assertEquals(0, i.imuDropped);
        assertEquals(-1, i.imuCounter);
    }

    @Test
    public void bikeDataCountsImuBlocksLeftOutAndRepeats() {
        BikeData b = new BikeData();
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        byte[] next = hx(Z900_4B);
        next[23] = 0x0F;                                  // counter 15, CRC no longer right for block 15
        next[24] = (byte) BikeFrames.ImuSusp.crc8(next, 17, 7);
        byte[] torn = hx(Z900_4B);
        torn[31] ^= 0x01;
        b.apply(d.decode(hx(Z900_4B)), 1000);            // counter 5
        b.apply(d.decode(hx(Z900_4B)), 1100);            // 5 again: the same message
        b.apply(d.decode(next), 1200);                   // 15: moved on
        b.apply(d.decode(torn), 1300);                   // 5, block 25 left out
        assertEquals(1, b.imuRepeats);
        assertEquals(1, b.imuDropped);
        b.connected();
        assertEquals(0, b.imuRepeats);
        assertEquals(0, b.imuDropped);
    }

    @Test
    public void wheelieAngleIsReadFromBothBytes() {
        // byte 22 is the high byte: 0x01CD = 461 × 0.09765625 (nibble 0)
        byte[] v = blank(0x4A, 93);
        put(v, 5, 0x05, 0x15);
        put(v, 15, 0x05, 0x16, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0xCD, 0x00);
        BikeFrames.Telemetry t = telemetry(infoConfig(0x00, 0x00, 0x00), v);
        assertEquals(0x1CD * 0.09765625, t.wheelieDeg, 1e-9); // 45.02°
    }

    @Test
    public void aFrameCutInsideABlockHasNoValues() {
        // 33 bytes: the block-25 header is there, the bytes the torque needs are not
        byte[] v = blank(0x4A, 33);
        put(v, 5, 0x05, 0x15);
        put(v, 25, 0x05, 0x17);
        BikeFrames.Telemetry t = telemetry(infoConfig(0x00, 0x00, 0x00), v);
        assertEquals(-1, t.riderTorque);   // not 0.2 × a byte read as -1
        assertEquals(-1, t.riderTorque == -1 ? -1 : 0);
        assertEquals(-1, t.abs);
        assertEquals(-1, t.damping);
    }

    // ------------------------------------------------------------------ a frame cut short

    /**
     * Every real sample above satisfies {@code length == byte 1 + 3}, the identity the drop is based on:
     * a frame that is whole is never the one that gets thrown away.
     */
    @Test
    public void everyRealSampleFrameCarriesItsOwnLength() {
        for (String s : new String[]{Z500_03, Z500_1A, Z500_1B, Z500_1D, Z500_1E, Z500_40, Z500_41,
                Z500_45, Z500_47, Z500_48, Z500_4B}) {
            byte[] v = hx(s);
            assertEquals(s, v.length, (v[1] & 0xFF) + 3);
        }
    }

    /**
     * The MTU stays 23 when the meter does not answer the request, so the first notification of a 55-byte
     * frame arrives with 20 bytes and a header that still says 55. Decoding it invented a lean of -100°,
     * a gear of 15 and a negative speed — values the bike never sent.
     */
    @Test
    public void aFrameTruncatedAtTheAttMtuIsDropped() {
        byte[] whole = telemetryFrame();
        byte[] cut = java.util.Arrays.copyOf(whole, 20);
        assertEquals("the header still announces the whole frame", 55, (cut[1] & 0xFF) + 3);
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        assertNull("nothing of it may reach the dash", d.decode(cut));
        // the same frame in full is decoded: the drop is not a blanket refusal
        assertTrue(d.decode(whole) instanceof BikeFrames.Telemetry);
    }

    @Test
    public void aTruncatedImuFrameIsDropped() {
        byte[] cut = java.util.Arrays.copyOf(hx(Z500_4B), 20);
        assertNull(new BikeFrames.Decoder().decode(cut));
    }

    /**
     * A frame the meter itself announces as short: block 1 is whole, block 2 is not. The values that did
     * arrive are kept — refusing them too would lose a whole frame — and the missing ones stay absent
     * instead of reading past the end of the array (where {@code u8} answers -1).
     */
    @Test
    public void aFrameThatStopsInsideABlockKeepsTheValuesItHas() {
        byte[] v = blank(0x4A, 20);
        put(v, 5, 0x05, 0x15, 0x27, 0x10, 0x00, 0x64, 0x0F, 0xA0, 0x03, 0xFF);
        put(v, 15, 0x05, 0x16);   // block 2 announces itself, its bytes were never sent
        BikeFrames.Telemetry t = telemetry(infoConfig(0x04, 0x05, 0x00), v);
        assertEquals(100.0, t.speedKmh, 1e-9);
        assertEquals(4000, t.rpm);
        assertEquals(3, t.gear);
        assertTrue("not -100.2°", Double.isNaN(t.leanDeg));
        assertTrue("not 0.01 g from a byte read as -1", Double.isNaN(t.accelG));
        assertTrue(Double.isNaN(t.wheelieDeg));
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

    /** A 0x41 frame like vehicleInfoArithmetic's, every value the meter would mark as "not current". */
    static byte[] vehicleInfoPlaceholders() {
        byte[] v = blank(0x41, 85);
        put(v, 5, 0x05, 0x14, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xA0);
        put(v, 25, 0x05, 0x16, 0x0F, 0xFF, 0xFF, 0x0F, 0xFF, 0x03, 0xFF, 0xFF);
        put(v, 35, 0x05, 0x17, 0x01, 0xFF, 0xFF, 0x01, 0xFF, 0xFF, 0xFF, 0x50);
        put(v, 45, 0x05, 0x18, 0x03, 0xFF, 0x03, 0xFF, 0x1F, 0xFF, 0x7F, 0x7F);
        return v;
    }

    /** The meter's "nothing here" values are left out, not shown as 1023 km of range or 25.5 V. */
    @Test
    public void vehicleInfoPlaceholdersAreLeftOut() {
        BikeFrames.VehicleInfo i = (BikeFrames.VehicleInfo) new BikeFrames.Decoder().decode(vehicleInfoPlaceholders());
        assertTrue(Double.isNaN(i.totalFuelL));
        assertEquals(-1, i.odometerKm);
        assertEquals(-1, i.fuelBars);
        assertTrue(Double.isNaN(i.avgMileage));
        assertTrue(Double.isNaN(i.meterBatteryV));
        assertTrue(Double.isNaN(i.tripAKm));
        assertTrue(Double.isNaN(i.tripBKm));
        assertEquals(-1, i.avgSpeedKmh);
        assertEquals(-1, i.rangeKm);
        assertTrue(Double.isNaN(i.fuelConsumption));
        assertEquals(-1, i.totalTime);
        assertEquals(0xA0 * 20.0 / 256.0, i.ecuBatteryV, 1e-9);   // a real value next to them stays
        assertEquals(20.0, i.outsideC, 0);
    }

    /** A 0x40 config where every value of 0x41 exists, with its "valid" bit and the max lean. */
    static byte[] infoConfigWithValidBits() {
        byte[] v = blank(0x40, 35);
        put(v, 5, 0x05, 0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01);
        put(v, 25, 0x05, 0x13, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01);
        return v;
    }

    /** With the config saying the bike sends "valid" bits, a set bit hides the value it belongs to. */
    @Test
    public void vehicleInfoValidBits() {
        byte[] v = blank(0x41, 85);
        put(v, 5, 0x05, 0x14, 0x00, 0x00, 0x30, 0x39, 0xFF, 0xFF, 0xFF, 0xA0);
        // byte 27: odometer bit 6 set (not current), meter battery bit 5 clear, mileage bit 4 clear
        put(v, 25, 0x05, 0x16, 0x41, 0x86, 0xA0, 0x05, 0xFF, 0x00, 0xC8, 0x7E);
        // byte 37: trip A bit 7 clear, trip B bit 6 set
        put(v, 35, 0x05, 0x17, 0x40, 0x03, 0xE8, 0x00, 0x00, 0x7B, 0x48, 0x50);
        // byte 47: range bit 7 clear, time bit 6 set, lean left bit 3 clear, lean right bit 2 set; 53/54 lean
        put(v, 45, 0x05, 0x18, 0x45, 0x2C, 0x00, 0x2D, 0x00, 0x96, 41, 37);
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(infoConfigWithValidBits());
        BikeFrames.VehicleInfo i = (BikeFrames.VehicleInfo) d.decode(v);
        assertEquals(-1, i.odometerKm);                    // its bit says not current
        assertEquals(12.6, i.meterBatteryV, 1e-9);
        assertEquals(20.0, i.avgMileage, 1e-9);
        assertEquals(100.0, i.tripAKm, 1e-9);
        assertTrue(Double.isNaN(i.tripBKm));
        assertEquals(300, i.rangeKm);
        assertEquals(-1, i.totalTime);
        assertEquals(41, i.maxLeanLeft);                   // max lean from bytes 53/54
        assertEquals(-1, i.maxLeanRight);                  // its bit says not current

        // without a config that announces the bits, the values are kept (only placeholders are dropped)
        BikeFrames.VehicleInfo plain = (BikeFrames.VehicleInfo) new BikeFrames.Decoder().decode(v);
        assertEquals(100000, plain.odometerKm);
        assertEquals(-1, plain.maxLeanLeft);               // max lean needs the config to say it exists
    }

    /** TPMS counts as present unless byte 45 or 46 is FF — an ordinary 05 block marker included. */
    @Test
    public void tpmsPresentWithBlockMarker() {
        byte[] v = blank(0x45, 55);
        put(v, 5, 0x05, 0x15, 0x00, 0x01, 0x86, 0xA0, 0x00, 0x32);
        put(v, 45, 0x05, 0x19, 0x64, 0x6E, 0x05);
        BikeFrames.LogExt e = (BikeFrames.LogExt) new BikeFrames.Decoder().decode(v);
        assertEquals(100 * 1.373 + 100, e.tyreFrontKpa, 1e-9);
        assertEquals(5, e.tpmsWarnings);                   // front pressure drop + front sensor battery
        put(v, 46, 0xFF);
        e = (BikeFrames.LogExt) new BikeFrames.Decoder().decode(v);
        assertTrue(Double.isNaN(e.tyreFrontKpa));
        assertEquals(-1, e.tpmsWarnings);
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
