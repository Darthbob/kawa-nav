package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * What happens to a ride the app was still writing when it was killed. The summary in the JSON is only
 * as fresh as the last periodic save, so it is the samples that decide what the ride is — and when they
 * cannot be read at all, the summary's own numbers must stand rather than being overwritten with a zero
 * that reads like a ride which recorded nothing.
 */
public class RideStoreRecoveryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void forgetTheOpenRide() {
        RideStore.resetForTests();
    }

    /** A ride the app was writing when it died: state "recording" in the JSON, nothing holding it open. */
    private RideStore killedMidRide(String id, int savedSamples, boolean withTrack) throws Exception {
        RideStore store = new RideStore(tmp.getRoot());
        if (withTrack) {
            store.open(ride(id), false);
            for (int i = 0; i < 5; i++) store.append(at(i));
            store.close();
        }
        Ride r = ride(id);
        r.samples = savedSamples;
        r.activeMs = savedSamples * 1000L;
        r.end = r.start + savedSamples * 1000L;
        store.save(r);
        store.awaitIdle();
        return store;
    }

    private static Ride ride(String id) {
        Ride r = new Ride();
        r.id = id;
        r.start = 1_760_000_000_000L;
        r.state = "recording";
        return r;
    }

    private static RideTsv.Sample at(int second) {
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = second * 1000L;
        s.epochMs = 1_760_000_000_000L + second * 1000L;
        s.kmh = 50 + second;
        s.rpm = 4000;
        s.gear = 3;
        s.lean = second % 30 - 15;
        s.lat = 45.0 + second * 0.0001;
        s.lon = 7.0;
        s.flags = RideTsv.FLAG_BIKE | RideTsv.FLAG_GPS;
        return s;
    }

    @Test
    public void aRideWithAReadableTrackIsRecovered() throws Exception {
        RideStore store = killedMidRide("ride", 120, true);
        store.resumeCandidate();
        store.awaitIdle();
        Ride r = store.get("ride");
        assertEquals("recovered", r.state);
        assertEquals("the count comes from the samples that are there", 5, r.samples);
    }

    @Test
    public void aRideWhoseTrackIsGoneKeepsItsSummary() throws Exception {
        RideStore store = killedMidRide("gone", 120, false);
        store.resumeCandidate();
        store.awaitIdle();
        Ride r = store.get("gone");
        // no samples could be read: the ride is the summary and says so, instead of claiming a recovery
        assertEquals("incomplete", r.state);
        assertEquals("the saved count must not be overwritten with a zero", 120, r.samples);
    }

    @Test
    public void aTruncatedTrackIsNotARecoveryEither() throws Exception {
        RideStore store = killedMidRide("cut", 90, true);
        store.compact("cut");
        store.awaitIdle();
        // the .gz a kill during compression used to leave behind: the plain file is gone with it
        File gz = new File(tmp.getRoot(), "cut.tsv.gz");
        java.nio.file.Files.write(gz.toPath(), "not a gzip".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        store.resumeCandidate();
        store.awaitIdle();
        Ride r = store.get("cut");
        assertEquals("incomplete", r.state);
        assertEquals(90, r.samples);
    }
}
