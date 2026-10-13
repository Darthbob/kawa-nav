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
