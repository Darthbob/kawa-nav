package io.github.darthbob.kawanav;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The cold engine warning: when it is said, and when it stays quiet. */
public class ColdEngineTest {

    @Test
    public void saidWhenAColdEngineGoesOverTheLimit() {
        ColdEngine c = new ColdEngine();
        assertFalse("under the limit", c.check(45, 4000, 4500, 70, 0));
        assertTrue(c.check(45, 4600, 4500, 70, 1000));
        assertFalse("still over: said once", c.check(46, 5000, 4500, 70, 2000));
    }

    @Test
    public void notAgainWithinAMinute() {
        ColdEngine c = new ColdEngine();
        assertTrue(c.check(45, 6500, 6000, 70, 0));
        assertFalse(c.check(45, 3000, 6000, 70, 10_000));
        assertFalse("over again 20 s later", c.check(45, 6500, 6000, 70, 20_000));
        assertFalse(c.check(45, 3000, 6000, 70, 30_000));
        assertTrue("over again after a minute", c.check(45, 6500, 6000, 70, 70_000));
    }

    @Test
    public void theTemperatureChosenIsWarm() {
        ColdEngine c = new ColdEngine();
        assertTrue("65 °C is still cold with 70 chosen", c.check(65, 5000, 4500, 70, 0));
        ColdEngine sixty = new ColdEngine();
        assertFalse("but warm with 60", sixty.check(65, 5000, 4500, 60, 0));
        assertTrue(sixty.warm());
    }

    @Test
    public void quietOnceTheEngineHasBeenWarm() {
        ColdEngine c = new ColdEngine();
        assertFalse(c.check(70, 8000, 4500, 70, 0));
        assertTrue(c.warm());
        // a long descent cools it below the limit again: it is not a cold engine
        assertFalse(c.check(56, 8000, 4500, 70, 120_000));
    }

    @Test
    public void aShortLinkDropKeepsTheEngineWarm() {
        ColdEngine c = new ColdEngine();
        assertFalse(c.check(75, 3000, 4500, 70, 0));
        // the link drops on a cool descent and is back two minutes later, the coolant at 58 °C
        c.linkLost(60_000);
        c.linkLost(90_000);   // a failed reconnect: the first loss counts
        c.linkBack(180_000);
        assertFalse(c.check(58, 6000, 4500, 70, 181_000));
        // switched off for half an hour: a new start
        c.linkLost(300_000);
        c.linkBack(300_000 + 30 * 60_000);
        assertTrue(c.check(45, 6000, 4500, 70, 300_000 + 30 * 60_000 + 1000));
    }

    @Test
    public void aLinkBackAfterTenMinutesStartsOver() {
        ColdEngine c = new ColdEngine();
        assertFalse(c.check(75, 3000, 4500, 70, 0));
        c.linkLost(1000);
        c.linkBack(1000 + ColdEngine.KEEP_WARM_MS - 1);
        assertTrue(c.warm());
        c.linkLost(2_000_000);
        c.linkBack(2_000_000 + ColdEngine.KEEP_WARM_MS);
        assertFalse(c.warm());
    }

    @Test
    public void nothingWithoutBothValuesOrWhenOff() {
        ColdEngine c = new ColdEngine();
        assertFalse(c.check(Double.NaN, 8000, 4500, 70, 0));
        assertFalse(c.check(40, -1, 4500, 70, 0));
        assertFalse("off", c.check(40, 8000, 0, 70, 0));
        assertTrue("the values arrive: said now", c.check(40, 8000, 4500, 70, 0));
    }

    @Test
    public void theChoicesAndTheirDefaults() {
        assertEquals(4500, ColdEngine.RPM_CHOICES[ColdEngine.indexOf(ColdEngine.RPM_CHOICES, 4500, ColdEngine.DEFAULT_RPM)]);
        assertEquals("a value the list does not have falls back to the default", 4500,
                ColdEngine.RPM_CHOICES[ColdEngine.indexOf(ColdEngine.RPM_CHOICES, 4321, ColdEngine.DEFAULT_RPM)]);
        assertEquals(70, ColdEngine.WARM_C_CHOICES[ColdEngine.indexOf(ColdEngine.WARM_C_CHOICES, 99, ColdEngine.DEFAULT_WARM_C)]);
        assertEquals("4,500 rpm", ColdEngine.rpmLabels()[3]);
        assertArrayEquals(new String[]{"50 °C", "60 °C", "70 °C", "80 °C"}, ColdEngine.warmLabels());
    }
}
