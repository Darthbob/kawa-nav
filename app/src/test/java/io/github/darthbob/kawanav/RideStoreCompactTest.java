package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The sample file of a ride, plain while it runs and gzipped when it is done. A compression that was
 * interrupted must never hide the plain file: the compressed one is written beside it and only the
 * whole one replaces it.
 */
public class RideStoreCompactTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** The open ride is per process, not per store: one case must not leak into the next. */
    @org.junit.After
    public void forgetTheOpenRide() {
        RideStore.resetForTests();
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

    /** A store with a ride of five samples, written and closed the way the recorder writes it. */
    private RideStore withRide(String id) {
        RideStore store = new RideStore(tmp.getRoot());
        store.open(ride(id), false);
        for (int i = 0; i < 5; i++) store.append(at(i));
        store.close();
        return store;
    }

    @Test
    public void aTruncatedGzipFallsBackToThePlainFile() throws Exception {
        RideStore store = withRide("ride");
        // what an older version left behind: a .gz that masks the whole .tsv beside it
        File gz = new File(tmp.getRoot(), "ride.tsv.gz");
        try (FileOutputStream out = new FileOutputStream(gz)) {
            out.write("not a gzip at all".getBytes(StandardCharsets.UTF_8));
        }
        List<RideTsv.Sample> samples = store.samples("ride");
        assertEquals("the ride must still be readable", 5, samples.size());
        assertEquals(at(2).kmh, samples.get(2).kmh, 1e-9);
    }

    @Test
    public void aWholeGzipWinsOverThePlainFile() throws Exception {
        RideStore store = withRide("ride");
        store.compact("ride");
        store.awaitIdle();
        assertFalse("the plain file goes once the gzip is whole", new File(tmp.getRoot(), "ride.tsv").exists());
        assertFalse("no temporary file is left behind", new File(tmp.getRoot(), "ride.tsv.gz.tmp").exists());
        List<RideTsv.Sample> samples = store.samples("ride");
        assertEquals(5, samples.size());
        assertEquals(at(4).kmh, samples.get(4).kmh, 1e-9);
        assertEquals(at(0).epochMs, samples.get(0).epochMs);
    }

    @Test
    public void aFailedCompactionKeepsThePlainFile() throws Exception {
        RideStore store = withRide("ride");
        // a directory where the temporary file has to go: writing it fails, so the plain file stays
        File blocker = new File(tmp.getRoot(), "ride.tsv.gz.tmp");
        assertTrue(blocker.mkdir());
        store.compact("ride");
        store.awaitIdle();
        assertTrue("the samples are still there", new File(tmp.getRoot(), "ride.tsv").exists());
        assertFalse(new File(tmp.getRoot(), "ride.tsv.gz").exists());
        assertEquals(5, store.samples("ride").size());
    }

    @Test
    public void compactingARideWithNoPlainFileDoesNothing() {
        RideStore store = new RideStore(tmp.getRoot());
        store.compact("never-recorded");
        store.awaitIdle();
        assertFalse(new File(tmp.getRoot(), "never-recorded.tsv.gz").exists());
    }

    @Test
    public void deletingTheRideBeingRecordedIsRefused() {
        RideStore recorder = new RideStore(tmp.getRoot());
        recorder.open(ride("live"), false);
        for (int i = 0; i < 3; i++) recorder.append(at(i));
        // the ride screen builds its own store, which is how a delete used to slip past the recorder
        RideStore other = new RideStore(tmp.getRoot());
        other.delete("live");
        assertTrue("the ride is still being written, so it stays",
                new File(tmp.getRoot(), "live.tsv").exists());
        recorder.append(at(3)); // the samples the delete would have thrown away
        recorder.close();
        assertEquals(4, other.samples("live").size());
    }

    @Test
    public void aFinishedRideCanStillBeDeleted() {
        RideStore store = withRide("ride");
        new RideStore(tmp.getRoot()).delete("ride");
        assertFalse(new File(tmp.getRoot(), "ride.tsv").exists());
        assertTrue(store.samples("ride").isEmpty());
    }

    @Test
    public void deletingARideAlsoDropsAnUnfinishedCompression() throws Exception {
        RideStore store = withRide("ride");
        File leftover = new File(tmp.getRoot(), "ride.tsv.gz.tmp");
        java.nio.file.Files.write(leftover.toPath(), new byte[]{1, 2, 3});
        store.delete("ride");
        assertFalse(leftover.exists());
        assertFalse(new File(tmp.getRoot(), "ride.tsv").exists());
        assertTrue(store.samples("ride").isEmpty());
    }
}
