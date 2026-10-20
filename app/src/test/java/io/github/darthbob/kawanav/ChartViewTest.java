package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The ride screen's chart: which column each value reads, the torque's three lines, the rear wheel slip. */
public class ChartViewTest {

    @Test
    public void theTorqueIsThreeLinesFromItsThreeColumns() {
        RideTsv.Sample s = new RideTsv.Sample();
        s.torque_rider = 40;
        s.torque_req = 38;
        s.torque_actual = 36;
        assertEquals(3, ChartView.Series.TORQUE.lineCount());
        assertEquals(40, ChartView.value(s, ChartView.Series.TORQUE, 0), 1e-9);   // asked by the throttle
        assertEquals(38, ChartView.value(s, ChartView.Series.TORQUE, 1), 1e-9);   // asked by the engine control
        assertEquals(36, ChartView.value(s, ChartView.Series.TORQUE, 2), 1e-9);   // given by the engine
        assertEquals(40, ChartView.value(s, ChartView.Series.TORQUE), 1e-9);      // the first line
        assertTrue(Double.isNaN(ChartView.value(s, ChartView.Series.TORQUE, 3)));
    }

    @Test
    public void aSeriesIsThereWhenAnyOfItsLinesIs() {
        RideTsv.Sample s = new RideTsv.Sample();
        assertFalse(ChartView.has(s, ChartView.Series.TORQUE));
        s.torque_actual = 12;              // only the engine's own figure
        assertTrue(ChartView.has(s, ChartView.Series.TORQUE));
        assertFalse(ChartView.has(s, ChartView.Series.YAW_RATE));
    }

    @Test
    public void oneLineSeriesReadTheirOwnColumn() {
        RideTsv.Sample s = new RideTsv.Sample();
        s.imu_ax_psip3 = 1.5;              // roll rate (PROTOCOL.md, 0x4B)
        s.imu_az_psip2 = -2.5;             // yaw rate
        s.imu_ay_psip1 = 0.5;              // not identified
        s.gpsKmh = 87;
        s.susp_f_stroke_vp = 0.25;
        s.brake_r_caliper = 7;
        s.fuelRate = 1.2;
        s.wheelie_deg = 4;
        assertEquals(1.5, ChartView.value(s, ChartView.Series.ROLL_RATE), 1e-9);
        assertEquals(-2.5, ChartView.value(s, ChartView.Series.YAW_RATE), 1e-9);
        assertEquals(0.5, ChartView.value(s, ChartView.Series.IMU_RATE1), 1e-9);
        assertEquals(87, ChartView.value(s, ChartView.Series.GPS_SPEED), 1e-9);
        assertEquals(0.25, ChartView.value(s, ChartView.Series.SUSP_SPEED_FRONT), 1e-9);
        assertEquals(7, ChartView.value(s, ChartView.Series.BRAKE_R_CALIPER), 1e-9);
        assertEquals(1.2, ChartView.value(s, ChartView.Series.FUEL_RATE), 1e-9);
        assertEquals(4, ChartView.value(s, ChartView.Series.WHEELIE_ANGLE), 1e-9);
        assertEquals(1, ChartView.Series.YAW_RATE.lineCount());
        assertTrue(Double.isNaN(ChartView.value(s, ChartView.Series.YAW_RATE, 1)));
    }

    @Test
    public void theRearWheelSlipIsHowMuchFasterItTurnsThanTheFront() {
        assertEquals(10, Ride.rearSlip(100, 110), 1e-9);      // spinning up under power
        assertEquals(-10, Ride.rearSlip(100, 90), 1e-9);      // braked harder than the front
        assertEquals(0, Ride.rearSlip(50, 50), 1e-9);
        // crawling, or a wheel speed missing: nothing worth a ratio
        assertTrue(Double.isNaN(Ride.rearSlip(Ride.SLIP_MIN_KMH - 1, 20)));
        assertTrue(Double.isNaN(Ride.rearSlip(Double.NaN, 50)));
        assertTrue(Double.isNaN(Ride.rearSlip(50, Double.NaN)));
        RideTsv.Sample s = new RideTsv.Sample();
        s.wheel_f_kmh = 80;
        s.wheel_r_kmh = 84;
        assertEquals(5, ChartView.value(s, ChartView.Series.REAR_SLIP), 1e-9);
    }

    @Test
    public void theMapIsColouredByTheLineInTheAccentColour() {
        RideTsv.Sample s = new RideTsv.Sample();
        s.torque_rider = 40;
        s.torque_actual = 36;
        s.imu_az_psip2 = 2;
        assertEquals(36, ChartView.mapValue(s, ChartView.Series.TORQUE), 1e-9);   // what the engine gives
        assertEquals(2, ChartView.mapValue(s, ChartView.Series.YAW_RATE), 1e-9);
    }

    @Test
    public void theZoomedStretchStaysInsideTheRide() {
        long total = 3_600_000;
        assertArrayEquals(new long[]{600_000, 660_000}, ChartView.window(600_000, 60_000, total));
        // dragged past either end: it stops at the end, keeping its length
        assertArrayEquals(new long[]{0, 60_000}, ChartView.window(-30_000, 60_000, total));
        assertArrayEquals(new long[]{3_540_000, 3_600_000}, ChartView.window(3_590_000, 60_000, total));
        // zoomed out further than the ride: the whole ride
        assertArrayEquals(new long[]{0, total}, ChartView.window(-5, 2 * total, total));
    }

    @Test
    public void aTouchFindsTheClosestSecond() {
        java.util.List<RideTsv.Sample> ride = new java.util.ArrayList<>();
        for (long t : new long[]{0, 1000, 2000, 5000, 6000}) {   // a gap after 2 s
            RideTsv.Sample s = new RideTsv.Sample();
            s.tMs = t;
            ride.add(s);
        }
        assertEquals(0, ChartView.nearest(ride, -500));
        assertEquals(1, ChartView.nearest(ride, 1400));
        assertEquals(2, ChartView.nearest(ride, 3000));
        assertEquals(3, ChartView.nearest(ride, 4000));
        assertEquals(4, ChartView.nearest(ride, 9000));
        assertEquals(3, ChartView.firstAtOrAfter(ride, 2001));
        assertEquals(5, ChartView.firstAtOrAfter(ride, 6001));
    }

    @Test
    public void theTimeAxisReadsSecondsOnceZoomedIn() {
        assertEquals("12:05", ChartView.clock(725_000));
        assertEquals("1:02:05", ChartView.clock(3_725_000));
        assertEquals("45 min", ChartView.minutes(2_700_000));
        assertEquals("1 h 05 min", ChartView.minutes(3_900_000));
    }

    private static void assertArrayEquals(long[] expected, long[] actual) {
        org.junit.Assert.assertArrayEquals(expected, actual);
    }

    @Test
    public void theLineUnderTheChartNamesThePickedValue() {
        RideTsv.Sample s = new RideTsv.Sample();
        s.torque_rider = 40;
        s.torque_actual = 36;
        s.imu_az_psip2 = 3.24;
        s.gpsKmh = 87.4;
        assertEquals("Torque 40 / - / 36", ChartView.describe(s, ChartView.Series.TORQUE));
        assertEquals("Yaw rate 3.2", ChartView.describe(s, ChartView.Series.YAW_RATE));
        assertEquals("Speed (GPS) 87 km/h", ChartView.describe(s, ChartView.Series.GPS_SPEED));
        assertEquals("Roll rate -", ChartView.describe(s, ChartView.Series.ROLL_RATE));
    }
}
