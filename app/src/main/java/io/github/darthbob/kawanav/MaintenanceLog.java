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
