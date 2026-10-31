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
        File[] fs = root(c).listFiles(File::isDirectory);
        if (fs != null) for (File f : fs) if (RouteGraph.exists(f)) return true;
        return false;
    }
}
