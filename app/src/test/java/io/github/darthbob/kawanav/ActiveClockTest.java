package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The recorded time of a ride: reading it often, pausing, and a ride taken over after a restart. */
public class ActiveClockTest {

    @Test
    public void readingDoesNotCountTwice() {
        ActiveClock c = new ActiveClock(0);
        c.start(0);
        assertEquals(1_000, c.ms(1_000));
        assertEquals(60_000, c.ms(60_000));   // the summary is written every minute
        assertEquals(60_000, c.ms(60_000));
        assertEquals(69_000, c.ms(69_000));   // a 69 s ride stays 69 s
    }

    @Test
    public void pausesAreLeftOut() {
        ActiveClock c = new ActiveClock(0);
        c.start(0);
        c.stop(30_000);
        assertFalse(c.running());
        assertEquals(30_000, c.ms(600_000));  // ten minutes of coffee break
        c.start(600_000);
        assertTrue(c.running());
        assertEquals(40_000, c.ms(610_000));
        c.stop(620_000);
        c.stop(700_000);                      // a second pause changes nothing
        assertEquals(50_000, c.ms(700_000));
    }

    @Test
    public void startingAgainWhileRunningKeepsTheStart() {
        ActiveClock c = new ActiveClock(0);
        c.start(0);
        c.start(5_000);
        assertEquals(10_000, c.ms(10_000));
    }

    @Test
    public void takenOverRideCountsOn() {
        ActiveClock c = new ActiveClock(50_000);
        assertEquals(50_000, c.ms(1_000));    // stopped until started
        c.start(1_000);
        assertEquals(52_000, c.ms(3_000));
    }

    @Test
    public void clockGoingBackIsIgnored() {
        ActiveClock c = new ActiveClock(0);
        c.start(10_000);
        assertEquals(0, c.ms(9_000));         // phone clock set back
        c.stop(9_000);
        assertEquals(0, c.ms(20_000));
    }
}
