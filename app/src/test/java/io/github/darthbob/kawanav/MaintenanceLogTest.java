package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    // ------------------------------------------------------------------ the rider's own reminders

    @Test
    public void remindersAreKeptAsLines() {
        List<MaintenanceLog.Reminder> in = new ArrayList<>();
        in.add(new MaintenanceLog.Reminder("Chain", 1000, 0));
        in.add(new MaintenanceLog.Reminder("Oil change; filter too", 6000, 12));
        List<MaintenanceLog.Reminder> out = MaintenanceLog.reminders(MaintenanceLog.saveReminders(in));
        assertEquals(2, out.size());
        assertEquals("Chain", out.get(0).item);
        assertEquals(1000, out.get(0).km);
        assertEquals("a ; in the name stays in the name", "Oil change; filter too", out.get(1).item);
        assertEquals(12, out.get(1).months);
        assertEquals("every 6,000 km or 12 months", out.get(1).interval());
        assertEquals("every 1 month", new MaintenanceLog.Reminder("Chain", 0, 1).interval());
        // nothing saved, a broken line, a reminder with neither kilometres nor months
        assertTrue(MaintenanceLog.reminders(null).isEmpty());
        assertTrue(MaintenanceLog.reminders("x;1;Chain\n0;0;Tyres\n").isEmpty());
        assertEquals("Chain", MaintenanceLog.reminderFor(out, "chain").item);
    }

    @Test
    public void aReminderCountsFromTheNewestJob() {
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.UK);
        List<MaintenanceLog.Entry> entries = new ArrayList<>();
        entries.add(entry("Chain", 1000, 10000));
        entries.add(entry("Chain", 1000 + 10 * DAY, 10800));
        MaintenanceLog.Reminder chain = new MaintenanceLog.Reminder("Chain", 1000, 0);
        MaintenanceLog.Due d = MaintenanceLog.due(entries, chain, 11570, 1000 + 20 * DAY);
        assertEquals(230, d.kmLeft, 1e-9);
        assertFalse(d.due());
        assertFalse("230 km left of 1,000 is not soon", d.soon());
        assertEquals("in 230 km", d.describe(df));
        assertTrue(MaintenanceLog.due(entries, chain, 11750, 0).soon());
        MaintenanceLog.Due over = MaintenanceLog.due(entries, chain, 11920, 1000 + 30 * DAY);
        assertTrue(over.due());
        assertEquals("due: 120 km over", over.describe(df));
        assertEquals("announced once per job logged", 1000 + 10 * DAY, over.lastAt);
    }

    @Test
    public void aReminderByMonthsOrKilometresWhicheverComesFirst() {
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.UK);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.clear();
        cal.set(2026, java.util.Calendar.MARCH, 3);
        long done = cal.getTimeInMillis();
        cal.set(2027, java.util.Calendar.MARCH, 3);
        long year = cal.getTimeInMillis();
        List<MaintenanceLog.Entry> entries = new ArrayList<>();
        entries.add(entry("Oil change", done, 5000));
        MaintenanceLog.Reminder oil = new MaintenanceLog.Reminder("Oil change", 6000, 12);
        MaintenanceLog.Due d = MaintenanceLog.due(entries, oil, 7000, done + 100 * DAY);
        assertEquals(year, d.dueAt);
        assertEquals("in 4,000 km or by " + df.format(new java.util.Date(year)), d.describe(df));
        // the year is up before the kilometres
        MaintenanceLog.Due late = MaintenanceLog.due(entries, oil, 9000, year + DAY);
        assertTrue(late.due());
        assertEquals("due: since " + df.format(new java.util.Date(year)), late.describe(df));
        assertTrue("two weeks before the date", MaintenanceLog.due(entries, oil, 7000, year - 10 * DAY).soon());
        // without the bike's odometer only the date counts
        assertFalse(MaintenanceLog.due(entries, oil, Double.NaN, done + 100 * DAY).due());
    }

    @Test
    public void aJobNeverLoggedIsNotDue() {
        java.text.DateFormat df = java.text.DateFormat.getDateInstance();
        MaintenanceLog.Due d = MaintenanceLog.due(new ArrayList<>(), new MaintenanceLog.Reminder("Tyres", 8000, 24),
                20000, 0);
        assertTrue(d.never);
        assertFalse(d.due());
        assertFalse(d.soon());
        assertEquals("never logged: log it once to start counting", d.describe(df));
        // logged without the odometer: the kilometres cannot be counted, the months can
        List<MaintenanceLog.Entry> entries = new ArrayList<>();
        entries.add(entry("Tyres", 1000, Double.NaN));
        MaintenanceLog.Due noOdo = MaintenanceLog.due(entries, new MaintenanceLog.Reminder("Tyres", 8000, 0), 20000, 2000);
        assertEquals("log the odometer with the job to count the kilometres", noOdo.describe(df));
        // logged with the odometer, but the bike has not sent its own yet
        List<MaintenanceLog.Entry> withOdo = new ArrayList<>();
        withOdo.add(entry("Tyres", 1000, 12000));
        assertEquals("the kilometres count once the bike has sent its odometer", MaintenanceLog.due(withOdo,
                new MaintenanceLog.Reminder("Tyres", 8000, 0), Double.NaN, 2000).describe(df));
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
