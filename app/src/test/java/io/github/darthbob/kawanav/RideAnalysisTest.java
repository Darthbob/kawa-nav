package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The bends of a ride and the grip it used, from made-up seconds. */
public class RideAnalysisTest {

    /** Seconds of a ride: each row is speed km/h, lean °, throttle %, gear. */
    private static List<RideTsv.Sample> ride(double[]... rows) {
        List<RideTsv.Sample> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            RideTsv.Sample s = new RideTsv.Sample();
            s.tMs = i * 1000L;
            s.kmh = rows[i][0];
            s.lean = rows[i][1];
            s.thrPct = rows[i][2];
            s.gear = rows[i][3];
            out.add(s);
        }
        return out;
    }

    @Test
    public void aBendFromTheFirstToTheLastSecondLeantOver() {
        RideAnalysis a = RideAnalysis.of(ride(
                new double[]{60, 5, 20, 4},
                new double[]{58, 12, 10, 4},   // in
                new double[]{50, 25, 0, 4},
                new double[]{46, 30, 12, 4},   // apex
                new double[]{50, 18, 30, 4},
                new double[]{55, 8, 35, 4}));
        assertEquals(1, a.bends.size());
        RideAnalysis.Bend b = a.bends.get(0);
        assertEquals(1, b.start);
        assertEquals(4, b.end);
        assertEquals(3, b.apex);
        assertEquals(1, b.side);
        assertEquals(30, b.lean, 1e-9);
        assertEquals(46, b.apexKmh, 1e-9);
        assertEquals(58, b.entryKmh, 1e-9);
        assertEquals(50, b.exitKmh, 1e-9);
        assertEquals(46, b.minKmh, 1e-9);
        assertEquals(-8, b.gain(), 1e-9);
        assertEquals(12, b.apexThrottle, 1e-9);
        assertEquals(RideAnalysis.radius(46, 30), b.radiusM, 1e-9);
        assertEquals(1, a.count(1));
        assertEquals(0, a.count(-1));
    }

    @Test
    public void theRadiusOfASteadyTurn() {
        // 36 km/h is 10 m/s; at 45° a steady turn has v² / g
        assertEquals(100 / 9.81, RideAnalysis.radius(36, 45), 1e-9);
        assertEquals("either side", RideAnalysis.radius(36, -45), RideAnalysis.radius(36, 45), 1e-9);
        assertEquals("a lean under 5° counts as 5°", RideAnalysis.radius(36, 5), RideAnalysis.radius(36, 1), 1e-9);
    }

    @Test
    public void notABend() {
        // one second leant over, a crawl, a lean no one rides
        assertTrue(RideAnalysis.of(ride(new double[]{50, 0, 10, 3}, new double[]{50, 20, 10, 3},
                new double[]{50, 0, 10, 3})).bends.isEmpty());
        assertTrue(RideAnalysis.of(ride(new double[]{12, 20, 0, 1}, new double[]{10, 25, 0, 1},
                new double[]{12, 15, 5, 1})).bends.isEmpty());
        assertTrue(RideAnalysis.of(ride(new double[]{50, 30, 0, 3}, new double[]{40, 75, 0, 3},
                new double[]{0, 80, 0, 3})).bends.isEmpty());
    }

    @Test
    public void aChangeOfSideOrAGapStartsAnotherBend() {
        RideAnalysis a = RideAnalysis.of(ride(
                new double[]{50, -15, 10, 3},
                new double[]{50, -20, 10, 3},
                new double[]{50, 20, 10, 3},
                new double[]{50, 25, 10, 3}));
        assertEquals(2, a.bends.size());
        assertEquals(-1, a.bends.get(0).side);
        assertEquals(1, a.bends.get(1).side);
        assertEquals(1, a.count(-1));

        List<RideTsv.Sample> gap = ride(new double[]{50, 20, 10, 3}, new double[]{50, 22, 10, 3},
                new double[]{50, 24, 10, 3}, new double[]{50, 26, 10, 3});
        for (int i = 2; i < gap.size(); i++) gap.get(i).tMs += 10_000;   // ten seconds not recorded
        assertEquals(2, RideAnalysis.of(gap).bends.size());
    }

    @Test
    public void brakingAtTheApexASnappedThrottleAndADownshift() {
        RideAnalysis a = RideAnalysis.of(ride(
                new double[]{70, 5, 0, 4},
                new double[]{66, 15, 0, 4},
                new double[]{62, 24, 0, 3},    // apex: throttle shut, 66 → 58 km/h over two seconds
                new double[]{58, 22, 2, 3},
                new double[]{60, 20, 30, 3},   // 2 % to 30 % in a second, still leant 20°
                new double[]{64, 4, 35, 3}));
        RideAnalysis.Bend b = a.bends.get(0);
        assertEquals(2, b.apex);
        assertTrue(b.brakingAtApex);
        assertTrue(b.snapThrottle);
        assertEquals(1, b.downshifts);
        assertEquals(1, a.brakingAtApex());
        assertEquals(1, a.snapThrottle());
        assertEquals(1, a.downshifting());

        RideAnalysis smooth = RideAnalysis.of(ride(
                new double[]{50, 15, 8, 3},
                new double[]{50, 25, 10, 3},
                new double[]{51, 25, 14, 3},
                new double[]{53, 15, 20, 3}));
        RideAnalysis.Bend s = smooth.bends.get(0);
        assertFalse(s.brakingAtApex);
        assertFalse(s.snapThrottle);
        assertEquals(0, s.downshifts);
    }

    @Test
    public void theGripUsedEachSecond() {
        RideAnalysis a = RideAnalysis.of(ride(
                new double[]{80, 0, 0, 4},
                new double[]{70, 0, 0, 4},     // 80 → 60 km/h over two seconds: braking 0.28 g
                new double[]{60, 45, 0, 4},    // leant 45°: 1 g sideways, still slowing
                new double[]{60, -30, 40, 4},
                new double[]{70, 0, 60, 4},
                new double[]{75, 0, 60, 4}));
        // the first and last seconds have no neighbour on one side
        assertEquals(4, a.sideG.length);
        assertEquals(-20 / 3.6 / 2 / 9.81, a.forwardG[0], 1e-6);
        assertEquals(1, a.sideG[1], 1e-6);
        assertEquals(-Math.tan(Math.toRadians(30)), a.sideG[2], 1e-6);
        assertEquals(20 / 3.6 / 2 / 9.81, a.maxBrakeG, 1e-6);
        assertEquals(15 / 3.6 / 2 / 9.81, a.maxAccelG, 1e-6);
        assertEquals(1, a.maxSideG, 1e-6);
    }

    @Test
    public void theBendsInTheOrderAsked() {
        RideAnalysis a = RideAnalysis.of(ride(
                new double[]{40, 20, 10, 3}, new double[]{30, 35, 10, 3}, new double[]{50, 0, 10, 3},
                new double[]{80, -15, 10, 4}, new double[]{90, -20, 10, 4}, new double[]{60, 0, 10, 4}));
        assertEquals(2, a.bends.size());
        assertEquals(35, a.top(0, 5).get(0).lean, 1e-9);           // most lean
        assertEquals(-1, a.top(1, 5).get(0).side);                 // fastest: the left one at 90
        assertEquals(1, a.top(2, 1).size());
        assertEquals(10, a.top(2, 1).get(0).gain(), 1e-9);         // best exit: 80 → 90
        assertEquals(-10, a.top(3, 1).get(0).gain(), 1e-9);        // most speed lost: 40 → 30
        assertEquals(0, a.top(4, 5).get(0).start);                 // in ride order
        assertEquals(27.5, a.median(b -> b.lean), 1e-9);
        assertTrue(Double.isNaN(RideAnalysis.of(ride(new double[]{50, 0, 0, 3})).median(b -> b.lean)));
    }
}
