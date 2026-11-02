package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/** What the route planner has to plan on: it tells the rider to download a map when there is nothing. */
public class RoutingDataTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(f)) {
            w.write(text);
        }
    }

    @Test
    public void nothingDownloaded() throws IOException {
        File files = tmp.newFolder("files");
        assertEquals(RoutingData.NO_REGION, RoutingData.state(files));
        // the folders alone (made by the app on the way) are no region
        new File(files, "regions").mkdirs();
        new File(files, "routing").mkdirs();
        assertEquals(RoutingData.NO_REGION, RoutingData.state(files));
    }

    @Test
    public void aRegionFromBeforeTheRoutePlanner() throws IOException {
        File files = tmp.newFolder("files");
        write(new File(files, "regions/bayern.json"), "{}");
        assertEquals(RoutingData.OLD_REGIONS, RoutingData.state(files));
        // routing data of an older format counts as none
        write(new File(files, "routing/bayern/meta.properties"), "version=" + (RouteGraph.FORMAT - 1) + "\n");
        assertEquals(RoutingData.OLD_REGIONS, RoutingData.state(files));
    }

    @Test
    public void aRegionWithRoutingData() throws IOException {
        File files = tmp.newFolder("files");
        write(new File(files, "regions/bayern.json"), "{}");
        write(new File(files, "routing/bayern/meta.properties"), "version=" + RouteGraph.FORMAT + "\n");
        assertEquals(RoutingData.READY, RoutingData.state(files));
    }
}
