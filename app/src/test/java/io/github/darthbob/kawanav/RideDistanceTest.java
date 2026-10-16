package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The live distance of a ride: a ride of its own, a ride taken over after a restart, and the file's count. */
public class RideDistanceTest {

    @Test
    public void aSecondRideStartsFromZero() {
        // the first ride of the service, then a second one later: the odometer and the position moved in between
        RideDistance first = new RideDistance(0, 0);
        first.odometer(12_000.0);
        first.odometer(12_013.0);
        first.position(45.0, 7.0, 5);
        first.position(45.001, 7.0, 5);
        assertEquals(13_000, first.metres(), 1e-6);

        RideDistance second = new RideDistance(0, 0);
        second.odometer(12_051.0);              // 38 km ridden without recording
        second.position(45.3, 7.2, 5);
        assertEquals(0, second.metres(), 0);    // no jump at the start of dist_m
        assertEquals(0, second.gpsM(), 0);
        second.odometer(12_051.4);
        assertEquals(400, second.metres(), 1e-6);
    }

    @Test
    public void aRideTakenOverKeepsCountingFromItsDistance() {
        RideDistance d = new RideDistance(800, 1_000);
        d.odometer(12_100.0);                    // where counting resumes, not a step
        d.position(45.0, 7.0, Double.NaN);
        assertEquals(1_000, d.bikeM(), 0);
        assertEquals(800, d.gpsM(), 0);
        d.odometer(12_100.5);
        assertEquals(1_500, d.metres(), 1e-6);
    }

    @Test
    public void jumpsBadFixesAndAnOdometerGoingBackAddNothing() {
        RideDistance d = new RideDistance(0, 0);
        d.position(45.0, 7.0, 5);
        d.position(45.01, 7.0, 5);               // 1.1 km in a second: a jump
        d.position(45.0101, 7.0, 30);            // 11 m with a 30 m fix
        assertEquals(0, d.gpsM(), 0);
        d.position(45.0102, 7.0, Double.NaN);    // unknown accuracy counts
        assertEquals(11.1, d.gpsM(), 0.1);
        d.odometer(500);
        d.odometer(499);
        assertEquals(0, d.bikeM(), 0);
        assertEquals(11.1, d.metres(), 0.1);     // no odometer distance: the GPS one
    }

    @Test
    public void countsLikeTheSummaryOfTheFile() {
        RideDistance d = new RideDistance(0, 0);
        for (RideTsv.Sample s : RideTest.ride100()) {
            if (s.odoKm == s.odoKm) d.odometer(s.odoKm);
            if (s.hasGps()) d.position(s.lat, s.lon, s.gpsAccM);
        }
        Ride r = new Ride();
        r.summarize(RideTest.ride100());
        assertEquals(r.distBikeM, d.bikeM(), 1e-6);
        assertEquals(r.distGpsM, d.gpsM(), 1e-6);
    }
}
