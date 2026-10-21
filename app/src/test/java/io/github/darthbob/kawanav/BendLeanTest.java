package io.github.darthbob.kawanav;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The bends of a ride by their most lean, and the lean the rider usually takes a bend at. */
public class BendLeanTest {

    /** A ride at {@code kmh}, one second per lean (NaN: no lean that second). */
    private static Ride ride(double kmh, double... leans) {
        List<RideTsv.Sample> samples = new ArrayList<>();
        for (int i = 0; i < leans.length; i++) samples.add(RideTest.sample(i, kmh, 5000, 3, leans[i], Double.NaN, Double.NaN));
        Ride r = new Ride();
        r.summarize(samples);
        return r;
    }

    private static int[] bends(int... peaks) {
        int[] b = new int[Ride.Bends.MAX_PEAK + 1];
        for (int p : peaks) b[p]++;
        return b;
    }

    // ------------------------------------------------------------------ one ride

    @Test
    public void eachBendCountsWithItsMostLean() {
        Ride r = ride(60, 0, 0, 12, 20, 25, 18, 5, 0, -11, -30, -28, -9, 0);
        assertArrayEquals(bends(25, 30), r.bendLean);
    }

    @Test
    public void leaningTheOtherWayStartsAnotherBend() {
        // an S-bend: from right to left without straightening up
        assertArrayEquals(bends(25, 22), ride(60, 20, 25, -22, -18, 0).bendLean);
    }

    @Test
    public void sweepsManoeuvringAndOddReadingsAreNoBends() {
        assertNull(ride(60, 0, 11, 13, 12, 0).bendLean);   // never 15°: a sweep
        assertNull(ride(20, 0, 25, 30, 25, 0).bendLean);   // slower than 30 km/h
        assertNull(ride(60, 0, 70, 72, 0).bendLean);       // beyond 60°: not a lean
        assertNull(ride(60, 0, 0, 0).bendLean);
    }

    @Test
    public void aMissingLeanEndsTheBend() {
        assertArrayEquals(bends(20, 24), ride(60, 18, 20, Double.NaN, 24, 16, 0).bendLean);
    }

    @Test
    public void theGpsSpeedServesWithoutTheBikes() {
        List<RideTsv.Sample> samples = new ArrayList<>();
        double[] leans = {0, 18, 26, 14, 0};
        for (int i = 0; i < leans.length; i++) {
            RideTsv.Sample s = RideTest.sample(i, Double.NaN, Double.NaN, Double.NaN, leans[i], Double.NaN, Double.NaN);
            s.gpsKmh = 55;
            samples.add(s);
        }
        Ride r = new Ride();
        r.summarize(samples);
        assertArrayEquals(bends(26), r.bendLean);
    }

    // ------------------------------------------------------------------ the rides together

    private static Ride withBends(int... peaks) {
        Ride r = new Ride();
        r.bendLean = bends(peaks);
        return r;
    }

    private static int[] many(int count, int peak) {
        int[] p = new int[count];
        java.util.Arrays.fill(p, peak);
        return p;
    }

    @Test
    public void theUsualLeanIsTheMiddleBend() {
        List<Ride> rides = new ArrayList<>();
        rides.add(withBends(many(20, 20)));
        rides.add(withBends(many(15, 30)));
        RideStats.BendLean l = RideStats.usualBendLean(rides);
        assertTrue(l.known());
        assertEquals(20, l.degrees, 0);
        assertEquals(35, l.bends);
        assertEquals(2, l.rides);
        rides.add(withBends(many(10, 30)));
        assertEquals(30, RideStats.usualBendLean(rides).degrees, 0);
    }

    @Test
    public void tooFewBendsGiveNoLeanYet() {
        List<Ride> rides = new ArrayList<>();
        rides.add(withBends(many(RideStats.MIN_BENDS - 1, 25)));
        RideStats.BendLean l = RideStats.usualBendLean(rides);
        assertFalse(l.known());
        assertEquals(RideStats.MIN_BENDS - 1, l.bends);
        assertFalse(RideStats.usualBendLean(new ArrayList<>()).known());
    }

    @Test
    public void onlyTheLatestRidesWithBendsCount() {
        List<Ride> rides = new ArrayList<>();
        Ride recording = withBends(many(50, 40));
        recording.state = "recording";
        rides.add(recording);                        // still being recorded: not yet
        rides.add(new Ride());                       // no lean recorded: skipped, not counted
        for (int i = 0; i < RideStats.BEND_RIDES; i++) rides.add(withBends(30, 30));
        for (int i = 0; i < 5; i++) rides.add(withBends(many(20, 18))); // older
        RideStats.BendLean l = RideStats.usualBendLean(rides);
        assertEquals(30, l.degrees, 0);
        assertEquals(RideStats.BEND_RIDES, l.rides);
        assertEquals(2 * RideStats.BEND_RIDES, l.bends);
    }
}
