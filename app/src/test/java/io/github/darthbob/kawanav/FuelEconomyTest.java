package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The dash's km/L, shown in the unit the rider picked (issue #9). */
public class FuelEconomyTest {

    @Test
    public void theFourUnits() {
        assertEquals(19.2, FuelEconomy.convert(19.2, FuelEconomy.KM_PER_L), 1e-9);
        assertEquals(5.2083, FuelEconomy.convert(19.2, FuelEconomy.L_PER_100), 1e-4);   // the issue's bike
        assertEquals(45.07, FuelEconomy.convert(19.2, FuelEconomy.MPG_US), 0.01);
        assertEquals(54.14, FuelEconomy.convert(19.2, FuelEconomy.MPG_UK), 0.01);
        // back the other way, for the test of the test
        assertEquals(19.2, 100 / FuelEconomy.convert(19.2, FuelEconomy.L_PER_100), 1e-9);
    }

    @Test
    public void whatThereIsNoValueFor() {
        assertTrue(Double.isNaN(FuelEconomy.convert(Double.NaN, FuelEconomy.L_PER_100)));
        assertTrue(Double.isNaN(FuelEconomy.convert(0, FuelEconomy.L_PER_100)));   // 100 / 0
        assertEquals("", FuelEconomy.format(Double.NaN, FuelEconomy.L_PER_100));
    }

    @Test
    public void theValueComesWithItsUnit() {
        assertEquals("5.2 L/100 km", FuelEconomy.format(19.2, FuelEconomy.L_PER_100));
        assertEquals("19.2 km/L", FuelEconomy.format(19.2, FuelEconomy.KM_PER_L));
        assertEquals("45.1 MPG US", FuelEconomy.format(19.2, FuelEconomy.MPG_US));
        assertEquals("54.1 MPG UK", FuelEconomy.format(19.2, FuelEconomy.MPG_UK));
    }
}
