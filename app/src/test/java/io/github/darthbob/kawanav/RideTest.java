package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Ride files: the sample format, the summary computed from the samples, and the CSV/GPX export. */
public class RideTest {

    static RideTsv.Sample sample(int second, double kmh, double rpm, double gear, double lean, double lat, double lon) {
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = second * 1000L;
        s.epochMs = 1_760_000_000_000L + second * 1000L;
        s.kmh = kmh;
        s.rpm = rpm;
        s.gear = gear;
        s.lean = lean;
        s.thrPct = 20;
        s.waterC = 80;
        s.odoKm = 12_000 + second / 1000.0;
        s.flags = RideTsv.FLAG_BIKE | (lat == lat ? RideTsv.FLAG_GPS : 0);
        s.lat = lat;
        s.lon = lon;
        s.gpsAccM = 5;
        return s;
    }

    static List<RideTsv.Sample> ride100() {
        List<RideTsv.Sample> out = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            out.add(sample(i, 50 + i % 20, 4000 + i, i < 30 ? 0 : 3, i % 40 - 20,
                    45.0 + i * 0.0002, 7.0 + i * 0.0003));
        }
        return out;
    }

    @Test
    public void sampleRoundTrip() {
        List<RideTsv.Sample> in = ride100();
        List<String> lines = new ArrayList<>(Arrays.asList(RideTsv.magic(), RideTsv.header()));
        for (RideTsv.Sample s : in) lines.add(s.line());
        List<RideTsv.Sample> out = RideTsv.parse(lines);
        assertEquals(in.size(), out.size());
        assertEquals(in.get(5).tMs, out.get(5).tMs);
        assertEquals(in.get(5).kmh, out.get(5).kmh, 1e-6);
        assertEquals(in.get(5).rpm, out.get(5).rpm, 1e-6);
        assertEquals(in.get(5).lat, out.get(5).lat, 1e-6);
        assertEquals(in.get(5).flags, out.get(5).flags);
        assertTrue(out.get(5).hasGps());
    }

    @Test
    public void missingValuesStayEmpty() {
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = 1000;
        s.epochMs = 1;
        String line = s.line();
        assertTrue(line.contains("\t\t"));   // empty cells, not zeros
        assertFalse(s.hasGps());
        assertEquals(1, RideTsv.parse(Arrays.asList(RideTsv.magic(), RideTsv.header(), line)).size());
    }

    @Test
    public void summaryFromSamples() {
        Ride r = new Ride();
        r.summarize(ride100());
        assertEquals(100, r.samples);
        assertEquals(99_000, r.activeMs); // 100 samples, one second apart
        assertTrue(r.hasBike);
        assertTrue(r.hasGps);
        assertEquals(69.0, r.maxKmh, 0.1);
        assertEquals(4099, r.maxRpm);
        assertEquals(-20.0, r.maxLeanL, 0.01);
        assertEquals(19.0, r.maxLeanR, 0.01);
        assertEquals(0.1, r.odoEndKm - r.odoStartKm, 0.02);      // 100 s of 0.001 km steps
        assertTrue(r.distBikeM > 90 && r.distBikeM < 110);
        assertTrue(r.distGpsM > 3000 && r.distGpsM < 3400);      // ~32 m per second of track
        assertEquals(45.0, r.bbox[0], 1e-9);                     // the track's south-west corner
        assertEquals(7.0, r.bbox[1], 1e-9);
        assertTrue(r.bbox[2] > r.bbox[0]);
    }

    /** A sample with only the values the caller sets; everything else stays "the bike did not send it". */
    private static RideTsv.Sample bare(int second) {
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = second * 1000L;
        s.epochMs = 1_700_000_000_000L + second * 1000L;
        return s;
    }

    @Test
    public void metricsFromSamples() {
        Ride r = new Ride();
        r.summarize(ride100());   // rpm 4000..4099, throttle 20, coolant 80, odometer 12000+ ...
        Ride.Metric rpm = r.metric("rpm");
        assertTrue(rpm.known());
        assertEquals(100, rpm.n);
        assertEquals(4000, rpm.min, 1e-9);
        assertEquals(4099, rpm.max, 1e-9);
        assertEquals(4049.5, rpm.mean, 1e-9);
        assertEquals(80, r.metric("coolant").max, 1e-9);
        assertEquals(20, r.metric("throttle").max, 1e-9);
        assertEquals(Ride.SUMMARY_VERSION, r.summaryVersion);
    }

    @Test
    public void channelsTheBikeNeverSentStayUnknown() {
        Ride r = new Ride();
        r.summarize(ride100());
        // the fixture has no 0x4B/0x45/0x41 values beyond coolant, the odometer and the GPS accuracy
        for (String key : new String[]{"oil", "intake", "outside", "accel", "wheelie", "susp_f", "susp_r",
                "tyre_f", "tyre_r", "brake_f", "battery", "altitude", "torque_rider", "torque_req", "torque_actual",
                "fuel_rate", "susp_f_speed", "susp_r_speed", "brake_f_master", "brake_r_master", "brake_r_caliper",
                "fuel_inst", "accel_fwd", "accel_side", "accel_up", "roll_rate", "yaw_rate", "imu_rate1", "wheel_f",
                "wheel_r", "rear_slip", "gps_speed"}) {
            assertFalse("expected " + key + " to be unknown", r.metric(key).known());
        }
        assertTrue(r.sections().contains(Ride.ENGINE));
        assertTrue(r.metric("gps_acc").known());       // the fixture's fixes have an accuracy
        // the chassis section is there for the lean angle (0x4A) alone
        for (Ride.Metric m : r.metrics) {
            if (Ride.CHASSIS.equals(m.section) && m.known()) assertEquals("lean", m.key);
        }
        assertFalse(r.sections().contains(Ride.BRAKES));
        assertTrue(Double.isNaN(r.kqsUp));             // no quick-shifter column at all
    }

    @Test
    public void theImuAccelerationsAreTurnedAsTheChartTurnsThem() {
        List<RideTsv.Sample> in = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RideTsv.Sample s = bare(i);
            // at a standstill on the 2026-10-17 rides: the 45° pair at -0.70 g each (PROTOCOL.md, 0x4B)
            s.imu_ax_g = -0.70;
            s.imu_ay_g = -0.70;
            s.imu_az_g = 0.05;
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        assertEquals(0, r.metric("accel_fwd").max, 1e-9);
        assertEquals(0.99, r.metric("accel_up").mean, 0.005);
        assertEquals(0.05, r.metric("accel_side").min, 1e-9);
        assertTrue(r.sections().contains(Ride.CHASSIS));
    }

    @Test
    public void theLeanIsSummarizedEitherSideAndTheRatesOnlyByTheirExtremes() {
        List<RideTsv.Sample> in = new ArrayList<>();
        double[] lean = {-30, 10, 20};
        double[] roll = {-4, 1, 6};
        for (int i = 0; i < 3; i++) {
            RideTsv.Sample s = bare(i);
            s.lean = lean[i];
            s.imu_ax_psip3 = roll[i];
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        Ride.Metric l = r.metric("lean");
        assertEquals(10, l.min, 1e-9);
        assertEquals(20, l.mean, 1e-9);                // how far over, on average, whichever side
        assertEquals(30, l.max, 1e-9);
        assertFalse(l.extremes);
        Ride.Metric rr = r.metric("roll_rate");
        assertTrue(rr.extremes);                       // it averages out around zero: lowest and highest only
        assertEquals(-4, rr.min, 1e-9);
        assertEquals(6, rr.max, 1e-9);
        assertTrue(r.metric("yaw_rate").extremes);
        assertTrue(r.metric("rear_slip").extremes);
    }

    @Test
    public void theSlipIsLeftOutBelowWalkingPace() {
        List<RideTsv.Sample> in = new ArrayList<>();
        double[][] wheels = {{100, 110}, {80, 76}, {5, 9}};   // front, rear
        for (int i = 0; i < wheels.length; i++) {
            RideTsv.Sample s = bare(i);
            s.wheel_f_kmh = wheels[i][0];
            s.wheel_r_kmh = wheels[i][1];
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        Ride.Metric slip = r.metric("rear_slip");
        assertEquals(2, slip.n);                       // the 5 km/h second says nothing
        assertEquals(-5, slip.min, 1e-9);
        assertEquals(10, slip.max, 1e-9);
        assertEquals(110, r.metric("wheel_r").max, 1e-9);
    }

    @Test
    public void theTimeInEachGearAndTheShiftsAreCounted() {
        List<RideTsv.Sample> in = new ArrayList<>();
        double[] gear = {0, 1, 1, 2, 4, 3, 0, 2, Double.NaN, 3};
        double[] up = {0, 0, 1, 1, 0, 0, 0, 0, 0, 1};               // one shift held over two seconds, then one
        double[] down = {0, 0, 0, 0, 0, 1, 0, 0, Double.NaN, 0};
        for (int i = 0; i < gear.length; i++) {
            RideTsv.Sample s = bare(i);
            s.gear = gear[i];
            s.kqs_up = up[i];
            s.kqs_down = down[i];
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        assertEquals(2, r.gearS[0]);                   // neutral
        assertEquals(2, r.gearS[1]);
        assertEquals(2, r.gearS[2]);
        assertEquals(2, r.gearS[3]);
        assertEquals(1, r.gearS[4]);
        // 1→2, 2→4 (two steps), 2→3 across the second without a gear; 4→3 down. Through neutral counts not.
        assertEquals(4, r.shiftsUp);
        assertEquals(1, r.shiftsDown);
        assertEquals(2, r.kqsUp, 1e-9);
        assertEquals(1, r.kqsDown, 1e-9);
        assertTrue(r.sections().contains(Ride.ENGINE));   // the gears alone make the engine section
    }

    @Test
    public void aRideWithoutAGearHasNoGearRows() {
        List<RideTsv.Sample> in = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RideTsv.Sample s = bare(i);
            s.waterC = 80;
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        assertEquals(null, r.gearS);
        assertEquals(0, r.shiftsUp);
        assertFalse(r.sections().contains(Ride.ENGINE));
    }

    @Test
    public void torqueFuelUseAndRawBrakesAreSummarizedWhenTheBikeSendsThem() {
        List<RideTsv.Sample> in = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RideTsv.Sample s = bare(i);
            s.torque_rider = 10 + i;      // 10..13
            s.torque_req = 20 + i;        // 20..23
            s.torque_actual = 30 + i;     // 30..33
            s.fuelRate = 0.5;
            s.brake_r_caliper = i;        // 0..3: the first second's 0 is a reading
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        assertEquals(10, r.metric("torque_rider").min, 1e-9);
        assertEquals(13, r.metric("torque_rider").max, 1e-9);
        assertEquals(21.5, r.metric("torque_req").mean, 1e-9);
        assertEquals(33, r.metric("torque_actual").max, 1e-9);
        assertEquals(4, r.metric("fuel_rate").n);
        assertEquals(0, r.metric("brake_r_caliper").min, 1e-9);
        assertTrue(r.sections().contains(Ride.ENGINE));
        assertTrue(r.sections().contains(Ride.BRAKES));
        // what this bike did not send stays out of the screen
        assertFalse(r.metric("brake_f_master").known());
        assertFalse(r.metric("susp_f_speed").known());
        assertFalse(r.sections().contains(Ride.CHASSIS));
    }

    @Test
    public void aZeroIsAValueNotAnAbsence() {
        // no acceleration, no throttle, no brake pressure are all real values at their minimum
        java.util.List<RideTsv.Sample> in = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            RideTsv.Sample s = bare(i);
            s.accG = 0;
            s.thrPct = 0;
            s.brakeFrontBar = 0;
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        for (String key : new String[]{"accel", "throttle", "brake_f"}) {
            Ride.Metric m = r.metric(key);
            assertTrue("expected " + key + " to be known", m.known());
            assertEquals(0, m.min, 1e-9);
            assertEquals(0, m.max, 1e-9);
        }
    }

    @Test
    public void summarizeTwiceReplacesTheMetrics() {
        Ride r = new Ride();
        r.summarize(ride100());
        java.util.List<RideTsv.Sample> other = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RideTsv.Sample s = bare(i);
            s.rpm = 1000 + i;
            other.add(s);
        }
        r.summarize(other);
        assertEquals(3, r.metric("rpm").n);          // the second ride's, not merged with the first
        assertEquals(1000, r.metric("rpm").min, 1e-9);
        assertEquals(1002, r.metric("rpm").max, 1e-9);
        assertFalse(r.metric("coolant").known());    // the first ride had it, this one does not
    }

    @Test
    public void eitherTcsReadingCountsAsTheTractionControlWorking() {
        // the 0x4A nibbles and the 0x4B pair are different readings: the real bike moves only the 0x4B one
        java.util.List<RideTsv.Sample> in = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            RideTsv.Sample s = bare(i);
            s.tcs = 0;
            s.tcs_hb = i == 3 ? 1 : 0;
            s.tcs_lb = i == 3 ? 7 : 0;
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        assertEquals(1, r.tcsOnS, 1e-9);
        // and the other way round: only the 0x4A nibble moving
        java.util.List<RideTsv.Sample> other = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            RideTsv.Sample s = bare(i);
            s.tcs_hi = i < 2 ? 1 : 0;
            s.tcs_hb = 0;
            s.tcs_lb = 0;
            other.add(s);
        }
        Ride r2 = new Ride();
        r2.summarize(other);
        assertEquals(2, r2.tcsOnS, 1e-9);
    }

    @Test
    public void theHighTcsNibbleIsRecordedToo() {
        RideTsv.Sample s = bare(0);
        s.tcs = 3;
        s.tcs_hi = 1;
        java.util.List<RideTsv.Sample> back = RideTsv.parse(java.util.Arrays.asList(
                RideTsv.magic(), RideTsv.header(), s.line()));
        assertEquals(1, back.size());
        assertEquals(3, back.get(0).tcs, 1e-9);
        assertEquals(1, back.get(0).tcs_hi, 1e-9);
    }

    @Test
    public void stateFactsCountTimeNotZeros() {
        java.util.List<RideTsv.Sample> in = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {               // ten seconds without traction control
            RideTsv.Sample s = bare(i);
            s.tcs = 0;
            s.abs = 0;
            in.add(s);
        }
        for (int i = 10; i < 15; i++) {              // then five with it working
            RideTsv.Sample s = bare(i);
            s.tcs = 3;
            s.abs = 1;                               // front channel acting
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        assertEquals(5, r.tcsOnS, 1e-9);
        assertEquals(5, r.absOnS, 1e-9);
        assertTrue(Double.isNaN(r.wheelieS));        // no 0x4B in this ride at all
        assertTrue(Double.isNaN(r.cruiseOnS));
    }

    @Test
    public void stateFactsKeepTheDifferenceBetweenNeverAndNotRecorded() {
        java.util.List<RideTsv.Sample> in = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RideTsv.Sample s = bare(i);
            s.tcs = 0;                               // recorded, never cut in
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        assertEquals(0, r.tcsOnS, 1e-9);
        assertFalse(r.tcsOnS != r.tcsOnS);           // 0, not NaN: the row can say "never"
        assertTrue(Double.isNaN(r.absOnS));          // the ABS column is not in this ride
    }

    @Test
    public void theMetricTableIsWellFormed() {
        Ride r = new Ride();
        java.util.Set<String> keys = new java.util.HashSet<>();
        java.util.Set<String> sections = new java.util.HashSet<>();
        for (Ride.Metric m : r.metrics) {
            assertTrue("duplicate key " + m.key, keys.add(m.key));
            assertFalse(m.label.isEmpty());
            assertFalse(m.unit.isEmpty());
            sections.add(m.section);
        }
        assertEquals(java.util.Set.of(Ride.ENGINE, Ride.CHASSIS, Ride.BRAKES, Ride.TEMPERATURES, Ride.QUALITY),
                sections);
    }

    @Test
    public void summaryLeavesPausesOut() {
        List<RideTsv.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            RideTsv.Sample s = sample(i, 50, 4000, 3, 0, 45.0, 7.0);
            if (i >= 10) s.epochMs += 600_000;   // ten minutes paused after the tenth second
            samples.add(s);
        }
        Ride r = new Ride();
        r.summarize(samples);
        assertEquals(19_000, r.activeMs);
    }

    /** The ride's average is distance over recorded time, as the recorder counts it while riding. */
    @Test
    public void averageIsDistanceOverTime() {
        List<RideTsv.Sample> samples = new ArrayList<>();
        for (int i = 0; i <= 100; i++) {
            RideTsv.Sample s = sample(i, i < 50 ? 100 : 0, 4000, 3, 0, Double.NaN, Double.NaN);
            s.odoKm = 12_000 + Math.min(i, 50) * (100 / 3.6) / 1000;   // 100 km/h for 50 s, then standing
            samples.add(s);
        }
        Ride r = new Ride();
        r.summarize(samples);
        // ~1.39 km in 100 s: 50 km/h, not the 49.5 km/h mean of the speed samples
        assertEquals(50.0, r.avgKmh, 0.5);
    }

    /** The distance column is written and read back. */
    @Test
    public void distanceColumnRoundTrip() {
        RideTsv.Sample s = sample(5, 50, 4000, 3, 0, 45.0, 7.0);
        s.distM = 1234.5;
        List<RideTsv.Sample> out = RideTsv.parse(Arrays.asList(RideTsv.magic(), RideTsv.header(), s.line()));
        assertEquals(1234.5, out.get(0).distM, 0.01);
    }

    @Test
    public void summaryTwiceGivesTheSameFuel() {
        List<RideTsv.Sample> samples = ride100();
        for (int i = 0; i < samples.size(); i++) samples.get(i).fuelTotalL = 100 + i * 0.01;
        Ride r = new Ride();
        r.summarize(samples);
        assertEquals(0.99, r.fuelUsedL, 1e-6);
        r.summarize(samples);                    // at the end of a ride, after the summary written during it
        assertEquals(0.99, r.fuelUsedL, 1e-6);
    }

    /** A second of riding at {@code kmh} with this instant economy (NaN: the dash sent none). */
    private static RideTsv.Sample economy(int second, double kmh, double kmPerL) {
        RideTsv.Sample s = bare(second);
        s.flags = RideTsv.FLAG_BIKE;
        s.kmh = kmh;
        s.fuelInst = kmPerL;
        return s;
    }

    @Test
    public void theFuelEconomysAverageIsTheDistanceOverTheLitres() {
        // 10 s coasting at 50 km/h (the dash's 99.9 top) and 10 s at 100 km/h doing 10 km/L: the average
        // of the seconds says 55 km/L; the 0.42 km took 0.029 L, 14.3 km/L
        List<RideTsv.Sample> in = new ArrayList<>();
        for (int i = 0; i < 20; i++) in.add(i < 10 ? economy(i, 50, 99.9) : economy(i, 100, 10));
        Ride r = new Ride();
        r.summarize(in);
        Ride.Metric m = r.metric("fuel_inst");
        assertEquals(10, m.min, 1e-9);
        assertEquals(99.9, m.max, 1e-9);
        assertEquals(1500 / (500 / 99.9 + 100), m.mean, 1e-9);
        assertEquals(20, m.n);
        // no odometer and no track: the litres over the speed's own kilometres
        assertEquals((500 / 99.9 + 100) / 3600, r.fuelEstL, 1e-9);
        assertEquals(r.fuelEstL, r.fuelL(), 1e-12);
        assertTrue(r.fuelEstimated());
    }

    @Test
    public void theEstimatedLitresFollowTheRidesDistance() {
        // the speedometer says 60 km/h, the odometer (one with a fraction) 66: 10 % more road, 10 % more fuel
        List<RideTsv.Sample> in = new ArrayList<>();
        for (int i = 0; i < 3600; i++) {
            RideTsv.Sample s = economy(i, 60, 20);
            s.odoKm = 1000 + i * 0.0183;
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        assertEquals(3599 * 0.0183, r.km(), 1e-6);
        assertEquals(r.km() / 20, r.fuelEstL, 1e-9);
        assertEquals(5.0, r.fuelL() / r.km() * 100, 1e-9);
    }

    @Test
    public void aSecondWithoutAnEconomyBurnsNothingButOneWithoutTheBikeIsUnknown() {
        // 100 s at 20 km/L, then 100 s of engine braking with no economy: half the road for nothing
        List<RideTsv.Sample> braking = new ArrayList<>();
        for (int i = 0; i < 200; i++) braking.add(economy(i, 60, i < 100 ? 20 : Double.NaN));
        Ride a = new Ride();
        a.summarize(braking);
        assertEquals(20, a.metric("fuel_inst").mean, 1e-9);
        assertEquals(100 * 60 / 20.0 / 3600, a.fuelEstL, 1e-9);
        // 100 s at 20 km/L, then 100 s on the GPS alone (bike gone): that stretch burned as the rest did
        List<RideTsv.Sample> gone = new ArrayList<>();
        double step = 60 / 3.6 / 111_194.93;        // degrees of latitude per second at 60 km/h
        for (int i = 0; i < 200; i++) {
            RideTsv.Sample s = i < 100 ? economy(i, 60, 20) : bare(i);
            s.flags |= RideTsv.FLAG_GPS;
            s.lat = 45 + i * step;
            s.lon = 7;
            s.gpsKmh = 60;
            s.gpsAccM = 5;
            gone.add(s);
        }
        Ride b = new Ride();
        b.summarize(gone);
        assertEquals(5.0, b.fuelL() / b.km() * 100, 1e-6);
    }

    @Test
    public void theBikesFuelCounterWinsOverTheEstimate() {
        List<RideTsv.Sample> in = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            RideTsv.Sample s = economy(i, 60, 20);
            s.fuelTotalL = 100 + i * 0.01;
            in.add(s);
        }
        Ride r = new Ride();
        r.summarize(in);
        assertTrue(r.fuelEstL > 0);
        assertEquals(0.99, r.fuelL(), 1e-6);
        assertFalse(r.fuelEstimated());
    }

    @Test
    public void aRideWithoutAnyEconomyHasNoFuel() {
        Ride r = new Ride();
        r.summarize(ride100());
        assertEquals(0, r.fuelEstL, 1e-12);
        assertEquals(0, r.fuelL(), 1e-12);
        assertFalse(r.fuelEstimated());
        assertFalse(r.metric("fuel_inst").known());
    }

    @Test
    public void newColumnsRoundTrip() {
        // one value per group, so a swapped column name or order shows up
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = 1000;
        s.epochMs = 2_000_000;
        s.susp_f_stroke_mm = 12.5;
        s.susp_r_stroke_mm = 7.25;
        s.imu_ax_g = -0.42;
        s.imu_ay_psip1 = 3.5;
        s.wheel_f_kmh = 63.4;
        s.brake_f_master = 34;
        s.abs_target = 5;
        s.cruise_on = 1;
        s.torque_actual = 10;
        s.damping = 7;
        s.tcs_hb = 5;
        s.wheelie_deg = 45.02;
        java.util.List<RideTsv.Sample> back = RideTsv.parse(java.util.Arrays.asList(
                RideTsv.magic(), RideTsv.header(), s.line()));
        assertEquals(1, back.size());
        RideTsv.Sample r = back.get(0);
        assertEquals(12.5, r.susp_f_stroke_mm, 1e-9);
        assertEquals(7.25, r.susp_r_stroke_mm, 1e-9);
        assertEquals(-0.42, r.imu_ax_g, 1e-9);
        assertEquals(3.5, r.imu_ay_psip1, 1e-9);
        assertEquals(63.4, r.wheel_f_kmh, 1e-9);
        assertEquals(34, r.brake_f_master, 1e-9);
        assertEquals(5, r.abs_target, 1e-9);
        assertEquals(1, r.cruise_on, 1e-9);
        assertEquals(10, r.torque_actual, 1e-9);
        assertEquals(7, r.damping, 1e-9);
        assertEquals(5, r.tcs_hb, 1e-9);
        assertEquals(45.02, r.wheelie_deg, 1e-9);
        // what this sample has none of stays empty
        assertTrue(Double.isNaN(r.susp_r_stroke_vp));
        assertTrue(Double.isNaN(r.imu_az_g));
    }

    @Test
    public void aFileFromBeforeTheNewColumnsStillReads() {
        // a 0.7 file: same magic, header and row as then, without any of the 0.8 columns
        String header = "t_ms	epoch_ms	lat	lon	alt_m	gps_kmh	gps_acc_m	flags	kmh	rpm	gear"
                + "	thr_pct	acc_g	lean_deg	water_c	oil_c	intake_c	fuel_inst	fuel_rate	tcs	abs"
                + "	odo_km	dist_m	fuel_total_l	tyre_f_kpa	tyre_r_kpa	battery_v	outside_c	brake_f_bar";
        String row = "5000	1700000000000	47.1	9.5				3	62.50	4200	3	45.00	0.10	18.00"
                + "	85	95	30	4.8	1.00	1	0	12345	1500.00	123.45	210.00	251.00	11.72	18	12.70";
        java.util.List<RideTsv.Sample> back = RideTsv.parse(java.util.Arrays.asList(RideTsv.magic(), header, row));
        assertEquals(1, back.size());
        assertEquals(62.5, back.get(0).kmh, 1e-9);
        assertEquals(210.0, back.get(0).tyreFrontKpa, 1e-9);
        assertTrue(Double.isNaN(back.get(0).susp_f_stroke_mm));
        assertTrue(Double.isNaN(back.get(0).abs_target));
    }

    @Test
    public void theFullCsvCarriesEveryRecordedValue() {
        Ride r = new Ride();
        r.id = "test"; r.name = "Test ride"; r.start = 1_700_000_000_000L; r.activeMs = 3000;
        java.util.List<RideTsv.Sample> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RideTsv.Sample s = new RideTsv.Sample();
            s.tMs = i * 1000;
            s.epochMs = 1_700_000_000_000L + i * 1000;
            s.susp_f_stroke_mm = 12.5 + i;
            s.imu_ax_g = -0.4;
            s.wheel_f_kmh = 60 + i;
            s.abs_target = 5;
            samples.add(s);
        }
        String csv = RideExport.fullCsv(r, samples);
        String[] lines = csv.split("\n");
        // a comment, the header, one line per second - and the header is the ride file's own columns
        assertEquals(RideTsv.header(), lines[1]);
        assertEquals(lines[1].split("	", -1).length, lines[2].split("	", -1).length);
        assertEquals(3 + 2, lines.length);
        assertTrue(csv.startsWith("# "));
        // and it reads back as the same values
        java.util.List<RideTsv.Sample> back = RideTsv.parse(java.util.Arrays.asList(
                RideTsv.magic(), lines[1], lines[2], lines[3], lines[4]));
        assertEquals(3, back.size());
        assertEquals(12.5, back.get(0).susp_f_stroke_mm, 1e-9);
        assertEquals(5, back.get(2).abs_target, 1e-9);
    }

    @Test
    public void theRawExportIsTheRideFile() {
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = 1000;
        s.epochMs = 1_700_000_000_000L;
        s.kmh = 42;
        String raw = RideExport.rawTsv(java.util.Arrays.asList(s));
        String[] lines = raw.split("\n");
        assertEquals(RideTsv.magic(), lines[0]);
        assertEquals(RideTsv.header(), lines[1]);
        java.util.List<RideTsv.Sample> back = RideTsv.parse(java.util.Arrays.asList(lines));
        assertEquals(1, back.size());
        assertEquals(42, back.get(0).kmh, 1e-9);
    }

    @Test
    public void csvLayout() {
        Ride r = new Ride();
        r.name = "Morning ride";
        r.summarize(ride100());
        String csv = RideExport.csv(r, ride100(), "jacky", "");
        String[] lines = csv.split("\n");
        assertEquals("UserAccount Name,jacky", lines[0]);
        assertEquals("Title,Morning ride", lines[1]);
        assertTrue(lines[6].startsWith("R.Score"));
        assertTrue(lines[7].startsWith("\"elapsed_msec\",\"gps_latitude\""));
        assertEquals(14, lines[7].split(",", -1).length);
        String[] first = lines[8].split(",", -1);
        assertEquals("0", first[0]);
        assertEquals("45.000000", first[1]);
        assertEquals("80", first[4]);          // water temperature
        assertEquals("", first[5]);            // boost temperature: not on this bike
        assertEquals("4000", first[6]);
        assertEquals("50", first[7]);
        assertEquals("N", first[11]);          // first 30 samples: neutral
        assertEquals("", first[13]);           // rideology_score: server side
        assertTrue(RideExport.csvBytes(csv).length > 100);
    }

    @Test
    public void gpxTrack() {
        Ride r = new Ride();
        r.name = "Giro & prova <test>";
        r.summarize(ride100());
        String gpx = RideExport.gpx(r, ride100());
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(gpx.contains("<gpx version=\"1.1\" creator=\"KawaNav\""));
        assertTrue(gpx.contains("Giro &amp; prova &lt;test&gt;"));
        assertEquals(100, gpx.split("<trkpt").length - 1);
        assertTrue(gpx.contains("<kawanav:rpm>4000</kawanav:rpm>"));
        assertTrue(gpx.contains("<kawanav:gear>N</kawanav:gear>"));
        assertTrue(gpx.endsWith("</gpx>\n"));
    }

    @Test
    public void fileNameIsSafe() {
        Ride r = new Ride();
        r.id = "20261013-1840";
        r.name = "Giro: mattina / test";
        assertEquals("Giro_mattina_test.gpx", RideExport.fileName(r, "gpx"));
        r.name = "";
        assertEquals("20261013-1840.csv", RideExport.fileName(r, "csv"));
    }

    /** Older versions wrote a raw 00 temperature down as -40 / -60 C: read back, it is no value. */
    @Test
    public void aTemperatureAtTheBottomOfItsScaleReadsAsNone() {
        RideTsv.Sample s = sample(5, 50, 4000, 3, 0, 45.0, 7.0);
        s.waterC = -40;
        s.oilC = -60;
        s.intakeC = 36;
        List<RideTsv.Sample> back = RideTsv.parse(Arrays.asList(RideTsv.magic(), RideTsv.header(), s.line()));
        assertTrue(Double.isNaN(back.get(0).waterC));
        assertTrue(Double.isNaN(back.get(0).oilC));
        assertEquals(36, back.get(0).intakeC, 1e-9);
        Ride r = new Ride();
        r.summarize(back);
        assertFalse(r.metric("oil").known());
        assertFalse(r.metric("coolant").known());
    }

    /**
     * A ride north at 10 m/s, so the true distance at second i is 10 i m. The odometer reads whole km and
     * starts {@code startKm} into its count; it comes every 7 s (a Z900's comes in 3-second runs every 20 s,
     * KawaNav's poll: PROTOCOL.md). GPS every second, except
     * the seconds {@code noGps} says.
     */
    private static List<RideTsv.Sample> wholeKmRide(int seconds, double startKm, java.util.function.IntPredicate noGps) {
        List<RideTsv.Sample> out = new ArrayList<>();
        double degPerM = 180 / Math.PI / 6_371_000;
        for (int i = 0; i < seconds; i++) {
            RideTsv.Sample s = bare(i);
            s.flags = RideTsv.FLAG_BIKE;
            if (!noGps.test(i)) {
                s.flags |= RideTsv.FLAG_GPS;
                s.lat = 44.0 + 10 * i * degPerM;
                s.lon = 10.0;
                s.gpsAccM = 5;
            }
            if (i % 7 == 0) s.odoKm = Math.floor(startKm + i / 100.0);
            out.add(s);
        }
        return out;
    }

    /** 12.5 km that a whole-km odometer counts as 13: the GPS fills in what it cannot see. */
    @Test
    public void aWholeKmOdometerIsFilledInByTheGps() {
        Ride r = new Ride();
        r.summarize(wholeKmRide(1251, 5234.9, i -> false));    // 5234.9 -> 5247.4 km
        assertEquals(5234, r.odoStartKm, 0);
        assertEquals(13_000, r.distBikeM, 1e-6);               // the odometer's own count
        assertEquals(12_500, r.distM, 80);
        assertEquals(12.5, r.km(), 0.08);
        assertEquals("12.5 km", r.distance());
        assertEquals(36, r.avgKmh, 0.3);                       // 12.5 km in 1250 s
    }

    /** What the recorder writes every second: no kilometre steps, never running back, close to the truth. */
    @Test
    public void theRunningDistanceHasNoKilometreSteps() {
        Ride.Distance d = new Ride.Distance();
        double prev = 0;
        for (RideTsv.Sample s : wholeKmRide(1251, 5234.9, i -> false)) {
            d.add(s);
            double m = d.m();
            assertTrue("ran back at " + s.tMs, m >= prev);
            assertTrue("jumped at " + s.tMs, m - prev <= 100);
            assertEquals("at " + s.tMs, s.tMs / 100.0, m, 100);
            prev = m;
        }
    }

    /** The odometer keeps count while the GPS has no track: a gap costs nothing once it has stepped again. */
    @Test
    public void aGpsGapIsBridgedByTheOdometer() {
        Ride r = new Ride();
        r.summarize(wholeKmRide(1251, 5234.9, i -> i >= 300 && i <= 550));
        assertTrue(r.distGpsM < 10_100);                       // the track alone lost 2.5 km
        assertEquals(12_500, r.distM, 80);
    }

    /** Without a GPS track, the odometer's count; without an odometer, the track. */
    @Test
    public void eitherDistanceAloneIsTakenAsItIs() {
        Ride r = new Ride();
        r.summarize(wholeKmRide(1251, 5234.9, i -> true));
        assertEquals(13_000, r.distM, 1e-6);
        List<RideTsv.Sample> noOdometer = wholeKmRide(1251, 5234.9, i -> false);
        for (RideTsv.Sample s : noOdometer) s.odoKm = Double.NaN;
        r.summarize(noOdometer);
        assertEquals(0, r.distBikeM, 0);
        assertEquals(r.distGpsM, r.distM, 1e-9);
        assertEquals(12_500, r.distM, 1);
    }

    /** An odometer finer than a kilometre is taken as it is, whatever the GPS says. */
    @Test
    public void aFinerOdometerIsKept() {
        Ride r = new Ride();
        r.summarize(ride100());                                // 1 m odometer steps, ~3.2 km of track
        assertEquals(r.distBikeM, r.distM, 1e-9);
        assertEquals(r.distBikeM / 1000, r.km(), 1e-9);
    }

    /** A summary saved before the distance was kept shows what it showed then, until it is redone. */
    @Test
    public void anOlderSummaryKeepsItsDistance() {
        Ride r = new Ride();
        r.distBikeM = 13_000;
        r.distGpsM = 13_440;
        assertEquals(13.0, r.km(), 1e-9);
        r.distBikeM = 0;
        assertEquals(13.44, r.km(), 1e-9);
    }

    /** Every ride gets a new Distance: the previous ride's last odometer reading and position do not count. */
    @Test
    public void aSecondRideStartsFromZero() {
        Ride.Distance first = new Ride.Distance();
        for (RideTsv.Sample s : wholeKmRide(300, 12_000.2, i -> false)) first.add(s);
        assertEquals(2_990, first.m(), 100);
        // the next ride, 38 km further on and somewhere else
        Ride.Distance second = new Ride.Distance();
        RideTsv.Sample s = bare(0);
        s.flags = RideTsv.FLAG_BIKE | RideTsv.FLAG_GPS;
        s.odoKm = 12_041;
        s.lat = 45.3;
        s.lon = 7.2;
        s.gpsAccM = 5;
        second.add(s);
        assertEquals(0, second.m(), 0);                         // no jump at the start of dist_m
    }

    @Test
    public void theDashTotalTimeIsMinutes() {
        assertEquals("82 h 39 min", Ride.hoursMinutes(4959));   // the Z900 in issue #8
        assertEquals("2 h 00 min", Ride.hoursMinutes(120));
        assertEquals("59 min", Ride.hoursMinutes(59));
        assertEquals("0 min", Ride.hoursMinutes(0));
    }

    @Test
    public void distanceBetweenPoints() {
        assertEquals(111.3, Ride.distanceM(45.0, 7.0, 45.001, 7.0), 0.5);
        assertEquals(0, Ride.distanceM(45, 7, 45, 7), 1e-9);
    }
}
