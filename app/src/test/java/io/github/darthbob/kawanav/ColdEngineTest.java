package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The cold engine warning: when it is said, and when it stays quiet. */
public class ColdEngineTest {

    @Test
    public void saidWhenAColdEngineGoesOverTheLimit() {
        ColdEngine c = new ColdEngine();
        assertFalse("under the limit", c.check(45, 4000, 6000, 0));
        assertTrue(c.check(45, 6500, 6000, 1000));
        assertFalse("still over: said once", c.check(46, 7000, 6000, 2000));
    }

    @Test
    public void notAgainWithinAMinute() {
        ColdEngine c = new ColdEngine();
        assertTrue(c.check(45, 6500, 6000, 0));
        assertFalse(c.check(45, 3000, 6000, 10_000));
        assertFalse("over again 20 s later", c.check(45, 6500, 6000, 20_000));
        assertFalse(c.check(45, 3000, 6000, 30_000));
        assertTrue("over again after a minute", c.check(45, 6500, 6000, 70_000));
    }

    @Test
    public void quietOnceTheEngineHasBeenWarm() {
        ColdEngine c = new ColdEngine();
        assertFalse(c.check(60, 8000, 6000, 0));
        assertTrue(c.warm());
        // a long descent cools it below 60 °C again: it is not a cold engine
        assertFalse(c.check(56, 8000, 6000, 120_000));
        c.reset();
        assertTrue("a new connection starts over", c.check(50, 8000, 6000, 240_000));
    }

    @Test
    public void nothingWithoutBothValuesOrWhenOff() {
        ColdEngine c = new ColdEngine();
        assertFalse(c.check(Double.NaN, 8000, 6000, 0));
        assertFalse(c.check(40, -1, 6000, 0));
        assertFalse(c.check(40, 8000, 0, 0));
        assertEquals(0, ColdEngine.limit(0));
        assertEquals(6000, ColdEngine.limit(2));
        assertEquals("a setting from a newer version is off", 0, ColdEngine.limit(9));
        assertTrue("the values arrive: said now", c.check(40, 8000, 6000, 0));
    }
}
