package io.github.darthbob.kawanav;

import java.util.Locale;

/**
 * What KawaNav knows about the bike: the last frame of each kind the meter sent and when (wall-clock
 * ms). Filled by the bridge, read by the UI and the ride recorder; main thread only.
 */
public final class BikeData {
    public BikeFrames.ModelInfo model;
    public BikeFrames.GeneralCaps caps;
    public BikeFrames.InfoConfig infoConfig;
    public BikeFrames.TuningConfig tuning;
    public BikeFrames.ServiceConfig serviceConfig;
    public BikeFrames.VehicleInfo info;
    public BikeFrames.LogExt ext;
    public BikeFrames.Telemetry live;
    public BikeFrames.ImuSusp imu;
    public BikeFrames.GeneralSettings general;
    public BikeFrames.VehicleSettings settings;
    public BikeFrames.ServiceIndicator service;
    public long infoAt, extAt, liveAt, imuAt, settingsAt, serviceAt, generalAt;
    /** Where the phone was when the link to the bike was lost (≈ where the bike was parked), or null. */
    public Place parked;

    /** A GPS position with its accuracy and time. */
    public static final class Place {
        public final double lat, lon;
        public final float accuracyM;
        public final long at;

        public Place(double lat, double lon, float accuracyM, long at) {
            this.lat = lat;
            this.lon = lon;
            this.accuracyM = accuracyM;
            this.at = at;
        }
    }

    /** Debug: frames received per type since connecting, the last one and when. */
    public final int[] count = new int[256];
    public final byte[][] last = new byte[256][];
    public final long[] lastAt = new long[256];
    /** Debug: when counting started, for frame rates. */
    public long since;

    public void apply(BikeFrames.Frame f, long now) {
        if (f == null) return;
        int id = f.id;
        if (count[id]++ == 0 && since == 0) since = now;
        last[id] = f.raw;
        lastAt[id] = now;
        if (f instanceof BikeFrames.ModelInfo) {
            BikeFrames.ModelInfo m = (BikeFrames.ModelInfo) f;
            if (m.model != null) model = m;
        } else if (f instanceof BikeFrames.GeneralCaps) {
            caps = (BikeFrames.GeneralCaps) f;
        } else if (f instanceof BikeFrames.InfoConfig) {
            infoConfig = (BikeFrames.InfoConfig) f;
        } else if (f instanceof BikeFrames.TuningConfig) {
            tuning = (BikeFrames.TuningConfig) f;
        } else if (f instanceof BikeFrames.ServiceConfig) {
            serviceConfig = (BikeFrames.ServiceConfig) f;
        } else if (f instanceof BikeFrames.VehicleInfo) {
            info = (BikeFrames.VehicleInfo) f;
            infoAt = now;
        } else if (f instanceof BikeFrames.LogExt) {
            ext = (BikeFrames.LogExt) f;
            extAt = now;
        } else if (f instanceof BikeFrames.Telemetry) {
            live = (BikeFrames.Telemetry) f;
            liveAt = now;
        } else if (f instanceof BikeFrames.ImuSusp) {
            imu = (BikeFrames.ImuSusp) f;
            imuAt = now;
        } else if (f instanceof BikeFrames.GeneralSettings) {
            BikeFrames.GeneralSettings g = (BikeFrames.GeneralSettings) f;
            if (g.result >= 0) {
                general = g;
                generalAt = now;
            }
        } else if (f instanceof BikeFrames.VehicleSettings) {
            settings = (BikeFrames.VehicleSettings) f;
            settingsAt = now;
        } else if (f instanceof BikeFrames.ServiceIndicator) {
            service = (BikeFrames.ServiceIndicator) f;
            serviceAt = now;
        }
    }

    /** A new connection: the per-connection counters start again; the last values stay. */
    public void connected() {
        java.util.Arrays.fill(count, 0);
        since = 0;
    }

    /** The VIN with all but the last 6 characters hidden, for logs (users share them). */
    public static String maskedVin(String vin) {
        if (vin == null) return "–";
        return vin.length() <= 6 ? vin : "…" + vin.substring(vin.length() - 6);
    }

    // ------------------------------------------------------------------ debug text

    /** Everything decoded so far, for the debug card. */
    public String describe(long now) {
        StringBuilder sb = new StringBuilder();
        if (model != null) sb.append("Model ").append(model.model).append(", VIN ").append(maskedVin(model.vin)).append('\n');
        if (live != null) {
            BikeFrames.Telemetry t = live;
            sb.append(String.format(Locale.ROOT,
                    "Live (%s ago): %s km/h (other scaling %s), %d rpm (other %d), gear %s, throttle %.0f %%, "
                            + "lean %.1f°, accel %.2f g, injection %s, TCS %d/%d, ABS %s, brake %.1f\n",
                    ago(liveAt, now), num(t.speedKmh), num(t.speedKmhAlt), t.rpm, t.rpmAlt,
                    t.gear < 0 ? "–" : String.valueOf(t.gear), t.throttlePct,
                    t.leanDeg, t.accelG, num(t.fuelInjection), t.tcsHigh, t.tcsLow,
                    t.abs < 0 ? "–" : String.format(Locale.ROOT, "%02X", t.abs), t.brakeFrontBar));
        }
        if (info != null) {
            BikeFrames.VehicleInfo i = info;
            sb.append(String.format(Locale.ROOT,
                    "Info (%s ago): odo %d km, trip A %s, trip B %s, fuel %d bars, range %d km (symbol %d), "
                            + "avg mileage %s, consumption %s, avg speed %d, battery %s V (meter %s V), outside %s °C, "
                            + "total fuel %s L, total time %d\n",
                    ago(infoAt, now), i.odometerKm, num(i.tripAKm), num(i.tripBKm), i.fuelBars, i.rangeKm, i.rangeSymbol,
                    num(i.avgMileage), num(i.fuelConsumption), i.avgSpeedKmh, num(i.ecuBatteryV), num(i.meterBatteryV),
                    num(i.outsideC), num(i.totalFuelL), i.totalTime));
        }
        if (ext != null) {
            BikeFrames.LogExt e = ext;
            sb.append(String.format(Locale.ROOT,
                    "Ext (%s ago): water %s °C, oil %s °C, intake %s °C, distance %s km, fuel rate %s, instant %s, tyres %s/%s kPa\n",
                    ago(extAt, now), num(e.waterC), num(e.oilC), num(e.intakeC), num(e.totalDistanceKm), num(e.fuelRate),
                    num(e.instantFuel), num(e.tyreFrontKpa), num(e.tyreRearKpa)));
        }
        if (settings != null) {
            BikeFrames.VehicleSettings s = settings;
            sb.append(String.format(Locale.ROOT, "Settings: riding mode %d, power %d, KTRC %d, KQS %d, KEBC %d, KECS %d\n",
                    s.ridingMode, s.power, s.ktrc, s.kqs, s.kebc, s.kecsMode));
        }
        if (general != null) {
            BikeFrames.GeneralSettings g = general;
            sb.append(String.format(Locale.ROOT, "Meter clock %04d-%02d-%02d %02d:%02d:%02d, shift lamp %d rpm (status %d)\n",
                    g.year, g.month, g.day, g.hour, g.minute, g.second, g.shiftLampRpm(), g.shiftLampStatus));
        }
        if (service != null) {
            for (int k = 0; k < 3; k++) {
                BikeFrames.ServiceIndicator.Reminder r = service.reminders[k];
                if (!r.supported) continue;
                sb.append(BikeFrames.ServiceIndicator.NAMES[k]).append(String.format(Locale.ROOT,
                        ": %04d-%02d-%02d, %d km, notify %d\n", r.year, r.month, r.day, r.distanceKm, r.notify));
            }
        }
        if (infoConfig != null) {
            sb.append("Bike has:");
            for (BikeFrames.Flag f : BikeFrames.InfoConfig.ALL) if (infoConfig.supported(f)) sb.append(' ').append(f.name).append(',');
            sb.setLength(sb.length() - 1);
            sb.append('\n');
        }
        if (tuning != null) {
            sb.append("Settings it has:");
            for (BikeFrames.Flag f : BikeFrames.TuningConfig.ALL) if (tuning.supported(f)) sb.append(' ').append(f.name).append(',');
            sb.setLength(sb.length() - 1);
            sb.append('\n');
        }
        sb.append("Frames since connecting:\n");
        for (int id = 0; id < 256; id++) {
            if (count[id] == 0) continue;
            double secs = Math.max(1, (lastAt[id] - since) / 1000.0);
            sb.append(String.format(Locale.ROOT, "%02X %s ×%d (%.1f/s): %s\n", id, BikeFrames.name(id), count[id],
                    count[id] / secs, KawaProtocol.hex(last[id])));
        }
        return sb.toString().trim();
    }

    private static String ago(long at, long now) {
        long s = Math.max(0, (now - at) / 1000);
        return s < 60 ? s + " s" : s / 60 + " min";
    }

    private static String num(double d) {
        return Double.isNaN(d) ? "–" : String.format(Locale.ROOT, "%.1f", d);
    }
}
