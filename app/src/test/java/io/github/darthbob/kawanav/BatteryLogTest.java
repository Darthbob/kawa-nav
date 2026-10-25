package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The 12 V log: what counts as a reading, and when the battery looks like it is going. */
public class BatteryLogTest {
    private static final long DAY = 24 * 60 * 60_000L;

    private static List<BatteryLog.Reading> readings(double... volts) {
        List<BatteryLog.Reading> out = new ArrayList<>();
        for (int i = 0; i < volts.length; i++) out.add(new BatteryLog.Reading(1000 + i * DAY, volts[i]));
        return out;
    }

    @Test
    public void chargingVoltagesAreNotReadings() {
        List<BatteryLog.Reading> out = BatteryLog.record(new ArrayList<>(), 1000, 14.2, true);
        assertTrue(out.isEmpty());
        assertTrue(BatteryLog.record(new ArrayList<>(), 1000, Double.NaN, false).isEmpty());
        assertEquals(1, BatteryLog.record(new ArrayList<>(), 1000, 12.6, false).size());
    }

    @Test
    public void aChargingVoltageIsNoRestingOne() {
        assertTrue(BatteryLog.record(new ArrayList<>(), 1000, 13.9, false).isEmpty());
        // the ones an older version kept are left out when the log is read
        List<BatteryLog.Reading> old = readings(11.4, 13.9, 11.6, 14.3, 12.7);
        List<BatteryLog.Reading> kept = BatteryLog.restingOnly(old);
        assertEquals(3, kept.size());
        assertEquals(12.7, kept.get(2).volts, 1e-9);
    }

    @Test
    public void oneReadingPerStop() {
        List<BatteryLog.Reading> out = BatteryLog.record(new ArrayList<>(), 1000, 12.6, false);
        out = BatteryLog.record(out, 1000 + 60_000, 12.5, false);          // same stop: the idle voltage
        assertEquals(1, out.size());
        out = BatteryLog.record(out, 1000 + 2 * BatteryLog.SAME_STOP_MS, 12.5, false);
        assertEquals(2, out.size());
    }

    @Test
    public void theOldestReadingsFallOff() {
        List<BatteryLog.Reading> out = new ArrayList<>();
        for (int i = 0; i < BatteryLog.MAX + 20; i++) out = BatteryLog.record(out, i * DAY * 2L, 12.6, false);
        assertEquals(BatteryLog.MAX, out.size());
        assertEquals(20 * DAY * 2L, out.get(0).at);
    }

    @Test
    public void aSlowFallIsNotEnoughToOneDay() {
        assertTrue(Double.isNaN(BatteryLog.decline(readings(12.6, 12.5))));   // too few
        List<BatteryLog.Reading> same = readings(12.6, 12.6, 12.55, 12.6);    // noise around the same value
        assertFalse(BatteryLog.declining(same));
        List<BatteryLog.Reading> shortSpan = new ArrayList<>();
        for (int i = 0; i < 5; i++) shortSpan.add(new BatteryLog.Reading(1000 + i * 60_000L, 12.6 - i * 0.2));
        assertFalse(BatteryLog.declining(shortSpan));                        // all in one afternoon
    }

    @Test
    public void aRealDeclineIsCaught() {
        List<BatteryLog.Reading> out = readings(12.7, 12.6, 12.4, 12.2);
        assertTrue(BatteryLog.declining(out));
        assertEquals(0.5, BatteryLog.decline(out), 1e-9);
    }

    @Test
    public void rangeOfTheLog() {
        double[] r = BatteryLog.range(readings(12.2, 12.9, 12.5));
        assertEquals(12.2, r[0], 1e-9);
        assertEquals(12.9, r[1], 1e-9);
    }
}
