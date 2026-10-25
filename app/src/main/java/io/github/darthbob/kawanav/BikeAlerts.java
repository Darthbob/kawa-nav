package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Warnings the bike sends but the dash may not make obvious: a tyre losing pressure or a TPMS sensor
 * with a flat battery (0x45 byte 49), an ABS system error (0x4A byte 37 bits 3-2), and a 12 V battery
 * that is low or not being charged (0x41). Plain Java: {@link #current} looks at the bike's last values,
 * {@link Tracker} turns them into "new since last time" so each problem is announced once.
 */
public final class BikeAlerts {
    private BikeAlerts() {}

    /** One problem, with a stable id (for "announced already") and the text to show. */
    public static final class Alert {
        public final String id, title, detail;

        Alert(String id, String title, String detail) {
            this.id = id;
            this.title = title;
            this.detail = detail;
        }
    }

    /** Values older than this no longer count (the bike has been switched off, or stopped sending). */
    static final long FRESH_MS = 60_000;
    /** Engine off: a healthy battery rests at 12.4-12.7 V; under this it is low. */
    static final double LOW_BATTERY_V = 11.8;
    /** Engine running: the charging system keeps it above 13 V; under this it is not charging. */
    static final double NOT_CHARGING_V = 12.2;
    /** Engine speed above which the engine counts as running. */
    static final double RUNNING_RPM = 500;
    /**
     * A 0x41 read this close to the engine starting or stopping may carry either state's voltage: the one
     * before the start, or the charging voltage just before a stop.
     */
    static final long ENGINE_SETTLE_MS = 5_000;
    /**
     * After the engine has run the battery holds 13-14 V for a while (surface charge): a resting reading
     * only counts this long after the engine was last seen running.
     */
    static final long REST_MS = 30 * 60_000;
    /**
     * The ECU's reading under load: on the Z900 it reads 0.45-0.55 V under the dash's at rest and while
     * charging, but 1.3 V under it while the starter turns. A larger gap than this is a load, not a battery.
     */
    static final double LOAD_GAP_V = 0.9;

    /** The problems the bike's last values show right now (empty when none, or when they are old). */
    public static List<Alert> current(BikeData b, long now) {
        List<Alert> out = new ArrayList<>();
        BikeFrames.LogExt ext = b.ext;
        if (ext != null && now - b.extAt <= FRESH_MS && ext.tpmsWarnings > 0) {
            int w = ext.tpmsWarnings;
            if ((w & 1) != 0) out.add(new Alert("tpms_front", "Front tyre is losing pressure", tyre(ext.tyreFrontKpa)));
            if ((w & 2) != 0) out.add(new Alert("tpms_rear", "Rear tyre is losing pressure", tyre(ext.tyreRearKpa)));
            if ((w & 4) != 0) out.add(new Alert("tpms_front_sensor", "Front tyre sensor battery is low",
                    "The pressure sensor in the front wheel needs a new battery"));
            if ((w & 8) != 0) out.add(new Alert("tpms_rear_sensor", "Rear tyre sensor battery is low",
                    "The pressure sensor in the rear wheel needs a new battery"));
        }
        BikeFrames.Telemetry live = b.live;
        boolean liveFresh = live != null && now - b.liveAt <= FRESH_MS;
        if (liveFresh && live.abs >= 0 && (live.abs & 0x0C) != 0) {
            out.add(new Alert("abs_error", "ABS reports a fault",
                    "The ABS may not work: check the warning lamp on the dash and have the bike looked at"));
        }
        BikeFrames.VehicleInfo info = b.info;
        // a reading taken before the engine last started or stopped belongs to the other state: judged
        // against this one's threshold, the 11.5 V read before a start became "not being charged" at
        // every start. The next answer, 20 s later, decides.
        boolean sameState = !liveFresh || b.infoAt >= b.engineChangedAt + ENGINE_SETTLE_MS;
        if (info != null && now - b.infoAt <= FRESH_MS && sameState) {
            boolean running = liveFresh && running(live);
            double v = lowVoltage(info, running ? NOT_CHARGING_V : LOW_BATTERY_V);
            if (v == v && running) {
                out.add(new Alert("battery_charging", "Battery is not being charged",
                        String.format(Locale.ROOT, "%.1f V with the engine running: check the charging system", v)));
            } else if (v == v) {
                out.add(new Alert("battery_low", "Battery is low",
                        String.format(Locale.ROOT, "%.1f V: the bike may not start next time", v)));
            }
        }
        return out;
    }

    /** True when a live frame says the engine is running (an absent engine speed says it is not). */
    static boolean running(BikeFrames.Telemetry t) {
        return t.rpm > RUNNING_RPM;
    }

    /** The battery voltage the bike reports: the ECU's when it has one, else the meter's. */
    static double battery(BikeFrames.VehicleInfo info) {
        return info.ecuBatteryV == info.ecuBatteryV ? info.ecuBatteryV : info.meterBatteryV;
    }

    /**
     * The battery's voltage at rest from the bike's last values, for the 12 V log; NaN unless it can be
     * trusted. The engine must be known to be off, have stayed off since {@link #ENGINE_SETTLE_MS}
     * before the reading and for as long after it (so a reading while the starter turns is not kept),
     * and not have run for {@link #REST_MS} (the battery still holds 13-14 V from the ride). With both
     * readings, the ECU's must not be far under the dash's ({@link #LOAD_GAP_V}): that is a load.
     */
    static double restingVoltage(BikeData b, long now) {
        BikeFrames.VehicleInfo info = b.info;
        BikeFrames.Telemetry live = b.live;
        if (info == null || now - b.infoAt > FRESH_MS) return Double.NaN;
        if (live == null || now - b.liveAt > FRESH_MS || live.rpm < 0 || running(live)) return Double.NaN;
        if (now - b.infoAt < ENGINE_SETTLE_MS || b.engineChangedAt > b.infoAt - ENGINE_SETTLE_MS) return Double.NaN;
        if (b.engineRunningAt > 0 && b.infoAt - b.engineRunningAt < REST_MS) return Double.NaN;
        if (info.meterBatteryV - info.ecuBatteryV > LOAD_GAP_V) return Double.NaN;
        return battery(info);
    }

    /**
     * The lowest voltage the bike reports, when **every** reading it gives is below {@code threshold}
     * (NaN otherwise). The ECU's supply dips under load — the fuel pump priming before a start, a cold
     * start — while the dash's own reading stays normal, so one channel alone must not raise a warning.
     * The ECU reading is shown when it is the lower one, as it is the one that dipped.
     */
    static double lowVoltage(BikeFrames.VehicleInfo info, double threshold) {
        boolean ecu = info.ecuBatteryV == info.ecuBatteryV;
        boolean meter = info.meterBatteryV == info.meterBatteryV;
        if (!ecu && !meter) return Double.NaN;
        double low = Double.POSITIVE_INFINITY;
        if (ecu) {
            if (!(info.ecuBatteryV < threshold)) return Double.NaN;
            low = Math.min(low, info.ecuBatteryV);
        }
        if (meter) {
            if (!(info.meterBatteryV < threshold)) return Double.NaN;
            low = Math.min(low, info.meterBatteryV);
        }
        return low;
    }

    /** The alerts without the battery ones (the "Battery warnings" setting is off). */
    public static List<Alert> withoutBattery(List<Alert> alerts) {
        List<Alert> out = new ArrayList<>();
        for (Alert a : alerts) if (!a.id.startsWith("battery_")) out.add(a);
        return out;
    }

    private static String tyre(double kpa) {
        return kpa == kpa ? String.format(Locale.ROOT, "%.2f bar now: stop and check it", kpa / 100.0)
                : "Stop and check it";
    }

    /**
     * The bike's warnings as they stand now, with the battery ones held back until a second 0x41 has
     * said the same thing. The meter answers 0x41 every 20 s, and one reading taken while the pump
     * primes or the engine has just started is not a battery condition; the next answer, 20 s later,
     * decides. A battery warning that changes kind (low, then not charging) waits one answer as well.
     */
    public static final class Monitor {
        private long infoAt = Long.MIN_VALUE;
        private String previousId, confirmedId;

        public List<Alert> current(BikeData b, long now) {
            List<Alert> out = BikeAlerts.current(b, now);
            if (b.info == null || now - b.infoAt > FRESH_MS) {
                infoAt = Long.MIN_VALUE;
                previousId = confirmedId = null;
                return out;
            }
            String id = null;
            for (Alert a : out) if (a.id.startsWith("battery_")) id = a.id;
            if (b.infoAt != infoAt) {
                confirmedId = id != null && id.equals(previousId) ? id : null;
                previousId = id;
                infoAt = b.infoAt;
            }
            // the warning shown now, not the one of the last answer: a warning can also appear between
            // two answers (the engine started), and then the next answer has to say it too
            if (id != null && !id.equals(confirmedId)) {
                String held = id;
                out.removeIf(a -> a.id.equals(held));
            }
            return out;
        }
    }

    /**
     * Remembers what was announced: an alert is new the first time it shows up, and again only after it
     * has gone away (a tyre pumped up, the fault cleared). A battery reading that flickers around its
     * threshold therefore does not repeat every few seconds.
     */
    public static final class Tracker {
        private final java.util.Set<String> active = new java.util.HashSet<>();

        /** The alerts of {@code now} that were not active before. */
        public List<Alert> fresh(List<Alert> now) {
            List<Alert> out = new ArrayList<>();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Alert a : now) {
                ids.add(a.id);
                if (!active.contains(a.id)) out.add(a);
            }
            active.retainAll(ids);
            active.addAll(ids);
            return out;
        }
    }
}
