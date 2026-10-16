package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** Speed cameras: which one is ahead, and how they are stored in tiles. */
public class HazardsTest {

    /** ~46.50 N, 11.35 E (Bolzano), 110 m per 0.001° of latitude. */
    private static final double LAT = 46.50, LON = 11.35;

    private static Hazards.Camera at(double metresNorth, double metresEast) {
        return at(1, metresNorth, metresEast);
    }

    private static Hazards.Camera at(long id, double metresNorth, double metresEast) {
        return new Hazards.Camera(id, (int) Math.round((LAT + metresNorth / 111_320.0) * 1e7),
                (int) Math.round((LON + metresEast / (111_320.0 * Math.cos(Math.toRadians(LAT)))) * 1e7),
                "fixed", 50);
    }

    @Test
    public void findsTheCameraAhead() {
        List<Hazards.Camera> cameras = Arrays.asList(at(300, 0), at(800, 0));
        Hazards.Ahead a = Hazards.next(cameras, LAT, LON, 0, 500);
        assertNotNull(a);
        assertEquals(300, a.distanceM, 15);
    }

    @Test
    public void ignoresCamerasBehindAndTooFar() {
        // behind the rider (heading north, camera to the south) and one beyond the warning distance
        assertNull(Hazards.next(Arrays.asList(at(-200, 0)), LAT, LON, 0, 500));
        assertNull(Hazards.next(Arrays.asList(at(900, 0)), LAT, LON, 0, 500));
    }

    @Test
    public void theNearestOneWins() {
        Hazards.Ahead a = Hazards.next(Arrays.asList(at(400, 20), at(120, -30), at(380, 0)), LAT, LON, 0, 500);
        assertNotNull(a);
        assertEquals(120, a.distanceM, 15);
    }

    @Test
    public void aCameraAtTheRiderIsNotWarnedAgain() {
        // right ahead but closer than PASSED_M: the rider is basically at it
        assertNull(Hazards.next(Arrays.asList(at(10, 0)), LAT, LON, 0, 500));
        assertNull(Hazards.next(Arrays.asList(at(19, 0)), LAT, LON, 0, 500));
        assertNotNull(Hazards.next(Arrays.asList(at(21, 0)), LAT, LON, 0, 500));
    }

    @Test
    public void theConeFollowsTheHeading() {
        assertTrue(Hazards.inFront(LAT, LON, 90, LAT, LON + 0.01));       // heading east
        assertTrue(Hazards.inFront(LAT, LON, 0, LAT + 0.01, LON));        // heading north
        assertTrue(Hazards.inFront(LAT, LON, 350, LAT + 0.01, LON));      // just left of north
        assertTrue(Hazards.inFront(LAT, LON, 10, LAT + 0.01, LON));       // just right of north
        assertTrue(!Hazards.inFront(LAT, LON, 180, LAT + 0.01, LON));     // opposite direction
        assertTrue(!Hazards.inFront(LAT, LON, 0, LAT - 0.01, LON));       // behind
        assertTrue(!Hazards.inFront(LAT, LON, 0, LAT, LON));              // same spot
    }

    @Test
    public void namesAndDistance() {
        Hazards.Camera fixed = at(300, 0);
        assertEquals("Speed camera", Hazards.name(fixed));
        assertEquals("Speed camera in 300 m (50 km/h)", Hazards.describe(fixed, 300));
        Hazards.Camera light = new Hazards.Camera(2, fixed.lat7, fixed.lon7, "red_light", 0);
        assertEquals("Red-light camera in 1.2 km", Hazards.describe(light, 1200));
        Hazards.Camera avg = new Hazards.Camera(3, fixed.lat7, fixed.lon7, "average_speed", 0);
        assertEquals("Average-speed section", Hazards.name(avg));
    }

    @Test
    public void maxspeedFromTagValues() {
        assertEquals(50, PbfImporter.maxspeedKmh("50"));
        assertEquals(50, PbfImporter.maxspeedKmh("50 km/h"));
        assertEquals(50, PbfImporter.maxspeedKmh("50;70"));
        assertEquals(0, PbfImporter.maxspeedKmh("IT:urban"));   // a limit named by zone: unknown here
        assertEquals(0, PbfImporter.maxspeedKmh(null));
        assertEquals(0, PbfImporter.maxspeedKmh("none"));
    }

    @Test
    public void tilesKeepOneEntryPerCamera() throws Exception {
        File dir = Files.createTempDirectory("hazards").toFile();
        HazardStore store = new HazardStore(dir);
        Hazards.Camera c = at(100, 100);
        store.writeTile(1, 2, Arrays.asList(HazardStore.tsv(c)), false);
        assertEquals(1, store.load(1, 2).size());
        assertEquals(c.id, store.load(1, 2).get(0).id);
        Hazards.Camera read = store.load(1, 2).get(0);
        assertEquals(c.maxspeed, read.maxspeed);
        assertEquals(c.lat7, read.lat7);
        assertEquals(c.lon7, read.lon7);
        assertEquals(c.type, read.type);

        // a border tile merges the neighbouring region's cameras, without doubling one
        store.mergePartial(3, 4, Arrays.asList(HazardStore.tsv(c)));
        store.mergePartial(3, 4, Arrays.asList(HazardStore.tsv(c), HazardStore.tsv(new Hazards.Camera(9, c.lat7, c.lon7, "fixed", 0))));
        assertEquals(2, store.load(3, 4).size());
        assertTrue(store.hasAny(3, 4));
        assertTrue(!store.has(3, 4));

        // a complete tile replaces the partial data of the same tile
        store.writeTile(3, 4, Arrays.asList(HazardStore.tsv(c)), false);
        assertTrue(store.has(3, 4));
        assertEquals(1, store.load(3, 4).size());   // the complete tile replaced the two of the partial

        store.delete("3_4");
        assertTrue(!store.hasAny(3, 4));
    }

    @Test
    public void camerasOfSeveralTilesAreNotCountedTwice() {
        Hazards.Camera a = at(1, 10, 10), b = at(2, 5000, 5000);
        List<Hazards.Camera> merged = Hazards.merge(Arrays.asList(a, b), Arrays.asList(a));
        assertEquals(2, merged.size());
    }

    /** The border of a region decides whether a camera tile is complete or only partial. */
    @Test
    public void writingRespectsTheRegionBorder() throws Exception {
        File dir = Files.createTempDirectory("hazards-poly").toFile();
        File poly = new File(dir, "test.poly");
        // a square just around the tile 930_226 (46.50..46.55 N, 11.30..11.35 E): that tile lies inside,
        // the one east of it does not
        Files.write(poly.toPath(), ("test\n1\n"
                + "11.2999 46.4999\n11.3501 46.4999\n11.3501 46.5501\n11.2999 46.5501\n11.2999 46.4999\nEND\nEND\n")
                .getBytes(StandardCharsets.UTF_8));
        PbfImporter.Polygon region = PbfImporter.Polygon.read(poly);
        HazardStore store = new HazardStore(new File(dir, "hazards"));
        Hazards.Camera inside = new Hazards.Camera(8, (int) (46.52 * 1e7), (int) (11.32 * 1e7), "fixed", 0);
        Hazards.Camera outside = new Hazards.Camera(7, (int) (46.52 * 1e7), (int) (11.40 * 1e7), "fixed", 0);
        int[] res = store.write(region, Arrays.asList(inside, outside));
        assertEquals(2, res[0]);
        int ty = RoadStore.tileY(46.52), tx = RoadStore.tileX(11.32);
        assertTrue("inside the border: complete tile", store.has(ty, tx));
        int oy = RoadStore.tileY(46.52), ox = RoadStore.tileX(11.40);
        assertTrue("outside the border: only partial data", store.hasAny(oy, ox));
        assertTrue("a tile outside the border must not be complete", !store.has(oy, ox));
        assertEquals(1, store.load(oy, ox).size());
    }
}
