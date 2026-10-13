package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One recorded second of a ride, as a line of text: the columns are named in the header, so columns can
 * be added later without breaking old files, and a missing value is an empty cell. Pure Java (no
 * Android), so the format can be unit-tested; one file per ride, gzipped once the ride is finished.
 */
public final class RideTsv {
    private RideTsv() {}

    /** First line of the file: format version. */
    static final String MAGIC = "#kawanav-ride 1";

    /** Column names in the order they are written. */
    static final String[] COLUMNS = {
            "t_ms", "epoch_ms", "lat", "lon", "alt_m", "gps_kmh", "gps_acc_m", "flags",
            "kmh", "rpm", "gear", "thr_pct", "acc_g", "lean_deg", "water_c", "oil_c", "intake_c",
            "fuel_inst", "fuel_rate", "tcs", "abs", "odo_km", "dist_m", "fuel_total_l",
    };
    /** flags: the bike was connected while this second was recorded. */
    public static final int FLAG_BIKE = 1;
    /** flags: a fresh GPS fix was available. */
    public static final int FLAG_GPS = 2;

    /** One recorded second. Nothing is final: the recorder fills it in as the ride goes on. */
    public static final class Sample {
        public long tMs;          // since the ride started
        public long epochMs;
        public double lat = Double.NaN, lon = Double.NaN, altM = Double.NaN, gpsKmh = Double.NaN, gpsAccM = Double.NaN;
        public int flags;
        public double kmh = Double.NaN, rpm = Double.NaN, gear = Double.NaN, thrPct = Double.NaN, accG = Double.NaN,
                lean = Double.NaN, waterC = Double.NaN, oilC = Double.NaN, intakeC = Double.NaN,
                fuelInst = Double.NaN, fuelRate = Double.NaN, tcs = Double.NaN, abs = Double.NaN,
                odoKm = Double.NaN, distM = Double.NaN, fuelTotalL = Double.NaN;

        public boolean hasGps() {
            return (flags & FLAG_GPS) != 0 && lat == lat && lon == lon;
        }

        /** The line for the file: values in column order, empty cells for what is missing. */
        public String line() {
            StringBuilder sb = new StringBuilder(160);
            double[] numbers = {tMs, epochMs, lat, lon, altM, gpsKmh, gpsAccM, flags, kmh, rpm, gear, thrPct, accG,
                    lean, waterC, oilC, intakeC, fuelInst, fuelRate, tcs, abs, odoKm, distM, fuelTotalL};
            for (int i = 0; i < numbers.length; i++) {
                if (i > 0) sb.append('\t');
                sb.append(cell(numbers[i], COLUMNS[i]));
            }
            return sb.toString();
        }
    }

    /** Numbers are written with just enough decimals; the bike's integers without any. */
    private static String cell(double v, String column) {
        if (v != v) return "";
        switch (column) {
            case "t_ms": case "epoch_ms": case "flags": case "rpm": case "gear": case "water_c": case "oil_c":
            case "intake_c": case "tcs": case "abs":
                return String.valueOf(Math.round(v));
            case "lat": case "lon":
                return String.format(java.util.Locale.ROOT, "%.6f", v);
            default:
                return String.format(java.util.Locale.ROOT, "%.2f", v);
        }
    }

    public static String header() {
        return String.join("\t", COLUMNS);
    }

    /** The first line of a ride file. */
    public static String magic() {
        return MAGIC;
    }

    /** Reads a file's lines back into samples (used for the summary, the charts and the recovery). */
    public static List<Sample> parse(List<String> lines) {
        List<Sample> out = new ArrayList<>();
        if (lines.isEmpty() || !MAGIC.equals(lines.get(0).trim())) return out;
        Map<String, Integer> index = new LinkedHashMap<>();
        int start = 1;
        if (lines.size() > 1 && lines.get(1).startsWith("t_ms")) {
            String[] names = lines.get(1).split("\t", -1);
            for (int i = 0; i < names.length; i++) index.put(names[i], i);
            start = 2;
        } else {
            for (int i = 0; i < COLUMNS.length; i++) index.put(COLUMNS[i], i);
        }
        for (int l = start; l < lines.size(); l++) {
            String line = lines.get(l);
            if (line.isEmpty()) continue;
            String[] cells = line.split("\t", -1);
            Sample s = new Sample();
            s.tMs = (long) num(cells, index, "t_ms");
            s.epochMs = (long) num(cells, index, "epoch_ms");
            s.lat = num(cells, index, "lat");
            s.lon = num(cells, index, "lon");
            s.altM = num(cells, index, "alt_m");
            s.gpsKmh = num(cells, index, "gps_kmh");
            s.gpsAccM = num(cells, index, "gps_acc_m");
            s.flags = (int) num(cells, index, "flags");
            s.kmh = num(cells, index, "kmh");
            s.rpm = num(cells, index, "rpm");
            s.gear = num(cells, index, "gear");
            s.thrPct = num(cells, index, "thr_pct");
            s.accG = num(cells, index, "acc_g");
            s.lean = num(cells, index, "lean_deg");
            s.waterC = num(cells, index, "water_c");
            s.oilC = num(cells, index, "oil_c");
            s.intakeC = num(cells, index, "intake_c");
            s.fuelInst = num(cells, index, "fuel_inst");
            s.fuelRate = num(cells, index, "fuel_rate");
            s.tcs = num(cells, index, "tcs");
            s.abs = num(cells, index, "abs");
            s.odoKm = num(cells, index, "odo_km");
            s.distM = num(cells, index, "dist_m");
            s.fuelTotalL = num(cells, index, "fuel_total_l");
            out.add(s);
        }
        return out;
    }

    private static double num(String[] cells, Map<String, Integer> index, String column) {
        Integer i = index.get(column);
        if (i == null || i >= cells.length) return Double.NaN;
        String cell = cells[i].trim();
        if (cell.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(cell);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
