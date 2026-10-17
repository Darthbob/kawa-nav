package io.github.darthbob.kawanav;

import java.util.Locale;

/**
 * The bike's fuel economy, sent in km/L (0x41 average mileage and fuel consumption) and shown in the
 * unit the rider picks. The meter sends km/L whatever its own display is set to: on the Z900 of
 * issue #9 set to L/100 km, the app's 19.2 was the rider's 5.2 L/100 km. The conversions are the same
 * as Rideology's (`u6/ib.java`), and plain Java so the tests can check them.
 */
public final class FuelEconomy {
    private FuelEconomy() {}

    /** The choices, in the settings order. */
    public static final String[] LABELS = {"km/L", "L/100 km", "MPG US", "MPG UK"};
    public static final int KM_PER_L = 0, L_PER_100 = 1, MPG_US = 2, MPG_UK = 3;
    /** L/100 km: the app's other economy figures (refuels, rides) are in it. */
    public static final int DEFAULT = L_PER_100;

    /** A value the dash sent in km/L, in {@code unit}; NaN for no value, or 0 km/L in L/100 km. */
    public static double convert(double kmPerL, int unit) {
        if (kmPerL != kmPerL) return Double.NaN;
        switch (unit) {
            case KM_PER_L: return kmPerL;
            case MPG_US: return kmPerL * 3.78 / 1.61;
            case MPG_UK: return kmPerL * 4.54 / 1.61;
            default:
                return kmPerL > 0 ? 100.0 / kmPerL : Double.NaN;   // no litres per 100 km of nothing
        }
    }

    /** "5.2 L/100 km", or "" when there is no value. */
    public static String format(double kmPerL, int unit) {
        double v = convert(kmPerL, unit);
        if (v != v) return "";
        return String.format(Locale.ROOT, "%.1f %s", v, LABELS[unit]);
    }
}
