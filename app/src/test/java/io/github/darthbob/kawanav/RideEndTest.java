package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** When a ride ends after the link to the bike is lost: the rider's choice, and a dropout while moving. */
public class RideEndTest {

    private static final long MIN = 60_000;

    @Test
    public void standingTheChoiceDecides() {
        assertEquals(Hub.BIKE_OFF_MS, RideEnd.afterMs(0, 0));
        assertEquals(5 * MIN, RideEnd.afterMs(1, 0));
        assertEquals(15 * MIN, RideEnd.afterMs(2, 0));
        assertEquals(Hub.BIKE_OFF_MS, RideEnd.afterMs(0, 4.9));   // rolling to a stop is standing
    }

    @Test
    public void movingTheRideWaitsForADropoutToEnd() {
        assertEquals(5 * MIN, RideEnd.afterMs(0, 60));           // not "at once": the link only dropped
        assertEquals(5 * MIN, RideEnd.afterMs(1, 60));
        assertEquals(15 * MIN, RideEnd.afterMs(2, 5));            // a longer choice still holds
    }

    @Test
    public void noSpeedKnownCountsAsStanding() {
        assertFalse(RideEnd.moving(Double.NaN));
        assertEquals(Hub.BIKE_OFF_MS, RideEnd.afterMs(0, Double.NaN));
        assertTrue(RideEnd.moving(RideEnd.MOVING_KMH));
    }

    @Test
    public void anUnknownChoiceIsTheDefault() {
        assertEquals(RideEnd.afterMs(RideEnd.DEFAULT, 0), RideEnd.afterMs(-1, 0));
        assertEquals(RideEnd.afterMs(RideEnd.DEFAULT, 0), RideEnd.afterMs(RideEnd.LABELS.length, 0));
        assertEquals("At once", RideEnd.LABELS[RideEnd.DEFAULT]);
    }

    @Test
    public void everyChoiceHasItsTime() {
        long previous = 0;
        for (int i = 0; i < RideEnd.LABELS.length; i++) {
            long after = RideEnd.afterMs(i, 0);
            assertTrue(RideEnd.LABELS[i], after > previous);
            previous = after;
        }
    }
}
