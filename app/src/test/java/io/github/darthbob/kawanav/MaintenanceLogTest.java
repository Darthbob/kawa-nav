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

    /** The Z900's maintenance config and answer of 25 Oct 2026, as the log has them. */
    private static BikeFrames.ServiceIndicator z900Service() {
        BikeFrames.Decoder d = new BikeFrames.Decoder();
        d.decode(KawaProtocol.parseHex("1D0C0C140005414300C3FC00CFFFFF"));
        return (BikeFrames.ServiceIndicator) d.decode(KawaProtocol.parseHex(("1E 2A 17 08 00"
                + " 05 4C 00 01 01 18 00 00 00 00 05 4D 00 0F FF FF FF 00 2E E0 05 4E 00 19 1D 1B 00 00 2B 25"
                + " FF FF FF FF FF FF FF FF FF FF").replace(" ", "")));
    }

    @Test
    public void theDashCountsTheKilometresDown() {
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.UK);
        BikeFrames.ServiceIndicator s = z900Service();
        // 11,045 km left at 5,552 km: due at 16,597 km
        java.util.Calendar due = java.util.Calendar.getInstance();
        due.clear();
        due.set(2027, java.util.Calendar.SEPTEMBER, 29);
        assertEquals(df.format(due.getTime()) + " or in 11,045 km (at 16,597 km)",
                MaintenanceLog.due(s.reminders[BikeFrames.ServiceIndicator.OIL], 5552, df));
        assertEquals("in 11,045 km", MaintenanceLog.due(s.reminders[BikeFrames.ServiceIndicator.OIL], Double.NaN,
                java.text.DateFormat.getDateInstance()).replaceFirst("^.* or ", ""));
        // the two reminders whose notify bits are 0
        assertTrue(MaintenanceLog.due(s.reminders[BikeFrames.ServiceIndicator.KAWASAKI], 5552, df).endsWith("reminder off"));
        assertEquals("in 12,000 km (at 17,552 km) · reminder off",
                MaintenanceLog.due(s.reminders[BikeFrames.ServiceIndicator.RIDER], 5552, df));
        assertEquals(3, MaintenanceLog.dueFromDash(s, 5552).size());
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
