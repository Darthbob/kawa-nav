package io.github.darthbob.kawanav;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The routing graph of all downloaded regions, opened once and shared by the planner and the
 * navigation; opened again when a region was downloaded or deleted.
 */
final class RoutingData {
    private RoutingData() {}

    private static RouteGraph graph;
    private static String opened = "";

    /** The routing data on disk changed: the next {@link #graph} opens it again. */
    static synchronized void changed() {
        opened = "";
    }

    static File root(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "routing");
    }

    /** The graph of the downloaded regions; empty when none has routing data. */
    static synchronized RouteGraph graph(Context c) throws java.io.IOException {
        List<File> dirs = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        File[] fs = root(c).listFiles(File::isDirectory);
        if (fs != null) {
            java.util.Arrays.sort(fs);
            for (File f : fs) {
                if (!RouteGraph.exists(f)) continue;
                dirs.add(f);
                sig.append(f.getName()).append(':').append(new File(f, "meta.properties").lastModified()).append(' ');
            }
        }
        if (graph == null || !sig.toString().equals(opened)) {
            graph = RouteGraph.open(dirs);
            opened = sig.toString();
            Logger.log("Routing data: " + dirs.size() + " regions, " + graph.edgeCount() + " roads");
        }
        return graph;
    }

    /** Some region has routing data. */
    static boolean available(Context c) {
        return available(root(c));
    }

    private static boolean available(File root) {
        File[] fs = root.listFiles(File::isDirectory);
        if (fs != null) for (File f : fs) if (RouteGraph.exists(f)) return true;
        return false;
    }

    /** What the planner has to plan on: routing data, no region at all, or only regions without it. */
    static final int READY = 0, NO_REGION = 1, OLD_REGIONS = 2;

    static int state(Context c) {
        return state(c.getApplicationContext().getFilesDir());
    }

    /**
     * @param files the app's files folder: "routing" holds the graphs, "regions" a record per downloaded
     *              region ({@link OfflineRegions}); a region downloaded before 1.3 (or in an older routing
     *              format) has a record but no graph
     */
    static int state(File files) {
        if (available(new File(files, "routing"))) return READY;
        File[] records = new File(files, "regions").listFiles((d, n) -> n.endsWith(".json"));
        return records != null && records.length > 0 ? OLD_REGIONS : NO_REGION;
    }
}
