package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The maintenance log: what was done, when, and how far ago. */
public class MaintenanceLogTest {
    private static final long DAY = 24 * 60 * 60_000L;

    private static MaintenanceLog.Entry entry(String item, long at, double odoKm) {
        MaintenanceLog.Entry e = new MaintenanceLog.Entry();
        e.item = item;
        e.at = at;
        e.odoKm = odoKm;
        return e;
    }

    @Test
    public void theNewestJobOfThatKindCounts() {
        List<MaintenanceLog.Entry> entries = new ArrayList<>();
        entries.add(entry("Oil change", 1000, 10000));
        entries.add(entry("Oil change", 1000 + 30 * DAY, 11000));
        entries.add(entry("Chain", 1000 + 40 * DAY, 11500));
        assertEquals(30, MaintenanceLog.daysSince(entries, "Oil change", 1000 + 60 * DAY));
        assertEquals(20, MaintenanceLog.daysSince(entries, "Chain", 1000 + 60 * DAY));
        assertEquals(-1, MaintenanceLog.daysSince(entries, "Tyres", 1000 + 60 * DAY));
    }

    @Test
    public void kilometresSinceAJob() {
        List<MaintenanceLog.Entry> entries = new ArrayList<>();
        entries.add(entry("Oil filter", 1000, 10000));
        assertEquals(2500, MaintenanceLog.kmSince(entries, "Oil filter", 12500), 1e-9);
        assertTrue(Double.isNaN(MaintenanceLog.kmSince(entries, "Chain", 12500)));
        assertTrue(Double.isNaN(MaintenanceLog.kmSince(entries, "Oil filter", Double.NaN)));
        assertTrue(Double.isNaN(MaintenanceLog.kmSince(entries, "Oil filter", 9000))); // odometer went back
    }

    @Test
    public void entriesOlderThanTheOdometerAreIgnored() {
        List<MaintenanceLog.Entry> entries = new ArrayList<>();
        entries.add(entry("Tyres", 1000, Double.NaN));    // the rider did not note the kilometres
        assertTrue(Double.isNaN(MaintenanceLog.kmSince(entries, "Tyres", 12000)));
    }

    @Test
    public void sortedOldestFirst() {
        List<MaintenanceLog.Entry> entries = new ArrayList<>();
        entries.add(entry("B", 200, 1));
        entries.add(entry("A", 100, 1));
        List<MaintenanceLog.Entry> out = MaintenanceLog.sorted(entries);
        assertEquals("A", out.get(0).item);
    }
}
