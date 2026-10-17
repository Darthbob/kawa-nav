package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Tyre, ABS and battery warnings from the bike's values, and announcing each one once. */
public class BikeAlertsTest {

    private static final long NOW = 1_800_000_000_000L;

    private static BikeData withTpms(int warnings) {
        byte[] v = BikeFramesTest.blank(0x45, 55);
        BikeFramesTest.put(v, 5, 0x05, 0x15, 0x00, 0x01, 0x86, 0xA0, 0x00, 0x32);
        BikeFramesTest.put(v, 45, 0x05, 0x19, 0x64, 0x6E, warnings);
        BikeData b = new BikeData();
        b.apply(new BikeFrames.Decoder().decode(v), NOW);
        return b;
    }

    private static List<String> ids(List<BikeAlerts.Alert> alerts) {
        List<String> out = new java.util.ArrayList<>();
        for (BikeAlerts.Alert a : alerts) out.add(a.id);
        return out;
    }

    @Test
    public void tyreWarnings() {
        assertTrue(BikeAlerts.current(withTpms(0), NOW).isEmpty());
        List<String> ids = ids(BikeAlerts.current(withTpms(0x01 | 0x08), NOW));
        assertEquals(2, ids.size());
        assertTrue(ids.contains("tpms_front"));
        assertTrue(ids.contains("tpms_rear_sensor"));
    }

    @Test
    public void oldValuesDoNotWarn() {
        assertTrue(BikeAlerts.current(withTpms(0x01), NOW + BikeAlerts.FRESH_MS + 1).isEmpty());
    }

    @Test
    public void absFault() {
        byte[] v = BikeFramesTest.telemetryFrame();
        BikeFramesTest.put(v, 37, 0x04);               // system error bits 3-2
        BikeData b = new BikeData();
        b.apply(new BikeFrames.Decoder().decode(v), NOW);
        assertEquals("abs_error", BikeAlerts.current(b, NOW).get(0).id);
        BikeFramesTest.put(v, 37, 0x03);               // front and rear ABS working: no fault
        b.apply(new BikeFrames.Decoder().decode(v), NOW);
        assertTrue(BikeAlerts.current(b, NOW).isEmpty());
    }

    private static BikeData withBattery(int ecuByte) {
        return withBattery(NOW, ecuByte, -1);
    }

    /** A 0x41 with the ECU battery byte (and the dash's byte when {@code meterByte} is not -1). */
    private static BikeData withBattery(long at, int ecuByte, int meterByte) {
        byte[] v = BikeFramesTest.blank(0x41, 85);
        BikeFramesTest.put(v, 5, 0x05, 0x14, 0x00, 0x00, 0x30, 0x39, 0xFF, 0xFF, 0xFF, ecuByte);
        if (meterByte >= 0) {
            BikeFramesTest.put(v, 25, 0x05, 0x18);      // the block that carries byte 34 must be present
            BikeFramesTest.put(v, 34, meterByte);
        }
        BikeData b = new BikeData();
        b.apply(new BikeFrames.Decoder().decode(v), at);
        return b;
    }

    @Test
    public void lowBatteryWithTheEngineOff() {
        // ECU battery = byte * 20 / 256: 0x96 = 11.7 V, 0xA2 = 12.7 V
        assertEquals("battery_low", BikeAlerts.current(withBattery(0x96), NOW).get(0).id);
        assertTrue(BikeAlerts.current(withBattery(0xA2), NOW).isEmpty());
    }

    @Test
    public void oneReadingLowIsNotABattery() {
        // 11.7 V at the ECU under the fuel pump's load, 12.3 V on the dash (byte 34, × 0.1): no warning
        assertTrue(BikeAlerts.current(withBattery(NOW, 0x96, 123), NOW).isEmpty());
        // both readings low: the warning, with the lower of the two
        List<BikeAlerts.Alert> both = BikeAlerts.current(withBattery(NOW, 0x96, 115), NOW);
        assertEquals("battery_low", both.get(0).id);
        assertTrue(both.get(0).detail.startsWith("11.5 V"));
    }

    @Test
    public void notChargingWithTheEngineRunning() {
        BikeData b = withBattery(0x96);                // 11.7 V
        // 4000 rpm: the engine speed needs the 0x40 config to be read
        b.apply(BikeFramesTest.telemetry(BikeFramesTest.infoConfig(0x04, 0x05, 0x00), BikeFramesTest.telemetryFrame()), NOW);
        assertEquals("battery_charging", BikeAlerts.current(b, NOW).get(0).id);
    }

    @Test
    public void aBatteryWarningWaitsForTheNextAnswer() {
        BikeAlerts.Monitor m = new BikeAlerts.Monitor();
        // the first 0x41: the pump is priming, the ECU reads 11.7 V. Held back.
        assertTrue(m.current(withBattery(NOW, 0x96, -1), NOW).isEmpty());
        // its next answer, 20 s later, still low: a real condition
        assertEquals("battery_low", m.current(withBattery(NOW + 20_000, 0x96, -1), NOW + 20_000).get(0).id);
        // the one after that goes on being low: still just the one warning
        assertEquals(1, m.current(withBattery(NOW + 40_000, 0x96, -1), NOW + 40_000).size());
    }

    @Test
    public void aTransientLowReadingPasses() {
        BikeAlerts.Monitor m = new BikeAlerts.Monitor();
        m.current(withBattery(NOW, 0x96, -1), NOW);
        // 20 s later the pump has stopped and the battery is normal: nothing was ever announced
        assertTrue(m.current(withBattery(NOW + 20_000, 0xA2, -1), NOW + 20_000).isEmpty());
    }

    @Test
    public void theSettingTakesTheBatteryWarningsOut() {
        List<BikeAlerts.Alert> alerts = BikeAlerts.current(withBattery(0x96), NOW);
        assertEquals(1, alerts.size());
        assertTrue(BikeAlerts.withoutBattery(alerts).isEmpty());
        // the other warnings stay
        List<BikeAlerts.Alert> tpms = BikeAlerts.current(withTpms(0x01), NOW);
        assertEquals(1, BikeAlerts.withoutBattery(tpms).size());
    }

    @Test
    public void eachAlertIsAnnouncedOnce() {
        BikeAlerts.Tracker t = new BikeAlerts.Tracker();
        List<BikeAlerts.Alert> front = BikeAlerts.current(withTpms(0x01), NOW);
        assertEquals(1, t.fresh(front).size());
        assertEquals(0, t.fresh(front).size());        // still there: not again
        assertEquals(0, t.fresh(BikeAlerts.current(withTpms(0), NOW)).size());
        assertEquals(1, t.fresh(front).size());        // came back after going away: again
    }
}
