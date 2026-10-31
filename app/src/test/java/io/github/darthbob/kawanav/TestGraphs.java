package io.github.darthbob.kawanav;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small routing graphs for the tests, drawn in metres around 46 N / 11 E (x east, y north) and built
 * with {@link RouteGraphBuilder} like an import does: a point used by two ways is a junction.
 */
final class TestGraphs {
    private TestGraphs() {}

    static final double LAT0 = 46.0, LON0 = 11.0;
    private static final double KY = 111_132.0, KX = 111_320.0 * Math.cos(Math.toRadians(LAT0));

    static int lat7(double y) {
        return (int) Math.round((LAT0 + y / KY) * 1e7);
    }

    static int lon7(double x) {
        return (int) Math.round((LON0 + x / KX) * 1e7);
    }

    static double lat(double y) {
        return LAT0 + y / KY;
    }

    static double lon(double x) {
        return LON0 + x / KX;
    }

    static double x(double lon) {
        return (lon - LON0) * KX;
    }

    static double y(double lat) {
        return (lat - LAT0) * KY;
    }

    /** A road: OSM node ids and their positions in metres. */
    static final class Way {
        final long[] ids;
        final double[] xs, ys;
        String highway = "secondary", name = "", ref = "", oneway = "", junction = "";
        int attributes;

        Way(long[] ids, double[] xs, double[] ys) {
            this.ids = ids;
            this.xs = xs;
            this.ys = ys;
        }

        Way highway(String h) {
            highway = h;
            return this;
        }

        Way name(String n) {
            name = n;
            return this;
        }

        Way oneway(String o) {
            oneway = o;
            return this;
        }

        Way roundabout() {
            junction = "roundabout";
            return this;
        }

        Way attributes(int a) {
            attributes = a;
            return this;
        }
    }

    /** A straight way through the given (x, y) points, one OSM node per point. */
    static Way way(long[] ids, double... xy) {
        double[] xs = new double[xy.length / 2], ys = new double[xy.length / 2];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = xy[2 * i];
            ys[i] = xy[2 * i + 1];
        }
        return new Way(ids, xs, ys);
    }

    /** Ids from {@code first} on, one per point. */
    static long[] ids(long first, int count) {
        long[] r = new long[count];
        for (int i = 0; i < count; i++) r[i] = first + i;
        return r;
    }

    /** A winding road: {@code bends} half circles of the radius, alternating left and right, eastwards. */
    static Way snake(long firstId, double x0, double y0, double radius, int bends, double stepDeg) {
        List<double[]> pts = new ArrayList<>();
        double cx = x0 + radius;
        for (int b = 0; b < bends; b++) {
            boolean up = b % 2 == 0;
            for (double a = 180; a >= 0; a -= stepDeg) {
                if (b > 0 && a == 180) continue;
                double r = Math.toRadians(a);
                pts.add(new double[]{cx + radius * Math.cos(r), y0 + (up ? 1 : -1) * radius * Math.sin(r)});
            }
            cx += 2 * radius;
        }
        double[] xy = new double[pts.size() * 2];
        for (int i = 0; i < pts.size(); i++) {
            xy[2 * i] = pts.get(i)[0];
            xy[2 * i + 1] = pts.get(i)[1];
        }
        return way(ids(firstId, pts.size()), xy);
    }

    /** Builds the graph of the ways into a new directory and returns it. */
    static File build(List<Way> ways) throws Exception {
        return build(ways, (ty, tx) -> true, Files.createTempDirectory("graph").toFile());
    }

    static File build(List<Way> ways, RouteGraphBuilder.Border border, File dir) throws Exception {
        Map<Long, Integer> refs = new HashMap<>();
        for (Way w : ways) for (long id : w.ids) refs.merge(id, 1, Integer::sum);
        File out = new File(dir, "region");
        RouteGraphBuilder b = new RouteGraphBuilder(out, new File(dir, "tmp"));
        for (Way w : ways) {
            int n = w.ids.length;
            int[] la = new int[n], lo = new int[n];
            boolean[] junction = new boolean[n];
            for (int i = 0; i < n; i++) {
                la[i] = lat7(w.ys[i]);
                lo[i] = lon7(w.xs[i]);
                junction[i] = refs.get(w.ids[i]) > 1;
            }
            b.addWay(w.highway, w.name, w.ref, w.oneway, w.junction, w.attributes, w.ids, la, lo, junction,
                    new boolean[n], n);
        }
        b.finish(border, new PbfImporter.Progress() {
            public void status(String s) {}

            public boolean cancelled() {
                return false;
            }
        });
        return out;
    }

    static RouteGraph open(File... regions) throws Exception {
        return RouteGraph.open(java.util.Arrays.asList(regions));
    }
}
