package io.github.darthbob.kawanav;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Records a ride: once a second the bike's values (speed, rpm, gear, throttle, lean, temperatures…),
 * the phone's position and the odometer are written to a file ({@link RideStore}), and the summary is
 * updated as it goes. Started and stopped by hand from the app or the notification, optionally by
 * itself when the bike is moving. Runs on the main thread; the files are written on the ride thread.
 */
public final class RideRecorder implements android.location.LocationListener {

    /** The ride is longer than this: stop it (Rideology's limit is 24 h). */
    private static final long MAX_RIDE_MS = 24 * 60 * 60_000L;
    /** Stop when the phone is that low and not charging, like Rideology. */
    private static final int MIN_BATTERY_PERCENT = 10;
    /** Refuse to start with less free space than this. */
    private static final long MIN_FREE_BYTES = 50_000_000;
    /** A GPS fix counts as current this long. */
    private static final long FIX_MS = 5000;
    /** Bike values count as current this long. */
    private static final long BIKE_MS = 3000;
    /** Auto-start: the bike must be moving this fast for this long. */
    private static final double AUTO_KMH = 5;
    private static final long AUTO_MS = 10_000;

    public static final int IDLE = 0, RECORDING = 1, PAUSED = 2;

    private final Context context;
    private final RideStore store;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Ride ride;
    private int state = IDLE;
    private long lastSaveAt;
    /** Recorded time: runs while recording, not while paused. */
    private ActiveClock clock = new ActiveClock(0);
    /** Live distance since the ride started (GPS and bike odometer). */
    private double distGpsM, distBikeM, fuelStart = Double.NaN, lastFuel = Double.NaN;
    private double maxKmh, maxLeanL, maxLeanR;
    private int maxRpm, samples;
    private boolean gpsRequested, partialGps;
    private Location fix;
    private double autoSince = -1;

    public RideRecorder(Context context) {
        this.context = context.getApplicationContext();
        this.store = new RideStore(this.context);
    }

    public RideStore store() {
        return store;
    }

    public int state() {
        return state;
    }

    public Ride ride() {
        return ride;
    }

    /** Milliseconds recorded so far (pauses not counted). */
    public long activeMs() {
        if (ride == null) return 0;
        return clock.ms(System.currentTimeMillis());
    }

    public double distanceM() {
        return distBikeM > 0 ? distBikeM : distGpsM;
    }

    // ------------------------------------------------------------------ start / pause / stop

    /** Starts a ride; the reason why it could not be started, or null. */
    public String start(boolean manual) {
        if (state != IDLE) return "a ride is already running";
        if (context.getFilesDir().getUsableSpace() < MIN_FREE_BYTES) return "not enough free space on the phone";
        ride = new Ride();
        ride.id = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
        ride.name = Ride.defaultName(System.currentTimeMillis());
        ride.state = "recording";
        ride.start = System.currentTimeMillis();
        ride.end = ride.start;
        store.open(ride, false);
        state = RECORDING;
        clock = new ActiveClock(0);
        clock.start(System.currentTimeMillis());
        distGpsM = distBikeM = 0;
        fuelStart = lastFuel = Double.NaN;
        maxKmh = maxLeanL = maxLeanR = 0;
        maxRpm = 0;
        samples = 0;
        partialGps = false;
        requestGps();
        // Rideology asks for the riding log extension (temperatures, fuel, tyres) when a recording starts;
        // do the same when none has come in for a while
        if (Hub.bike.ext == null || System.currentTimeMillis() - Hub.bike.extAt > 120_000) {
            // if the bike is not there yet (the ride started the bridge) the ask is kept and sent later
            if (BridgeService.askRidingLogExt(context)) {
                Logger.log("Ride: asked the bike for the riding log extension (0x45)");
            }
        }
        main.post(tick);
        Logger.log("Ride: recording started (" + (manual ? "by hand" : "automatically") + ")");
        return null;
    }

    /**
     * Takes over a ride that was still being recorded when the app was killed: the samples continue in
     * the same file, and the summary keeps counting from where it stopped.
     */
    public String adopt(Ride r) {
        if (state != IDLE) return "a ride is already running";
        if (context.getFilesDir().getUsableSpace() < MIN_FREE_BYTES) return "not enough free space on the phone";
        ride = r;
        ride.state = "recording";
        ride.endReason = "";
        store.open(ride, true);
        state = RECORDING;
        clock = new ActiveClock(r.activeMs);
        clock.start(System.currentTimeMillis());
        lastSaveAt = System.currentTimeMillis();
        distGpsM = r.distGpsM;
        distBikeM = r.distBikeM;
        maxKmh = r.maxKmh;
        maxRpm = r.maxRpm;
        maxLeanL = r.maxLeanL;
        maxLeanR = r.maxLeanR;
        samples = r.samples;
        partialGps = !r.hasGps;
        requestGps();
        main.post(tick);
        Logger.log("Ride: continuing " + r.id + " after a restart (" + r.samples + " samples so far)");
        return null;
    }

    public void pause() {
        if (state != RECORDING) return;
        clock.stop(System.currentTimeMillis());
        state = PAUSED;
        ride.state = "paused";
        saveSummary();
        Logger.log("Ride: paused");
    }

    public void resume() {
        if (state != PAUSED) return;
        clock.start(System.currentTimeMillis());
        state = RECORDING;
        ride.state = "recording";
        Logger.log("Ride: resumed");
    }

    /** Ends the ride: the summary is saved and the samples are compressed on the ride thread. */
    public void finish(String reason) {
        if (state == IDLE || ride == null) return;
        main.removeCallbacks(tick);
        ride.activeMs = activeMs();
        ride.end = System.currentTimeMillis();
        ride.endReason = reason;
        ride.state = "done";
        store.close();
        ride.summarize(store.samples(ride.id));
        store.save(ride);
        store.compact(ride.id);
        stopGps();
        Logger.log("Ride: finished (" + reason + "), " + ride.samples + " samples, " + ride.distance()
                + ", " + ride.duration());
        ride = null;
        state = IDLE;
    }

    /** Called when the link to the bike was lost: a ride with bike data pauses until it is back. */
    public void linkLost() {
        if (state == RECORDING && samples > 0) pause();
    }

    /** Called when the bike is connected again. */
    public void linkBack() {
        if (state == PAUSED && ride != null) resume();
    }

    /** True when the ride was started without a working GPS (e.g. after a background autostart). */
    public boolean partialGps() {
        return partialGps;
    }

    // ------------------------------------------------------------------ recording loop

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (state == RECORDING) sample();
            if (state != IDLE) main.postDelayed(this, 1000);
        }
    };

    /** One recorded second. */
    private void sample() {
        long now = System.currentTimeMillis();
        if (clock.ms(now) > MAX_RIDE_MS) {
            finish("24 hours reached");
            return;
        }
        if (lowBattery()) {
            finish("phone battery low");
            return;
        }
        RideTsv.Sample s = new RideTsv.Sample();
        s.tMs = clock.ms(now);
        s.epochMs = now;
        s.flags = 0;

        Location l = freshFix();
        if (l != null) {
            s.lat = l.getLatitude();
            s.lon = l.getLongitude();
            if (l.hasAltitude()) s.altM = l.getAltitude();
            if (l.hasSpeed()) s.gpsKmh = l.getSpeed() * 3.6;
            if (l.hasAccuracy()) s.gpsAccM = l.getAccuracy();
            s.flags |= RideTsv.FLAG_GPS;
            ride.addPoint(l.getLatitude(), l.getLongitude());
        }

        BikeData b = Hub.bike;
        boolean bikeFresh = now - b.liveAt <= BIKE_MS;
        if (bikeFresh && Hub.bikeConnected) {
            BikeFrames.Telemetry t = b.live;
            if (t != null) {
                s.kmh = t.speedKmh;
                s.rpm = t.rpm;
                s.gear = t.gear;
                s.thrPct = t.throttlePct;
                s.accG = t.accelG;
                s.lean = t.leanDeg;
                s.tcs = t.tcsLow >= 0 ? t.tcsLow : Double.NaN;
                s.abs = t.abs;
                s.brakeFrontBar = t.brakeFrontBar;
                if (t.speedKmh == t.speedKmh) maxKmh = Math.max(maxKmh, t.speedKmh);
                if (t.rpm >= 0) maxRpm = Math.max(maxRpm, t.rpm);
                if (t.leanDeg == t.leanDeg) {
                    maxLeanR = Math.max(maxLeanR, t.leanDeg);
                    maxLeanL = Math.min(maxLeanL, t.leanDeg);
                }
            }
            if (now - b.extAt <= BIKE_MS && b.ext != null) {
                s.waterC = b.ext.waterC;
                s.oilC = b.ext.oilC;
                s.intakeC = b.ext.intakeC;
                s.fuelInst = b.ext.instantFuel;
                s.fuelRate = b.ext.fuelRate;
                s.tyreFrontKpa = b.ext.tyreFrontKpa;
                s.tyreRearKpa = b.ext.tyreRearKpa;
            }
            if (now - b.infoAt <= BIKE_MS && b.info != null) {
                s.odoKm = b.info.odometerKm >= 0 ? b.info.odometerKm : Double.NaN;
                s.fuelTotalL = b.info.totalFuelL;
                s.batteryV = BikeAlerts.battery(b.info);
                s.outsideC = b.info.outsideC;
                if (s.odoKm == s.odoKm) ride.odoEndKm = s.odoKm;
            }
            s.flags |= RideTsv.FLAG_BIKE;
        }
        // distance: from the odometer when it is there, else from the positions
        if (s.odoKm == s.odoKm) {
            if (ride.odoStartKm != ride.odoStartKm) ride.odoStartKm = s.odoKm;
            if (s.odoKm >= lastOdo) distBikeM += (s.odoKm - lastOdo) * 1000;
            lastOdo = s.odoKm;
        }
        if (l != null && lastLat == lastLat) {
            double d = Ride.distanceM(lastLat, lastLon, l.getLatitude(), l.getLongitude());
            if (d < 500 && (!l.hasAccuracy() || l.getAccuracy() < 25)) distGpsM += d;
        }
        if (l != null) {
            lastLat = l.getLatitude();
            lastLon = l.getLongitude();
        }
        if (s.fuelTotalL == s.fuelTotalL) lastFuel = s.fuelTotalL;
        // distance so far, the way the summary counts it: the bike's odometer when it has one, else GPS
        s.distM = distanceM();

        store.append(s);
        samples++;
        if (now - lastSaveAt >= 60_000) saveSummary();
    }

    private double lastOdo = Double.NaN, lastLat = Double.NaN, lastLon = Double.NaN;

    /** Writes the summary while the ride goes on, so the list shows its length and distance. */
    private void saveSummary() {
        if (ride == null) return;
        lastSaveAt = System.currentTimeMillis();
        ride.activeMs = activeMs();
        ride.end = System.currentTimeMillis();
        ride.samples = samples;
        ride.distGpsM = distGpsM;
        ride.distBikeM = distBikeM;
        ride.maxKmh = maxKmh;
        double hours = Math.max(1.0 / 3600, ride.activeMs / 3_600_000.0); // at least a second
        ride.avgKmh = (distBikeM > 0 ? distBikeM / 1000 : distGpsM / 1000) / hours; // km over active hours
        ride.maxRpm = maxRpm;
        ride.maxLeanL = maxLeanL;
        ride.maxLeanR = maxLeanR;
        ride.fuelUsedL = fuelStart == fuelStart && lastFuel == lastFuel ? Math.max(0, lastFuel - fuelStart) : 0;
        if (fuelStart != fuelStart && lastFuel == lastFuel) fuelStart = lastFuel;
        ride.hasGps = distGpsM > 0;
        ride.hasBike = samples > 0 && Hub.bikeConnected;
        store.save(ride);
    }

    private boolean lowBattery() {
        android.os.BatteryManager bm = context.getSystemService(android.os.BatteryManager.class);
        int percent = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        android.content.Intent battery = context.registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        boolean charging = battery != null
                && battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return !charging && percent > 0 && percent <= MIN_BATTERY_PERCENT;
    }

    /** Auto-start: with the bike connected and moving (its speed or, without it, the GPS speed). */
    public void autoCheck() {
        if (state != IDLE) return;
        if (!Hub.bikeConnected) {
            autoSince = -1;
            return;
        }
        BikeFrames.Telemetry t = Hub.bike.live;
        double kmh = t != null && t.speedKmh == t.speedKmh ? t.speedKmh : Double.NaN;
        if (kmh != kmh) {
            Location l = freshFix();
            if (l != null && l.hasSpeed()) kmh = l.getSpeed() * 3.6;
        }
        if (kmh != kmh || kmh < AUTO_KMH) {
            autoSince = -1;
            return;
        }
        long now = System.currentTimeMillis();
        if (autoSince < 0) autoSince = now;
        if (now - autoSince >= AUTO_MS) {
            autoSince = -1;
            start(false);
        }
    }

    // ------------------------------------------------------------------ location

    /** Registers the recorder's own GPS listener while recording (curves may be off). */
    public void requestGps() {
        if (gpsRequested) return;
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            partialGps = true;
            Logger.log("Ride: no location permission, recording without the track");
            return;
        }
        LocationManager lm = context.getSystemService(LocationManager.class);
        if (!lm.isLocationEnabled() || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            partialGps = true;
            Logger.log("Ride: GPS is off, recording without the track");
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            gpsRequested = true;
        } catch (SecurityException e) {
            partialGps = true;
            Logger.log("Ride: cannot use GPS: " + e);
        }
    }

    /** Called by the bridge when GPS became usable while recording (after the app was opened). */
    public void gpsBecameUsable() {
        if (state != IDLE) {
            requestGps();
            if (gpsRequested && partialGps) {
                partialGps = false;
                Logger.log("Ride: GPS joined the ride");
            }
        }
    }

    private void stopGps() {
        if (!gpsRequested) return;
        context.getSystemService(LocationManager.class).removeUpdates(this);
        gpsRequested = false;
        fix = null;
    }

    @Override
    public void onLocationChanged(Location loc) {
        fix = loc;
        Hub.lastFix = loc;
    }

    private Location freshFix() {
        if (fix != null && System.currentTimeMillis() - fix.getTime() <= FIX_MS) return fix;
        Location l = Hub.lastFix;
        return l != null && System.currentTimeMillis() - l.getTime() <= FIX_MS ? l : null;
    }

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    @Override
    public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
}
