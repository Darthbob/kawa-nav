package io.github.darthbob.kawanav;

/**
 * The distance of a ride while it is recorded: from the bike's odometer and from the GPS positions,
 * counted the way {@link Ride#summarize} counts them from the file afterwards. Each ride gets a new one,
 * so nothing of the previous ride (its last odometer reading or position) leaks into the next.
 */
final class RideDistance {

    private double gpsM, bikeM;
    private double lastOdo = Double.NaN, lastLat = Double.NaN, lastLon = Double.NaN;

    /**
     * Starts from a distance already covered (a ride taken over after a restart, or 0). The first
     * reading after that only says where counting resumes.
     */
    RideDistance(double gpsM, double bikeM) {
        this.gpsM = gpsM;
        this.bikeM = bikeM;
    }

    /** An odometer reading in km; the odometer going backwards adds nothing. */
    void odometer(double km) {
        if (km >= lastOdo) bikeM += (km - lastOdo) * 1000;
        lastOdo = km;
    }

    /** A GPS position; jumps and bad fixes add nothing. Accuracy in metres, NaN when unknown. */
    void position(double lat, double lon, double accM) {
        if (lastLat == lastLat) {
            double d = Ride.distanceM(lastLat, lastLon, lat, lon);
            if (d < 500 && (accM != accM || accM < 25)) gpsM += d;
        }
        lastLat = lat;
        lastLon = lon;
    }

    double gpsM() {
        return gpsM;
    }

    double bikeM() {
        return bikeM;
    }

    /** The ride's distance: the odometer's when it has one, else the GPS one. */
    double metres() {
        return bikeM > 0 ? bikeM : gpsM;
    }
}
