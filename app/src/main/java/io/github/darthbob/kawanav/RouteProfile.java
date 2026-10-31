package io.github.darthbob.kawanav;

/**
 * What a route should look like: how fast each road is ridden and how much the router likes it.
 *
 * The cost of an edge is its riding time multiplied by a factor: below 1 for roads the profile seeks
 * (winding ones for the curvy profiles), above 1 for those it avoids (motorways, built-up areas,
 * gravel). Turns add a cost of their own. FASTEST is close to plain riding time; CURVY trades some time
 * for bends; EXTRA_CURVY takes long detours for them.
 *
 * Plain Java.
 */
public final class RouteProfile {

    public enum Kind { FASTEST, CURVY, EXTRA_CURVY }

    public final Kind kind;
    public final boolean avoidMotorways;
    public final boolean avoidTolls;
    public final boolean avoidUnpaved;

    /** Riding speed by road class (km/h) when the road has no speed limit. */
    private static final double[] SPEED = {120, 60, 90, 50, 80, 45, 70, 40, 60, 35, 45, 30, 10, 30};
    /** Speed by curviness level (straight .. very winding). */
    private static final double[] CURVY_SPEED = {1.0, 0.95, 0.85, 0.7};
    /** Speed by surface (paved, rough, unpaved, bad). */
    private static final double[] SURFACE_SPEED = {1.0, 0.8, 0.5, 0.25};

    /** Cost factor by road class, per profile (order of {@link RouteGraph#CLASSES}). */
    private static final double[][] CLASS_FACTOR = {
            // motorway, link, trunk, link, primary, link, secondary, link, tertiary, link, unclassified, residential, living, road
            {1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.05, 1.3, 2.0, 1.5},
            {4.0, 3.0, 2.2, 2.0, 1.5, 1.6, 1.2, 1.4, 1.15, 1.4, 1.35, 2.5, 5.0, 2.0},
            {8.0, 6.0, 4.0, 3.0, 2.0, 2.0, 1.4, 1.6, 1.2, 1.6, 1.3, 3.0, 6.0, 2.5},
    };
    /** Cost factor by curviness level, per profile. */
    private static final double[][] CURVY_FACTOR = {
            {1.0, 1.0, 1.0, 1.0},
            {1.0, 0.75, 0.55, 0.45},
            {1.0, 0.6, 0.38, 0.25},
    };
    /** Built-up area (speed limit 50 or less, sidewalks, many junctions): on top of the fastest class factors. */
    private static final double[] URBAN_FACTOR = {1.0, 2.2, 3.5};
    /** Surface factors (paved, rough, unpaved, bad); with avoidUnpaved the last two are raised. */
    private static final double[][] SURFACE_FACTOR = {
            {1.0, 1.1, 2.0, 5.0},
            {1.0, 1.3, 4.0, 12.0},
            {1.0, 1.3, 4.0, 12.0},
    };
    private static final double UNPAVED_AVOIDED = 5.0;
    private static final double AVOIDED = 30.0;
    private static final double DESTINATION_ONLY = 3.0;

    /** The fastest {@link #speed} can be (m/s), for the search estimate. */
    static final double MAX_SPEED = 150 / 3.6;

    public RouteProfile(Kind kind, boolean avoidMotorways, boolean avoidTolls, boolean avoidUnpaved) {
        this.kind = kind;
        this.avoidMotorways = avoidMotorways;
        this.avoidTolls = avoidTolls;
        this.avoidUnpaved = avoidUnpaved;
    }

    public static RouteProfile of(Kind kind) {
        return new RouteProfile(kind, false, false, true);
    }

    /** The edge may be ridden at all (closed roads are not). */
    public boolean usable(int flags) {
        return RouteGraph.access(flags) != 2;
    }

    /** Riding speed in m/s. */
    public double speed(int flags) {
        int cls = RouteGraph.roadClass(flags);
        double kmh = SPEED[cls];
        int limit = RouteGraph.maxspeedKmh(flags);
        if (limit > 0) kmh = Math.min(limit * 0.95, kmh * 1.25);
        else if ((flags & RouteGraph.F_URBAN) != 0) kmh = Math.min(kmh, 40);
        kmh *= CURVY_SPEED[RouteGraph.curvy(flags)] * SURFACE_SPEED[RouteGraph.surface(flags)];
        return Math.max(5, kmh) / 3.6;
    }

    /** How much the profile likes the edge: cost per second of riding. */
    public double factor(int flags) {
        int p = kind.ordinal();
        int cls = RouteGraph.roadClass(flags);
        double f;
        boolean motorway = cls == RouteGraph.C_MOTORWAY || cls == RouteGraph.C_MOTORWAY_LINK;
        if ((flags & RouteGraph.F_URBAN) != 0 && !motorway) {
            // in town the bends do not count: through it on the main road, as fast as possible
            f = CLASS_FACTOR[0][cls] * URBAN_FACTOR[p];
        } else {
            f = CLASS_FACTOR[p][cls] * CURVY_FACTOR[p][RouteGraph.curvy(flags)];
        }
        int s = RouteGraph.surface(flags);
        f *= SURFACE_FACTOR[p][s];
        if (avoidUnpaved && s >= 2) f *= UNPAVED_AVOIDED;
        if (avoidMotorways && motorway) f *= AVOIDED;
        if (avoidTolls && (flags & RouteGraph.F_TOLL) != 0) f *= AVOIDED;
        if (RouteGraph.access(flags) == 1) f *= DESTINATION_ONLY;
        return f;
    }

    /** The smallest {@link #factor} any edge can have: keeps the search estimate below the true cost. */
    public double minFactor() {
        int p = kind.ordinal();
        double min = Double.MAX_VALUE;
        for (double c : CLASS_FACTOR[p]) for (double l : CURVY_FACTOR[p]) min = Math.min(min, c * l);
        return min;
    }

    /** Cost of riding {@code metres} of an edge with these flags. */
    public double cost(int flags, double metres) {
        return metres / speed(flags) * factor(flags);
    }

    /**
     * Cost of a turn between two edges, from the change of direction (degrees, -180..180, negative to
     * the left) at the junction. Turning across the oncoming lane costs more; a U-turn costs a lot (the
     * router only makes one at a dead end or a via point).
     */
    public double turnCost(double angle, boolean leftHandTraffic) {
        double a = Math.abs(angle);
        if (a < 25) return 0;
        // turning across the oncoming lane waits for a gap
        boolean across = leftHandTraffic ? angle > 0 : angle < 0;
        if (a < 60) return 2;
        if (a < 120) return across ? 8 : 5;
        if (a < 170) return across ? 15 : 10;
        return 90;
    }

    /** Riding time of a turn, seconds (part of the ETA, unlike the router's preferences). */
    static double turnTime(double angle) {
        double a = Math.abs(angle);
        return a < 25 ? 0 : a < 60 ? 1 : a < 120 ? 4 : a < 170 ? 7 : 20;
    }
}
