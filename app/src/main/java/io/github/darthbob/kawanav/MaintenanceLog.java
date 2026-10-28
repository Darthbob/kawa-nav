package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;

/**
 * What was done to the bike and when: work the dash cannot know about (a chain, tyres, an oil change done
 * at home) next to the reminders the dash does keep. Plain Java, so the "what is due" arithmetic is tested.
 */
public final class MaintenanceLog {
    /** The jobs the list offers; anything else can be typed. */
    public static final String[] ITEMS = {"Oil change", "Oil filter", "Air filter", "Chain", "Tyres",
            "Brake pads", "Brake fluid", "Coolant", "Spark plugs", "Valve clearance", "Battery", "Other"};

    /** One job done. */
    public static final class Entry implements Comparable<Entry> {
        public long at;
        /** Odometer when it was done, NaN when the rider did not say. */
        public double odoKm = Double.NaN;
        public String item = "";
        public String note = "";

        @Override
        public int compareTo(Entry o) {
            return Long.compare(at, o.at);
        }

        public String describe() {
            return odoKm == odoKm ? String.format(java.util.Locale.ROOT, "%s · %,.0f km", item, odoKm) : item;
        }
    }

    /** The entries, oldest first. */
    public static List<Entry> sorted(List<Entry> entries) {
        List<Entry> out = new ArrayList<>(entries);
        java.util.Collections.sort(out);
        return out;
    }

    /** How long ago, in whole days, the job was done; -1 when there is nothing logged for it. */
    public static long daysSince(List<Entry> entries, String item, long now) {
        long at = 0;
        for (Entry e : entries) {
            if (e.item.equalsIgnoreCase(item) && e.at > at) at = e.at;
        }
        return at == 0 ? -1 : (now - at) / (24 * 60 * 60_000L);
    }

    /** Kilometres since the job was done, or NaN when nothing is logged or an odometer is missing. */
    public static double kmSince(List<Entry> entries, String item, double odoKm) {
        if (odoKm != odoKm) return Double.NaN;
        double at = Double.NaN;
        for (Entry e : entries) {
            if (!e.item.equalsIgnoreCase(item) || e.odoKm != e.odoKm) continue;
            if (at != at || e.odoKm > at) at = e.odoKm;
        }
        if (at != at) return Double.NaN;
        double km = odoKm - at;
        return km >= 0 ? km : Double.NaN;
    }

    // ------------------------------------------------------------------ the rider's own reminders

    /**
     * A job the rider wants to be reminded of: again every {@code km} kilometres and/or every {@code months}
     * months after it was last logged, whichever comes first (0 = not by that).
     */
    public static final class Reminder {
        public final String item;
        public final int km, months;

        public Reminder(String item, int km, int months) {
            this.item = item;
            this.km = Math.max(0, km);
            this.months = Math.max(0, months);
        }

        /** "every 1,000 km", "every 12 months", "every 6,000 km or 12 months". */
        public String interval() {
            List<String> parts = new ArrayList<>();
            if (km > 0) parts.add(String.format(java.util.Locale.ROOT, "%,d km", km));
            if (months > 0) parts.add(months + (months == 1 ? " month" : " months"));
            return "every " + String.join(" or ", parts);
        }
    }

    /** The reminders as the settings keep them: a line each, {@code km;months;item}. */
    public static String saveReminders(List<Reminder> reminders) {
        StringBuilder b = new StringBuilder();
        for (Reminder r : reminders) {
            if (b.length() > 0) b.append('\n');
            b.append(r.km).append(';').append(r.months).append(';').append(r.item.replaceAll("\\s+", " ").trim());
        }
        return b.toString();
    }

    /** The reminders back from {@link #saveReminders}; a line that does not read is left out. */
    public static List<Reminder> reminders(String saved) {
        List<Reminder> out = new ArrayList<>();
        if (saved == null) return out;
        for (String line : saved.split("\n")) {
            String[] f = line.split(";", 3);
            if (f.length < 3 || f[2].trim().isEmpty()) continue;
            try {
                Reminder r = new Reminder(f[2].trim(), Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()));
                if (r.km > 0 || r.months > 0) out.add(r);
            } catch (NumberFormatException ignored) {
                // a line from somewhere else: not a reminder
            }
        }
        return out;
    }

    /** The reminder for this job, or null. Jobs are matched as the log matches them, ignoring case. */
    public static Reminder reminderFor(List<Reminder> reminders, String item) {
        for (Reminder r : reminders) if (r.item.equalsIgnoreCase(item)) return r;
        return null;
    }

    /** Where a reminder stands against the log, the odometer and the date. */
    public static final class Due {
        public final Reminder reminder;
        /** The job has never been logged: the reminder counts from its first entry. */
        public final boolean never;
        /** When the job was last logged (0 for never): a reminder is announced once for each such entry. */
        public final long lastAt;
        /** Kilometres until it is due, negative when it is past; NaN when not counted (no odometer). */
        public final double kmLeft;
        /** An entry of the job has its odometer: without the bike's, the kilometres cannot be counted yet. */
        public final boolean odoLogged;
        /** The day it is due, 0 when it is not by the date. */
        public final long dueAt;
        final long now;

        Due(Reminder reminder, long lastAt, double kmLeft, boolean odoLogged, long dueAt, long now) {
            this.reminder = reminder;
            this.never = lastAt == 0;
            this.lastAt = lastAt;
            this.kmLeft = kmLeft;
            this.odoLogged = odoLogged;
            this.dueAt = dueAt;
            this.now = now;
        }

        /** Due now: the kilometres or the date have been reached. */
        public boolean due() {
            return !never && (kmLeft <= 0 || dueAt > 0 && now >= dueAt);
        }

        /**
         * Due soon: within a tenth of its kilometres (at least {@link #SOON_KM}) or {@link #SOON_DAYS}
         * days, not yet due.
         */
        public boolean soon() {
            if (never || due()) return false;
            return kmLeft <= Math.max(SOON_KM, reminder.km / 10.0) || dueAt > 0 && dueAt - now <= SOON_DAYS * DAY_MS;
        }

        /** "due: 120 km over", "in 230 km or by 3 Mar 2027", "log it once to start counting". */
        public String describe(java.text.DateFormat df) {
            if (never) return "never logged: log it once to start counting";
            List<String> parts = new ArrayList<>();
            boolean byKm = kmLeft == kmLeft, byDate = dueAt > 0;
            if (due()) {
                if (byKm && kmLeft <= 0) parts.add(String.format(java.util.Locale.ROOT, "%,.0f km over", -kmLeft));
                if (byDate && now >= dueAt) parts.add("since " + df.format(new java.util.Date(dueAt)));
                return "due: " + String.join(", ", parts);
            }
            if (byKm) parts.add(String.format(java.util.Locale.ROOT, "in %,.0f km", kmLeft));
            if (byDate) parts.add("by " + df.format(new java.util.Date(dueAt)));
            if (!parts.isEmpty()) return String.join(" or ", parts);
            return odoLogged ? "the kilometres count once the bike has sent its odometer"
                    : "log the odometer with the job to count the kilometres";
        }
    }

    static final double SOON_KM = 100;
    static final int SOON_DAYS = 14;
    private static final long DAY_MS = 24 * 60 * 60_000L;

    /**
     * Where a reminder stands: the kilometres from the newest entry of that job with an odometer, the
     * months from its newest entry. {@code odoKm} NaN (bike not read) leaves the kilometres uncounted.
     */
    public static Due due(List<Entry> entries, Reminder r, double odoKm, long now) {
        long lastAt = 0;
        boolean odoLogged = false;
        for (Entry e : entries) {
            if (!e.item.equalsIgnoreCase(r.item)) continue;
            if (e.at > lastAt) lastAt = e.at;
            odoLogged |= e.odoKm == e.odoKm;
        }
        double kmLeft = Double.NaN;
        if (r.km > 0) {
            double since = kmSince(entries, r.item, odoKm);
            if (since == since) kmLeft = r.km - since;
        }
        long dueAt = 0;
        if (r.months > 0 && lastAt > 0) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(lastAt);
            cal.add(java.util.Calendar.MONTH, r.months);
            dueAt = cal.getTimeInMillis();
        }
        return new Due(r, lastAt, kmLeft, odoLogged, dueAt, now);
    }

    /** What a due reminder is announced under: once for each time the job was logged. */
    public static String toldKey(Due d) {
        return d.reminder.item.toLowerCase(java.util.Locale.ROOT) + "@" + d.lastAt;
    }

    /** Every reminder's standing, in the order they were set. */
    public static List<Due> due(List<Entry> entries, List<Reminder> reminders, double odoKm, long now) {
        List<Due> out = new ArrayList<>();
        for (Reminder r : reminders) out.add(due(entries, r, odoKm, now));
        return out;
    }

    /**
     * What the dash says is due for its own reminders: a line per reminder, empty when it says nothing.
     */
    public static List<String> dueFromDash(BikeFrames.ServiceIndicator service, double odoKm) {
        List<String> out = new ArrayList<>();
        if (service == null) return out;
        java.text.DateFormat df = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        for (int k = 0; k < service.reminders.length; k++) {
            String due = due(service.reminders[k], odoKm, df);
            if (due != null) out.add(BikeFrames.ServiceIndicator.NAMES[k] + ": " + due);
        }
        return out;
    }

    /**
     * When one of the dash's reminders is due, null when it says nothing. The date is the day it is due;
     * the distance is what is left, counting down with the odometer (a Z900's oil change read 11,338 km
     * at 5,259 km and 11,045 km at 5,552 km: due at 16,597 km both times), so with the odometer known it
     * also says where it falls. A reminder whose notify bits are 0 is marked off.
     */
    static String due(BikeFrames.ServiceIndicator.Reminder m, double odoKm, java.text.DateFormat df) {
        if (!m.supported) return null;
        List<String> due = new ArrayList<>();
        if (m.hasDate()) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.clear();
            cal.set(m.year, m.month - 1, m.day);
            due.add(df.format(cal.getTime()));
        }
        if (m.distanceKm >= 0) {
            String km = String.format(java.util.Locale.ROOT, "in %,d km", m.distanceKm);
            if (odoKm >= 0) km += String.format(java.util.Locale.ROOT, " (at %,.0f km)", odoKm + m.distanceKm);
            due.add(km);
        }
        if (due.isEmpty()) return null;
        return String.join(" or ", due) + (m.notify == 0 ? " · reminder off" : "");
    }
}
