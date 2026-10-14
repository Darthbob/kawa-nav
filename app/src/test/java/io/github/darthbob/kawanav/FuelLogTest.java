package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * The refuel detector: what the dash sends at the pump, and what the log says afterwards. The gauge is
 * watched once a second; a fill is only read from where it has stopped moving, and only once per stop.
 */
public class FuelLogTest {
    private static final double TANK = 15.0;

    /** One look at the dash, a second after the last. */
    private static FuelLog.Entry offer(FuelLog.Detector d, double bars, int range, int odo, long at) {
        return d.offer(bars, range, odo, at, TANK);
    }

    /**
     * The gauge standing still: the reading holds from {@code at} until it is acted on. Returns the first
     * refuel the detector reports while it holds, or null if it stays quiet past the dwell.
     */
    private static FuelLog.Entry holds(FuelLog.Detector d, double bars, int range, int odo, long at) {
        FuelLog.Entry found = null;
        for (int i = 0; i <= FuelLog.DWELL_MS / 1000 + 2; i++) {
            FuelLog.Entry e = offer(d, bars, range, odo, at + i * 1000L);
            if (e != null && found == null) found = e;
        }
        return found;
    }

    @Test
    public void firstReadingOnlySetsTheBaseline() {
        FuelLog.Detector d = new FuelLog.Detector();
        assertNull(offer(d, 3, 120, 12345, 1000));
    }

    @Test
    public void gaugeJumpIsARefuel() {
        FuelLog.Detector d = new FuelLog.Detector();
        offer(d, 1, 40, 12345, 1000);
        FuelLog.Entry e = holds(d, 5, 40, 12345, 2000);
        assertNotNull(e);
        assertEquals(12345, e.odoKm, 1e-9);
        assertEquals(4 / 6.0 * TANK, e.litres, 1e-9); // four bars back on a six-bar gauge
        assertTrue(e.estimated);
    }

    @Test
    public void nothingIsLoggedWhileTheGaugeIsStillMoving() {
        FuelLog.Detector d = new FuelLog.Detector();
        offer(d, 1, 40, 12345, 1000);
        // the pump is running: a bar every few seconds, never still for long enough to be a level
        for (int i = 1; i <= 5; i++) {
            assertNull(offer(d, 1 + i, 40 + i * 50, 12345, 1000 + i * 5000L));
        }
    }

    @Test
    public void aGaugeThatClimbsInStepsIsOneFill() {
        FuelLog.Detector d = new FuelLog.Detector();
        offer(d, 1, 40, 12345, 1000);
        for (int i = 1; i <= 3; i++) assertNull(offer(d, 1 + i, 40 + i * 50, 12345, 1000 + i * 5000L));
        FuelLog.Entry e = holds(d, 5, 240, 12345, 21_000); // the pump stops at five bars
        assertNotNull(e);
        assertEquals(1, e.barsBefore, 1e-9);            // from the level before the climb
        assertEquals(5, e.barsAfter, 1e-9);
        assertEquals(4 / 6.0 * TANK, e.litres, 1e-9);
    }

    @Test
    public void oneFillIsLoggedOnce() {
        FuelLog.Detector d = new FuelLog.Detector();
        offer(d, 1, 40, 12345, 1000);
        assertNotNull(holds(d, 5, 240, 12345, 2000));
        // the same full tank, looked at a minute and an hour later, is not a second refuel
        assertNull(holds(d, 5, 240, 12345, 60_000));
        assertNull(holds(d, 5, 240, 12345, 30 * 60_000));
    }

    @Test
    public void aRealSecondFillIsLoggedOnceTheBikeHasMoved() {
        FuelLog.Detector d = new FuelLog.Detector();
        offer(d, 1, 40, 12345, 1000);
        assertNotNull(holds(d, 5, 240, 12345, 2000));
        // a hundred kilometres later the tank is filled again: a fill of its own
        FuelLog.Entry e = holds(d, 6, 250, 12445, 60 * 60_000);
        assertNotNull(e);
        assertEquals(12445, e.odoKm, 1e-9);
    }

    @Test
    public void rangeJumpIsARefuelWhenTheGaugeBarelyMoves() {
        FuelLog.Detector d = new FuelLog.Detector();
        holds(d, 3, 60, 1000, 1000);
        FuelLog.Entry e = holds(d, 3, 200, 1000, 40_000); // same bar, but the range says it was filled
        assertNotNull(e);
        assertEquals((200 - 60) / 200.0 * TANK, e.litres, 1e-9);
    }

    @Test
    public void apartTopUpIsNotReadAsAFullTank() {
        FuelLog.Detector d = new FuelLog.Detector();
        holds(d, 3, 300, 1000, 1000);     // a full tank: 300 km of range
        holds(d, 1, 120, 1200, 40_000);
        // 40 km of range came back on the same bar: the range of a full tank is what the litres scale by
        FuelLog.Entry e = holds(d, 1, 160, 1200, 80_000);
        assertNotNull(e);
        assertEquals(40 / 300.0 * TANK, e.litres, 1e-9);
    }

    @Test
    public void smallMovementsAreNotRefuels() {
        FuelLog.Detector d = new FuelLog.Detector();
        holds(d, 3, 100, 1000, 1000);
        assertNull(holds(d, 3, 105, 1000, 40_000));  // the range drifts as the tank settles
        assertNull(holds(d, 3, 95, 1000, 80_000));   // and falls while standing with the engine running
    }

    @Test
    public void valuesTheBikeDoesNotHaveAreIgnored() {
        FuelLog.Detector d = new FuelLog.Detector();
        offer(d, Double.NaN, -1, -1, 1000);
        offer(d, Double.NaN, -1, -1, 2000);
        assertNull(holds(d, 4, 200, 1000, 3000));    // nothing to compare with: this is the baseline
        FuelLog.Entry e = holds(d, 6, 260, 1010, 40_000);
        assertNotNull(e);
        assertEquals(1010, e.odoKm, 1e-9);           // the odometer of the reading that showed the fill
    }

    @Test
    public void anEmptyGaugeDoesNotCountAsAJump() {
        FuelLog.Detector d = new FuelLog.Detector();
        offer(d, -1, -1, 1000, 1000);
        offer(d, -1, -1, 1000, 2000);
        assertNull(holds(d, 6, 300, 1000, 3000));    // the dash waking up, not a refuel
    }

    @Test
    public void withoutATankSizeTheLitresStayUnknown() {
        FuelLog.Detector d = new FuelLog.Detector();
        d.offer(1, 40, 1000, 1000, 0);
        d.offer(5, 40, 1000, 2000, 0);
        FuelLog.Entry e = d.offer(5, 40, 1000, 30_000, 0);
        assertNotNull(e);
        assertTrue(Double.isNaN(e.litres));
        assertTrue(!e.estimated);
    }

    @Test
    public void consumptionBetweenTwoRefuels() {
        FuelLog.Entry a = new FuelLog.Entry();
        a.odoKm = 10000;
        FuelLog.Entry b = new FuelLog.Entry();
        b.odoKm = 10300;
        b.litres = 15;
        assertEquals(5.0, FuelLog.consumptionLPer100(a, b), 1e-9);
        b.litres = Double.NaN;                        // the rider has not said how much went in
        assertTrue(Double.isNaN(FuelLog.consumptionLPer100(a, b)));
        b.litres = 15;
        b.odoKm = 9900;                               // a log that was edited into the wrong order
        assertTrue(Double.isNaN(FuelLog.consumptionLPer100(a, b)));
    }

    @Test
    public void entriesAreSortedOldestFirst() {
        FuelLog.Entry a = new FuelLog.Entry();
        a.at = 200;
        FuelLog.Entry b = new FuelLog.Entry();
        b.at = 100;
        List<FuelLog.Entry> out = FuelLog.sorted(java.util.Arrays.asList(a, b));
        assertEquals(100, out.get(0).at);
        assertEquals(200, out.get(1).at);
    }
}
