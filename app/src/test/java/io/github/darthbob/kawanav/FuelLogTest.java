package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The refuel detector: what the dash sends at the pump, and what the log says afterwards. */
public class FuelLogTest {
    private static final double TANK = 15.0;

    @Test
    public void firstReadingOnlySetsTheBaseline() {
        FuelLog.Detector d = new FuelLog.Detector();
        assertNull(d.offer(3, 120, 12345, 1000, TANK));
    }

    @Test
    public void gaugeJumpIsARefuel() {
        FuelLog.Detector d = new FuelLog.Detector();
        d.offer(1, 40, 12345, 1000, TANK);          // arriving at the pump, nearly empty
        FuelLog.Entry e = d.offer(5, 40, 12345, 2000, TANK);
        assertNotNull(e);
        assertEquals(12345, e.odoKm, 1e-9);
        assertEquals(4 / 6.0 * TANK, e.litres, 1e-9); // four bars back on a six-bar gauge
        assertTrue(e.estimated);
    }

    @Test
    public void rangeJumpIsARefuelWhenTheGaugeBarelyMoves() {
        FuelLog.Detector d = new FuelLog.Detector();
        d.offer(3, 60, 1000, 1000, TANK);
        FuelLog.Entry e = d.offer(3, 200, 1000, 2000, TANK); // same bar, but the range says it was filled
        assertNotNull(e);
        assertEquals((200 - 60) / 200.0 * TANK, e.litres, 1e-9);
    }

    @Test
    public void smallMovementsAreNotRefuels() {
        FuelLog.Detector d = new FuelLog.Detector();
        d.offer(3, 100, 1000, 1000, TANK);
        assertNull(d.offer(3, 105, 1000, 2000, TANK));  // the range drifts as the tank settles
        assertNull(d.offer(3, 95, 1000, 3000, TANK));   // and falls while standing with the engine running
    }

    @Test
    public void valuesTheBikeDoesNotHaveAreIgnored() {
        FuelLog.Detector d = new FuelLog.Detector();
        d.offer(Double.NaN, -1, -1, 1000, TANK);
        assertNull(d.offer(4, 200, 1000, 2000, TANK)); // nothing to compare with: this is the baseline
        FuelLog.Entry e = d.offer(6, 260, 1010, 3000, TANK);
        assertNotNull(e);
        assertEquals(1010, e.odoKm, 1e-9);             // the odometer of the reading that showed the fill
    }

    @Test
    public void anEmptyGaugeDoesNotCountAsAJump() {
        FuelLog.Detector d = new FuelLog.Detector();
        d.offer(-1, -1, 1000, 1000, TANK);
        assertNull(d.offer(6, 300, 1000, 2000, TANK)); // the dash waking up, not a refuel
    }

    @Test
    public void withoutATankSizeTheLitresStayUnknown() {
        FuelLog.Detector d = new FuelLog.Detector();
        d.offer(1, 40, 1000, 1000, 0);
        FuelLog.Entry e = d.offer(5, 40, 1000, 2000, 0);
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
        java.util.List<FuelLog.Entry> out = FuelLog.sorted(java.util.Arrays.asList(a, b));
        assertEquals(100, out.get(0).at);
        assertEquals(200, out.get(1).at);
    }
}
