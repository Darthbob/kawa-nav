package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** The totals and records the ride list shows. */
public class RideStatsTest {

    private static Ride ride(long start, double km, long minutes, double maxKmh, double leanL, double leanR, double fuelL) {
        Ride r = new Ride();
        r.id = "r" + start;
        r.start = start;
        r.activeMs = minutes * 60_000;
        r.distBikeM = km * 1000;
        r.maxKmh = maxKmh;
        r.maxLeanL = leanL;
        r.maxLeanR = leanR;
        r.fuelUsedL = fuelL;
        return r;
    }

    private static long at(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, 12, 0, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void bestOfEach() {
        List<Ride> rides = new ArrayList<>();
        // a lean to the left is negative, the way the recorder stores it
        rides.add(ride(at(2025, 5, 1), 100, 90, 140, -40, 45, 6));
        rides.add(ride(at(2026, 3, 2), 310, 240, 187, -52, 40, 18));
        rides.add(ride(at(2026, 9, 9), 60, 30, 120, -30, 35, 0));
        RideStats s = RideStats.of(rides);
        assertEquals(3, s.rides);
        assertEquals(470.0, s.totalKm, 1e-9);
        assertEquals(6.0, s.totalHours, 1e-9);
        assertTrue(s.fastest.known());
        assertEquals(187, s.fastest.value, 1e-9);
        assertEquals(at(2026, 3, 2), s.fastest.ride.start);
        assertEquals(52, s.maxLeanLeft.value, 1e-9);
        assertEquals(45, s.maxLeanRight.value, 1e-9);   // the 2025 ride leans further right
        assertEquals(310, s.longest.value, 1e-9);
    }

    @Test
    public void aLeftLeanCounts() {
        // the record used to compare a negative number against zero, so a left lean was never found
        List<Ride> rides = new ArrayList<>();
        rides.add(ride(at(2026, 1, 1), 10, 10, 50, -48, 12, 0));
        RideStats s = RideStats.of(rides);
        assertTrue(s.maxLeanLeft.known());
        assertEquals(48, s.maxLeanLeft.value, 1e-9);
        assertTrue(RideStats.summaryLine(rides.get(0)).contains("48"));
    }

    @Test
    public void theSummaryCountsTheLeftLeanToo() {
        Ride r = ride(at(2026, 1, 1), 10, 10, 50, -55, 20, 0);
        assertTrue(RideStats.summaryLine(r).contains("55"));
    }

    @Test
    public void theBikesOdometerWinsOverTheTrack() {
        Ride r = ride(at(2026, 1, 1), 100, 60, 100, 0, 0, 0);
        r.distGpsM = 90_000;                 // GPS says less than the bike's odometer
        assertEquals(100.0, RideStats.km(r), 1e-9);
    }

    /** A ride with the 0.9 numbers: simplest way is to summarise samples through it. */
    private static Ride summarised(Ride r, double... coolantAndAccel) {
        java.util.List<RideTsv.Sample> in = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RideTsv.Sample s = new RideTsv.Sample();
            s.tMs = i * 1000L;
            s.epochMs = r.start + i * 1000L;
            s.waterC = coolantAndAccel[0];
            s.accG = coolantAndAccel[1];
            s.kmh = 50;
            in.add(s);
        }
        r.summarize(in);
        return r;
    }

    @Test
    public void theRecordsTheMetricsKnow() {
        List<Ride> rides = new ArrayList<>();
        rides.add(summarised(ride(at(2026, 1, 1), 100, 60, 100, 0, 0, 0), 90, -0.2));
        rides.add(summarised(ride(at(2026, 2, 1), 50, 30, 90, 0, 0, 0), 101, -0.6));
        RideStats s = RideStats.of(rides);
        assertTrue(s.hottest.known());
        assertEquals(101, s.hottest.value, 1e-9);
        assertEquals(at(2026, 2, 1), s.hottest.ride.start);
        assertTrue(s.hardestBraking.known());
        assertEquals(0.6, s.hardestBraking.value, 1e-9);   // the magnitude of the hardest stop
        assertEquals(at(2026, 2, 1), s.hardestBraking.ride.start);
        assertFalse(s.mostSuspension.known());             // no bike on the fixture sends travel
    }

    @Test
    public void rideWithoutMetricsContributesNothing() {
        List<Ride> rides = new ArrayList<>();
        rides.add(ride(at(2026, 1, 1), 100, 60, 100, 0, 0, 0));      // never summarised: no metrics
        RideStats s = RideStats.of(rides);
        assertFalse(s.hottest.known());
        assertFalse(s.hardestBraking.known());
        assertEquals(0, s.fuelL, 1e-9);
    }

    @Test
    public void theLogsTotals() {
        Ride a = summarised(ride(at(2026, 1, 1), 100, 60, 100, -10, 12, 5), 90, -0.2);
        a.fuelUsedL = 5;   // summarise() recomputes it from the samples, which the fixture does not have
        a.tcsOnS = 12;
        a.absOnS = 3;
        a.wheelieS = 0;
        Ride b = summarised(ride(at(2026, 2, 1), 50, 30, 90, -5, 8, 0), 95, -0.1);
        b.tcsOnS = 0;                                        // recorded, never cut in
        b.absOnS = 0;
        b.wheelieS = 5;
        RideStats s = RideStats.of(java.util.Arrays.asList(a, b));
        assertEquals(5, s.fuelL, 1e-9);
        assertEquals(12, s.tcsSeconds, 1e-9);
        assertEquals(3, s.absSeconds, 1e-9);
        assertEquals(5, s.wheelieSeconds, 1e-9);
    }

    @Test
    public void nothingRecordedYet() {
        RideStats s = RideStats.of(new ArrayList<>());
        assertEquals(0, s.rides);
        assertFalse(s.fastest.known());
        assertTrue(RideStats.byYear(new ArrayList<>()).isEmpty());
    }

    @Test
    public void perYearNewestFirst() {
        List<Ride> rides = new ArrayList<>();
        rides.add(ride(at(2025, 6, 1), 100, 60, 100, 0, 0, 0));
        rides.add(ride(at(2026, 6, 1), 50, 30, 100, 0, 0, 0));
        rides.add(ride(at(2026, 7, 1), 70, 30, 100, 0, 0, 0));
        List<RideStats.Year> years = RideStats.byYear(rides);
        assertEquals(2, years.size());
        assertEquals(2026, years.get(0).year);
        assertEquals(120.0, years.get(0).km, 1e-9);
        assertEquals(2, years.get(0).rides);
        assertEquals(1.0, years.get(0).hours, 1e-9);
        assertEquals(2025, years.get(1).year);
        assertEquals(100.0, years.get(1).km, 1e-9);
    }

    @Test
    public void theLineAFinishedRideIsAnnouncedWith() {
        Ride r = ride(at(2026, 4, 4), 150, 100, 132, 44, 48, 9);
        String line = RideStats.summaryLine(r);
        assertTrue(line, line.contains("150.0 km"));
        assertTrue(line, line.contains("1 h 40"));
        assertTrue(line, line.contains("6.0 L/100 km"));
        assertTrue(line, line.contains("48"));
        assertTrue(line, line.contains("132 km/h"));
        assertTrue(RideStats.summaryLine(new Ride()).isEmpty());   // a ride with nothing in it says nothing
    }
}
