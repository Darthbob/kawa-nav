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
