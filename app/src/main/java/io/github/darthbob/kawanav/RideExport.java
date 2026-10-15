package io.github.darthbob.kawanav;

import java.util.List;
import java.util.Locale;

/**
 * Turns a recorded ride into a file: a CSV with Rideology's column layout (so the ride can be loaded
 * into the tools people already use for Rideology exports) and a GPX track with the bike's values as
 * extensions. Pure Java, so the output can be unit-tested.
 */
public final class RideExport {
    private RideExport() {}

    /**
     * Rideology's CSV for a BLE5 engine bike: seven metadata rows, then the quoted header, then one row
     * per recorded second (sf/c.java). Rideology writes it as Shift-JIS with LF line endings; KawaNav
     * writes the same layout, with a real value in every column it has.
     */
    public static String csv(Ride ride, List<RideTsv.Sample> samples, String userName, String comment) {
        StringBuilder sb = new StringBuilder(samples.size() * 120);
        double km = (ride.distBikeM > 0 ? ride.distBikeM : ride.distGpsM) / 1000;
        sb.append("UserAccount Name,").append(cell(userName)).append('\n');
        sb.append("Title,").append(cell(ride.name)).append('\n');
        sb.append("Comment,").append(cell(comment)).append('\n');
        sb.append("Vehicle Nickname,").append(cell(ride.hasBike ? "KawaNav" : "")).append('\n');
        sb.append(String.format(Locale.ROOT, "Distance(km),%.1f%n", km));
        sb.append(String.format(Locale.ROOT, "Time(min),%d%n", Math.round(ride.activeMs / 60_000.0)));
        sb.append("R.Score,\n");
        sb.append("\"elapsed_msec\",\"gps_latitude\",\"gps_longitude\",\"instant_fuel_consumption\",")
                .append("\"water_temperature\",\"boost_temperature\",\"engine_RPM\",\"wheel_speed\",\"acceleration\",")
                .append("\"throttle_position\",\"boost_pressure\",\"gear_position\",\"lean_angle\",\"rideology_score\"\n");
        for (RideTsv.Sample s : samples) {
            sb.append(s.tMs).append(',');
            sb.append(num(s.lat, "%.6f")).append(',');
            sb.append(num(s.lon, "%.6f")).append(',');
            sb.append(num(s.fuelInst, "%.1f")).append(',');
            sb.append(num(s.waterC, "%.0f")).append(',');
            sb.append(','); // boost temperature: this bike has none
            sb.append(num(s.rpm, "%.0f")).append(',');
            sb.append(num(s.kmh, "%.0f")).append(',');
            sb.append(num(s.accG, "%.2f")).append(',');
            sb.append(num(s.thrPct, "%.0f")).append(',');
            sb.append(','); // boost pressure: this bike has none
            sb.append(gear(s.gear)).append(',');
            sb.append(num(s.lean, "%.1f")).append(',');
            sb.append('\n'); // rideology_score: computed by Kawasaki's cloud, not here
        }
        return sb.toString();
    }

    /** "N" for neutral, 1-6, empty when unknown. */
    private static String gear(double g) {
        if (g != g) return "";
        int gear = (int) Math.round(g);
        return gear == 0 ? "N" : gear >= 1 && gear <= 6 ? String.valueOf(gear) : "Error";
    }

    private static String num(double v, String fmt) {
        return v != v ? "" : String.format(Locale.ROOT, fmt, v);
    }

    private static String cell(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.contains(",") || s.contains("\"") ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    /** The CSV as bytes in the encoding Rideology uses (Shift-JIS, falling back to UTF-8). */
    public static byte[] csvBytes(String csv) {
        try {
            return csv.getBytes(java.nio.charset.Charset.forName("Shift_JIS"));
        } catch (Exception e) {
            return csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * A GPX 1.1 track with every value recorded as an extension (speed, rpm, gear, lean, temperatures,
     * tyres, battery, traction control, ABS…): standard GPX, map tools read the track and skip the rest.
     */
    public static String gpx(Ride ride, List<RideTsv.Sample> samples) {
        StringBuilder sb = new StringBuilder(samples.size() * 120 + 400);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"KawaNav\" xmlns=\"http://www.topografix.com/GPX/1/1\" ")
                .append("xmlns:kawanav=\"https://github.com/Darthbob/kawa-nav\">\n");
        sb.append("  <metadata><name>").append(xml(ride.name)).append("</name>")
                .append("<time>").append(iso(ride.start)).append("</time></metadata>\n");
        sb.append("  <trk><name>").append(xml(ride.name)).append("</name><trkseg>\n");
        for (RideTsv.Sample s : samples) {
            if (!s.hasGps()) continue;
            sb.append("    <trkpt lat=\"").append(String.format(Locale.ROOT, "%.6f", s.lat))
                    .append("\" lon=\"").append(String.format(Locale.ROOT, "%.6f", s.lon)).append("\">");
            if (s.altM == s.altM) sb.append("<ele>").append(String.format(Locale.ROOT, "%.1f", s.altM)).append("</ele>");
            sb.append("<time>").append(iso(s.epochMs)).append("</time>");
            StringBuilder ext = new StringBuilder();
            ext(ext, "speed", s.kmh, "%.0f");
            ext(ext, "rpm", s.rpm, "%.0f");
            if (s.gear == s.gear) ext.append("<kawanav:gear>").append(gear(s.gear)).append("</kawanav:gear>");
            ext(ext, "lean", s.lean, "%.1f");
            ext(ext, "throttle", s.thrPct, "%.0f");
            ext(ext, "acceleration", s.accG, "%.2f");
            ext(ext, "coolant", s.waterC, "%.0f");
            ext(ext, "oil", s.oilC, "%.0f");
            ext(ext, "intake", s.intakeC, "%.0f");
            ext(ext, "fuelEconomy", s.fuelInst, "%.1f");
            ext(ext, "fuelRate", s.fuelRate, "%.2f");
            ext(ext, "tyreFront", s.tyreFrontKpa / 100, "%.2f");
            ext(ext, "tyreRear", s.tyreRearKpa / 100, "%.2f");
            ext(ext, "battery", s.batteryV, "%.1f");
            ext(ext, "outside", s.outsideC, "%.0f");
            ext(ext, "brakeFront", s.brakeFrontBar, "%.1f");
            ext(ext, "tcs", s.tcs, "%.0f");
            ext(ext, "abs", s.abs, "%.0f");
            ext(ext, "suspFrontMm", s.susp_f_stroke_mm, "%.1f");
            ext(ext, "suspRearMm", s.susp_r_stroke_mm, "%.1f");
            ext(ext, "ax", s.imu_ax_g, "%.2f");
            ext(ext, "ay", s.imu_ay_g, "%.2f");
            ext(ext, "az", s.imu_az_g, "%.2f");
            ext(ext, "wheelFront", s.wheel_f_kmh, "%.1f");
            ext(ext, "wheelRear", s.wheel_r_kmh, "%.1f");
            ext(ext, "torqueRequest", s.torque_req, "%.0f");
            ext(ext, "torqueActual", s.torque_actual, "%.0f");
            ext(ext, "brakeFrontMaster", s.brake_f_master, "%.0f");
            ext(ext, "brakeRearCaliper", s.brake_r_caliper, "%.0f");
            ext(ext, "absTarget", s.abs_target, "%.0f");
            ext(ext, "damping", s.damping, "%.0f");
            ext(ext, "cruise", s.cruise_on, "%.0f");
            if (ext.length() > 0) {
                sb.append("<extensions><kawanav:data>").append(ext).append("</kawanav:data></extensions>");
            }
            sb.append("</trkpt>\n");
        }
        sb.append("  </trkseg></trk>\n</gpx>\n");
        return sb.toString();
    }

    /** One value as a GPX extension element (bar for tyres, V, °C, g…), nothing when the ride has none. */
    private static void ext(StringBuilder sb, String name, double v, String fmt) {
        if (v != v) return;
        sb.append("<kawanav:").append(name).append('>').append(String.format(Locale.ROOT, fmt, v))
                .append("</kawanav:").append(name).append('>');
    }

    /** ISO-8601 in UTC, as GPX wants it. */
    static String iso(long epochMs) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(epochMs));
    }

    private static String xml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A file name that is safe on every phone and in every file manager. */
    public static String fileName(Ride ride, String extension) {
        String base = ride.name == null || ride.name.isEmpty() ? ride.id : ride.name;
        return base.replaceAll("[^A-Za-z0-9._-]+", "_") + "." + extension;
    }
}
