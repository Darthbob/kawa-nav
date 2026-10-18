package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The phone battery percentage sent to the dash: readings Android cannot give, and impossible drops. */
public class PhoneBatteryTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void aPlausibleReadingIsShown() {
        PhoneBattery b = new PhoneBattery();
        assertEquals(-1, b.percent());                 // nothing yet: the dash shows n/a
        assertTrue(b.accept(64, NOW));
        assertEquals(64, b.percent());
        assertTrue(b.accept(62, NOW + 5_000));         // a normal drop
        assertEquals(62, b.percent());
        assertTrue(b.accept(63, NOW + 10_000));        // a normal rise
        assertEquals(63, b.percent());
    }

    @Test
    public void aReadingAndroidCannotGiveKeepsTheLastOne() {
        PhoneBattery b = new PhoneBattery();
        b.accept(64, NOW);
        assertFalse(b.accept(0, NOW + 5_000));         // the service did not answer
        assertEquals(64, b.percent());
        assertFalse(b.accept(-1, NOW + 10_000));
        assertFalse(b.accept(101, NOW + 15_000));
        assertEquals(64, b.percent());
    }

    @Test
    public void anImpossibleDropIsIgnoredAndTheNextGoodReadingIsUsed() {
        PhoneBattery b = new PhoneBattery();
        b.accept(80, NOW);
        assertFalse(b.accept(9, NOW + 5_000));         // 71 points in 5 s: not the battery
        assertEquals(80, b.percent());
        assertTrue(b.accept(79, NOW + 10_000));        // back to a real reading
        assertEquals(79, b.percent());
    }

    @Test
    public void afterAPauseAnyPlausibleReadingIsTaken() {
        PhoneBattery b = new PhoneBattery();
        b.accept(80, NOW);
        // the app was suspended for an hour: the phone really did run down
        assertTrue(b.accept(25, NOW + 3_600_000));
        assertEquals(25, b.percent());
    }

    @Test
    public void anInvalidReadingAloneIsNoValue() {
        PhoneBattery b = new PhoneBattery();
        assertFalse(b.accept(0, NOW));
        assertEquals(-1, b.percent());                  // the dash keeps showing nothing
    }

    @Test
    public void theStickyBroadcastLevelBecomesAPercentage() {
        assertEquals(55, PhoneBattery.fromLevel(55, 100));
        assertEquals(33, PhoneBattery.fromLevel(1, 3));
        assertEquals(-1, PhoneBattery.fromLevel(-1, 100));
        assertEquals(-1, PhoneBattery.fromLevel(50, 0));
    }
}
