package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The tyre and clutch numbers of the field tests: which seconds count, and what they come to. */
public class WheelChecksTest {

    /** One steady second: upright, light throttle, rear wheel at {@code ratio} of the front. */
    private static RideTsv.Sample steady(long tMs, double frontKmh, double ratio, int gear, double rpmPerKmh) {
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = tMs;
        s.wheel_f_kmh = frontKmh;
        s.wheel_r_kmh = frontKmh * ratio;
        s.lean = 0;
        s.accG = 0;
        s.thrPct = 10;
        s.gear = gear;
        s.rpm = s.wheel_r_kmh * rpmPerKmh;
        s.kqs_up = 0;
        s.kqs_down = 0;
        return s;
    }

    private static List<RideTsv.Sample> cruise(int seconds, double frontKmh, double ratio) {
        List<RideTsv.Sample> out = new ArrayList<>();
        for (int i = 0; i < seconds; i++) out.add(steady(i * 1000L, frontKmh, ratio, 4, 57.5));
        return out;
    }

    @Test
    public void steadyRidingGivesTheRatioInItsBand() {
        WheelChecks.Tyres t = WheelChecks.tyres(cruise(100, 55, 0.988));
        assertEquals(0.988, t.ratio[0], 1e-9);
        assertEquals(99, t.n[0]);                       // the first second has no neighbour
        assertTrue(Double.isNaN(t.ratio[1]));           // never at 70–100 km/h
        assertEquals(0.988, t.all, 1e-9);
    }

    @Test
    public void tooLittleSteadyRidingGivesNoRatio() {
        WheelChecks.Tyres t = WheelChecks.tyres(cruise(WheelChecks.MIN_SAMPLES, 55, 0.988));
        assertTrue(Double.isNaN(t.ratio[0]));
        assertTrue(Double.isNaN(t.all));
    }

    @Test
    public void leaningBrakingAndChangingSpeedDoNotCount() {
        List<RideTsv.Sample> ride = cruise(100, 55, 0.988);
        for (int i = 10; i < 20; i++) ride.get(i).lean = 25;
        for (int i = 20; i < 30; i++) ride.get(i).accG = -0.4;
        for (int i = 30; i < 40; i++) ride.get(i).thrPct = 80;
        for (int i = 40; i < 50; i++) ride.get(i).wheel_f_kmh = 55 + ((i + 1) % 2) * 5; // the front jumping about
        for (int i = 10; i < 50; i++) ride.get(i).wheel_r_kmh = ride.get(i).wheel_f_kmh * 0.95;
        WheelChecks.Tyres t = WheelChecks.tyres(ride);
        assertEquals(0.988, t.ratio[0], 1e-9);
        assertEquals(9 + 50, t.n[0]);                    // 1..9 and 50..99
    }

    @Test
    public void aFewOddSecondsDoNotMoveTheRatio() {
        List<RideTsv.Sample> ride = cruise(200, 55, 0.988);
        for (int i = 0; i < 60; i++) ride.get(i).wheel_r_kmh = 55 * 0.990;
        assertEquals(0.988, WheelChecks.tyres(ride).ratio[0], 1e-9);
        assertEquals(0.985, WheelChecks.tyres(cruise(200, 55, 0.985)).ratio[0], 1e-9);
    }

    @Test
    public void theGearingIsReadAtLightThrottle() {
        WheelChecks.Clutch c = WheelChecks.clutch(cruise(100, 60, 0.988));
        assertEquals(57.5, c.rpmPerKmh[4], 1e-9);
        assertTrue(Double.isNaN(c.rpmPerKmh[3]));
        assertEquals(0, c.loadN);
        assertTrue(Double.isNaN(c.loadMaxPct));
    }

    @Test
    public void anEngineRunningAheadUnderLoadIsSlip() {
        List<RideTsv.Sample> ride = cruise(100, 60, 0.988);
        for (int i = 50; i < 55; i++) {
            RideTsv.Sample s = ride.get(i);
            s.thrPct = 100;
            s.rpm = s.wheel_r_kmh * 57.5 * 1.05;      // 5% more engine than the wheel says
        }
        WheelChecks.Clutch c = WheelChecks.clutch(ride);
        assertEquals(5, c.loadN);
        assertEquals(5, c.slipN);
        assertEquals(5, c.loadMaxPct, 1e-6);
    }

    @Test
    public void readingBelowTheGearingUnderLoadIsNotSlip() {
        List<RideTsv.Sample> ride = cruise(100, 60, 0.988);
        for (int i = 50; i < 55; i++) {
            RideTsv.Sample s = ride.get(i);
            s.thrPct = 100;
            s.rpm = s.wheel_r_kmh * 57.5 * 0.97;      // what the Z900 does at full throttle
        }
        WheelChecks.Clutch c = WheelChecks.clutch(ride);
        assertEquals(5, c.loadN);
        assertEquals(0, c.slipN);
        assertEquals(-3, c.loadMedianPct, 1e-6);
    }

    @Test
    public void shiftsGapsAndLowGearsAreLeftOut() {
        List<RideTsv.Sample> ride = cruise(100, 60, 0.988);
        for (int i = 40; i < 70; i++) {
            ride.get(i).thrPct = 100;
            ride.get(i).rpm *= 1.10;
        }
        ride.get(45).gear = 5;                         // a shift: 44, 45 and 46 have the clutch in between
        ride.get(50).kqs_up = 1;                        // a quick-shifter cut
        for (int i = 55; i < 100; i++) ride.get(i).tMs += 5000; // a gap before 55
        for (int i = 60; i < 70; i++) ride.get(i).gear = 2;     // 2nd gear: too low to load the clutch
        for (int i = 60; i < 70; i++) ride.get(i).rpm = ride.get(i).wheel_r_kmh * 80 * 1.1;
        WheelChecks.Clutch c = WheelChecks.clutch(ride);
        // 40..59 loaded, minus 44-46 (shift), 50 (cut), 54 and 55 (the gap), 59 (next is 2nd gear)
        assertEquals(20 - 3 - 1 - 2 - 1, c.loadN);
    }

    @Test
    public void theUsualValueNeedsTwoRides() {
        assertTrue(Double.isNaN(WheelChecks.usual(Arrays.asList(0.988, Double.NaN))));
        assertEquals(0.988, WheelChecks.usual(Arrays.asList(0.987, Double.NaN, 0.988, 0.990)), 1e-9);
        assertEquals(0.9875, WheelChecks.usual(Arrays.asList(0.987, 0.988)), 1e-9);
    }
}
