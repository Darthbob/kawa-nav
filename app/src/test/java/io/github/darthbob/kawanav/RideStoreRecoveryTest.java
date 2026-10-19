package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * What happens to a ride the app was still writing when it was killed. The summary in the JSON is only
 * as fresh as the last periodic save, so it is the samples that decide what the ride is — and when they
 * cannot be read at all, the summary's own numbers must stand rather than being overwritten with a zero
 * that reads like a ride which recorded nothing.
 *
 * <p>The decisions are driven through {@link RideStore#recover}, because {@code resumeCandidate} reads
 * and writes the ride's JSON: {@code org.json} is a stub under {@code testDebugUnitTest}, so a test
 * that called it would fail on the stub rather than on the code. The samples still come from the real
 * store and its real files.
 */
public class RideStoreRecoveryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @After
    public void forgetTheOpenRide() {
        RideStore.resetForTests();
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

    /** A ride the app was writing when it died: "recording", with the summary of its last periodic save. */
    private static Ride killedMidRide(int savedSamples) {
        Ride r = new Ride();
        r.id = "ride";
        r.start = 1_760_000_000_000L;
        r.state = "recording";
        r.samples = savedSamples;
        r.activeMs = savedSamples * 1000L;
        r.end = r.start + savedSamples * 1000L;
        return r;
    }

    @Test
    public void aRideWithAReadableTrackIsRecovered() throws Exception {
        RideStore store = new RideStore(tmp.getRoot());
        store.open(killedMidRide(120), false);
        for (int i = 0; i < 5; i++) store.append(at(i));
        store.close();
        Ride r = killedMidRide(120);
        RideStore.recover(r, store.samples("ride"));
        assertEquals("recovered", r.state);
        assertEquals("the count comes from the samples that are there", 5, r.samples);
    }

    @Test
    public void aRideWhoseTrackIsGoneKeepsItsSummary() {
        RideStore store = new RideStore(tmp.getRoot());
        Ride r = killedMidRide(120);
        RideStore.recover(r, store.samples("ride"));
        // no samples could be read: the ride is the summary and says so, instead of claiming a recovery
        assertEquals("incomplete", r.state);
        assertEquals("the saved count must not be overwritten with a zero", 120, r.samples);
    }

    @Test
    public void aTruncatedTrackIsNotARecoveryEither() throws Exception {
        RideStore store = new RideStore(tmp.getRoot());
        // the .gz a kill during compression leaves behind: it is the file a reader prefers, and it is
        // not readable, with no plain file to fall back to
        Files.write(new File(tmp.getRoot(), "ride.tsv.gz").toPath(),
                "not a gzip".getBytes(StandardCharsets.UTF_8));
        Ride r = killedMidRide(90);
        assertEquals("nothing readable in that file", List.of(), store.samples("ride"));
        RideStore.recover(r, store.samples("ride"));
        assertEquals("incomplete", r.state);
        assertEquals(90, r.samples);
    }
}
