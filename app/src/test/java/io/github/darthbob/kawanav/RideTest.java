package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Ride files: the sample format, the summary computed from the samples, and the CSV/GPX export. */
public class RideTest {

    static RideTsv.Sample sample(int second, double kmh, double rpm, double gear, double lean, double lat, double lon) {
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = second * 1000L;
        s.epochMs = 1_760_000_000_000L + second * 1000L;
        s.kmh = kmh;
        s.rpm = rpm;
        s.gear = gear;
        s.lean = lean;
        s.thrPct = 20;
        s.waterC = 80;
        s.odoKm = 12_000 + second / 1000.0;
        s.flags = RideTsv.FLAG_BIKE | (lat == lat ? RideTsv.FLAG_GPS : 0);
        s.lat = lat;
        s.lon = lon;
        s.gpsAccM = 5;
        return s;
    }

    static List<RideTsv.Sample> ride100() {
        List<RideTsv.Sample> out = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            out.add(sample(i, 50 + i % 20, 4000 + i, i < 30 ? 0 : 3, i % 40 - 20,
                    45.0 + i * 0.0002, 7.0 + i * 0.0003));
        }
        return out;
    }

    @Test
    public void sampleRoundTrip() {
        List<RideTsv.Sample> in = ride100();
        List<String> lines = new ArrayList<>(Arrays.asList(RideTsv.magic(), RideTsv.header()));
        for (RideTsv.Sample s : in) lines.add(s.line());
        List<RideTsv.Sample> out = RideTsv.parse(lines);
        assertEquals(in.size(), out.size());
        assertEquals(in.get(5).tMs, out.get(5).tMs);
        assertEquals(in.get(5).kmh, out.get(5).kmh, 1e-6);
        assertEquals(in.get(5).rpm, out.get(5).rpm, 1e-6);
        assertEquals(in.get(5).lat, out.get(5).lat, 1e-6);
        assertEquals(in.get(5).flags, out.get(5).flags);
        assertTrue(out.get(5).hasGps());
    }

    @Test
    public void missingValuesStayEmpty() {
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = 1000;
        s.epochMs = 1;
        String line = s.line();
        assertTrue(line.contains("\t\t"));   // empty cells, not zeros
        assertFalse(s.hasGps());
        assertEquals(1, RideTsv.parse(Arrays.asList(RideTsv.magic(), RideTsv.header(), line)).size());
    }

    @Test
    public void summaryFromSamples() {
        Ride r = new Ride();
        r.summarize(ride100());
        assertEquals(100, r.samples);
        assertEquals(99_000, r.activeMs); // 100 samples, one second apart
        assertTrue(r.hasBike);
        assertTrue(r.hasGps);
        assertEquals(69.0, r.maxKmh, 0.1);
        assertEquals(4099, r.maxRpm);
        assertEquals(-20.0, r.maxLeanL, 0.01);
        assertEquals(19.0, r.maxLeanR, 0.01);
        assertEquals(0.1, r.odoEndKm - r.odoStartKm, 0.02);      // 100 s of 0.001 km steps
        assertTrue(r.distBikeM > 90 && r.distBikeM < 110);
        assertTrue(r.distGpsM > 3000 && r.distGpsM < 3400);      // ~32 m per second of track
        assertEquals(45.0, r.bbox[0], 1e-9);                     // the track's south-west corner
        assertEquals(7.0, r.bbox[1], 1e-9);
        assertTrue(r.bbox[2] > r.bbox[0]);
    }

    @Test
    public void summaryLeavesPausesOut() {
        List<RideTsv.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            RideTsv.Sample s = sample(i, 50, 4000, 3, 0, 45.0, 7.0);
            if (i >= 10) s.epochMs += 600_000;   // ten minutes paused after the tenth second
            samples.add(s);
        }
        Ride r = new Ride();
        r.summarize(samples);
        assertEquals(19_000, r.activeMs);
    }

    /** The ride's average is distance over recorded time, as the recorder counts it while riding. */
    @Test
    public void averageIsDistanceOverTime() {
        List<RideTsv.Sample> samples = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            RideTsv.Sample s = sample(i, i < 50 ? 100 : 0, 4000, 3, 0, Double.NaN, Double.NaN);
            s.odoKm = 12_000 + Math.min(i, 50) * (100 / 3.6) / 1000;   // 100 km/h for 50 s, then standing
            samples.add(s);
        }
        Ride r = new Ride();
        r.summarize(samples);
        // ~1.39 km in 100 s: 50 km/h, not the 49.5 km/h mean of the speed samples
        assertEquals(50.0, r.avgKmh, 0.5);
    }

    /** The distance column is written and read back. */
    @Test
    public void distanceColumnRoundTrip() {
        RideTsv.Sample s = sample(5, 50, 4000, 3, 0, 45.0, 7.0);
        s.distM = 1234.5;
        List<RideTsv.Sample> out = RideTsv.parse(Arrays.asList(RideTsv.magic(), RideTsv.header(), s.line()));
        assertEquals(1234.5, out.get(0).distM, 0.01);
    }

    @Test
    public void summaryTwiceGivesTheSameFuel() {
        List<RideTsv.Sample> samples = ride100();
        for (int i = 0; i < samples.size(); i++) samples.get(i).fuelTotalL = 100 + i * 0.01;
        Ride r = new Ride();
        r.summarize(samples);
        assertEquals(0.99, r.fuelUsedL, 1e-6);
        r.summarize(samples);                    // at the end of a ride, after the summary written during it
        assertEquals(0.99, r.fuelUsedL, 1e-6);
    }

    @Test
    public void newColumnsRoundTrip() {
        // one value per group, so a swapped column name or order shows up
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = 1000;
        s.epochMs = 2_000_000;
        s.susp_f_stroke_mm = 12.5;
        s.susp_r_stroke_mm = 7.25;
        s.imu_ax_g = -0.42;
        s.imu_ay_psip1 = 3.5;
        s.wheel_f_kmh = 63.4;
        s.brake_f_master = 34;
        s.abs_target = 5;
        s.cruise_on = 1;
        s.torque_actual = 10;
        s.damping = 7;
        s.tcs_hb = 5;
        s.wheelie_deg = 45.02;
        java.util.List<RideTsv.Sample> back = RideTsv.parse(java.util.Arrays.asList(
                RideTsv.magic(), RideTsv.header(), s.line()));
        assertEquals(1, back.size());
        RideTsv.Sample r = back.get(0);
        assertEquals(12.5, r.susp_f_stroke_mm, 1e-9);
        assertEquals(7.25, r.susp_r_stroke_mm, 1e-9);
        assertEquals(-0.42, r.imu_ax_g, 1e-9);
        assertEquals(3.5, r.imu_ay_psip1, 1e-9);
        assertEquals(63.4, r.wheel_f_kmh, 1e-9);
        assertEquals(34, r.brake_f_master, 1e-9);
        assertEquals(5, r.abs_target, 1e-9);
        assertEquals(1, r.cruise_on, 1e-9);
        assertEquals(10, r.torque_actual, 1e-9);
        assertEquals(7, r.damping, 1e-9);
        assertEquals(5, r.tcs_hb, 1e-9);
        assertEquals(45.02, r.wheelie_deg, 1e-9);
        // what this sample has none of stays empty
        assertTrue(Double.isNaN(r.susp_r_stroke_vp));
        assertTrue(Double.isNaN(r.imu_az_g));
    }

    @Test
    public void aFileFromBeforeTheNewColumnsStillReads() {
        // a 0.7 file: same magic, header and row as then, without any of the 0.8 columns
        String header = "t_ms	epoch_ms	lat	lon	alt_m	gps_kmh	gps_acc_m	flags	kmh	rpm	gear"
                + "	thr_pct	acc_g	lean_deg	water_c	oil_c	intake_c	fuel_inst	fuel_rate	tcs	abs"
                + "	odo_km	dist_m	fuel_total_l	tyre_f_kpa	tyre_r_kpa	battery_v	outside_c	brake_f_bar";
        String row = "5000	1700000000000	47.1	9.5				3	62.50	4200	3	45.00	0.10	18.00"
                + "	85	95	30	4.8	1.00	1	0	12345	1500.00	123.45	210.00	251.00	11.72	18	12.70";
        java.util.List<RideTsv.Sample> back = RideTsv.parse(java.util.Arrays.asList(RideTsv.magic(), header, row));
        assertEquals(1, back.size());
        assertEquals(62.5, back.get(0).kmh, 1e-9);
        assertEquals(210.0, back.get(0).tyreFrontKpa, 1e-9);
        assertTrue(Double.isNaN(back.get(0).susp_f_stroke_mm));
        assertTrue(Double.isNaN(back.get(0).abs_target));
    }

    @Test
    public void theFullCsvCarriesEveryRecordedValue() {
        Ride r = new Ride();
        r.id = "test"; r.name = "Test ride"; r.start = 1_700_000_000_000L; r.activeMs = 3000;
        java.util.List<RideTsv.Sample> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RideTsv.Sample s = new RideTsv.Sample();
            s.tMs = i * 1000;
            s.epochMs = 1_700_000_000_000L + i * 1000;
            s.susp_f_stroke_mm = 12.5 + i;
            s.imu_ax_g = -0.4;
            s.wheel_f_kmh = 60 + i;
            s.abs_target = 5;
            samples.add(s);
        }
        String csv = RideExport.fullCsv(r, samples);
        String[] lines = csv.split("\n");
        // a comment, the header, one line per second - and the header is the ride file's own columns
        assertEquals(RideTsv.header(), lines[1]);
        assertEquals(lines[1].split("	", -1).length, lines[2].split("	", -1).length);
        assertEquals(3 + 2, lines.length);
        assertTrue(csv.startsWith("# "));
        // and it reads back as the same values
        java.util.List<RideTsv.Sample> back = RideTsv.parse(java.util.Arrays.asList(
                RideTsv.magic(), lines[1], lines[2], lines[3], lines[4]));
        assertEquals(3, back.size());
        assertEquals(12.5, back.get(0).susp_f_stroke_mm, 1e-9);
        assertEquals(5, back.get(2).abs_target, 1e-9);
    }

    @Test
    public void theRawExportIsTheRideFile() {
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = 1000;
        s.epochMs = 1_700_000_000_000L;
        s.kmh = 42;
        String raw = RideExport.rawTsv(java.util.Arrays.asList(s));
        String[] lines = raw.split("\n");
        assertEquals(RideTsv.magic(), lines[0]);
        assertEquals(RideTsv.header(), lines[1]);
        java.util.List<RideTsv.Sample> back = RideTsv.parse(java.util.Arrays.asList(lines));
        assertEquals(1, back.size());
        assertEquals(42, back.get(0).kmh, 1e-9);
    }

    @Test
    public void csvLayout() {
        Ride r = new Ride();
        r.name = "Morning ride";
        r.summarize(ride100());
        String csv = RideExport.csv(r, ride100(), "jacky", "");
        String[] lines = csv.split("\n");
        assertEquals("UserAccount Name,jacky", lines[0]);
        assertEquals("Title,Morning ride", lines[1]);
        assertTrue(lines[6].startsWith("R.Score"));
        assertTrue(lines[7].startsWith("\"elapsed_msec\",\"gps_latitude\""));
        assertEquals(14, lines[7].split(",", -1).length);
        String[] first = lines[8].split(",", -1);
        assertEquals("0", first[0]);
        assertEquals("45.000000", first[1]);
        assertEquals("80", first[4]);          // water temperature
        assertEquals("", first[5]);            // boost temperature: not on this bike
        assertEquals("4000", first[6]);
        assertEquals("50", first[7]);
        assertEquals("N", first[11]);          // first 30 samples: neutral
        assertEquals("", first[13]);           // rideology_score: server side
        assertTrue(RideExport.csvBytes(csv).length > 100);
    }

    @Test
    public void gpxTrack() {
        Ride r = new Ride();
        r.name = "Giro & prova <test>";
        r.summarize(ride100());
        String gpx = RideExport.gpx(r, ride100());
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(gpx.contains("<gpx version=\"1.1\" creator=\"KawaNav\""));
        assertTrue(gpx.contains("Giro &amp; prova &lt;test&gt;"));
        assertEquals(100, gpx.split("<trkpt").length - 1);
        assertTrue(gpx.contains("<kawanav:rpm>4000</kawanav:rpm>"));
        assertTrue(gpx.contains("<kawanav:gear>N</kawanav:gear>"));
        assertTrue(gpx.endsWith("</gpx>\n"));
    }

    @Test
    public void fileNameIsSafe() {
        Ride r = new Ride();
        r.id = "20261013-1840";
        r.name = "Giro: mattina / test";
        assertEquals("Giro_mattina_test.gpx", RideExport.fileName(r, "gpx"));
        r.name = "";
        assertEquals("20261013-1840.csv", RideExport.fileName(r, "csv"));
    }

    @Test
    public void distanceBetweenPoints() {
        assertEquals(111.3, Ride.distanceM(45.0, 7.0, 45.001, 7.0), 0.5);
        assertEquals(0, Ride.distanceM(45, 7, 45, 7), 1e-9);
    }
}
