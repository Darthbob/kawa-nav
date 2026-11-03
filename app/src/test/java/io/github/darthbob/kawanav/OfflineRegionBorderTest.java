package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * The borders the planner's map draws: one file per region next to its record, kept from the download,
 * read back with every ring. (The region list itself goes through org.json, stubbed on the JVM.)
 */
public class OfflineRegionBorderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String POLY = "hessen\n1\n  8.0 50.0\n  9.0 50.0\n  9.0 51.0\n  8.0 51.0\nEND\n"
            + "2\n  10.0 50.0\n  10.5 50.0\n  10.5 50.5\nEND\nEND\n";

    @Test
    public void aBorderIsKeptPerRegionAndReadBackWithItsRings() throws IOException {
        File dir = tmp.newFolder("regions");
        OfflineRegions regions = new OfflineRegions(dir, new RoadStore(tmp.newFolder("roads")),
                new HazardStore(tmp.newFolder("hazards")));
        File downloaded = tmp.newFile("download.poly");
        try (FileWriter w = new FileWriter(downloaded)) {
            w.write(POLY);
        }
        regions.keepBorder("hessen", downloaded);
        regions.keepBorder("us/alabama", downloaded);
        // each region its own file, under the folded key like its record
        assertEquals(new File(dir, "hessen.poly"), regions.borderFile("hessen"));
        assertEquals(new File(dir, "us_alabama.poly"), regions.borderFile("us/alabama"));
        assertTrue(regions.borderFile("hessen").isFile());
        assertTrue(regions.borderFile("us/alabama").isFile());
        // a region in two parts (an island, an exclave) keeps both rings
        PbfImporter.Polygon p = PbfImporter.Polygon.read(regions.borderFile("hessen"));
        assertEquals(2, p.rings.size());
        assertEquals(8, p.rings.get(0).length);
        assertTrue(p.contains(50.5, 8.5));
    }
}
