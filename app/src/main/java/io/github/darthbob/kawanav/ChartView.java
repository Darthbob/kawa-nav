package io.github.darthbob.kawanav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * One recorded value over the ride: a line with its range on the left and the time along the bottom.
 * Touching it marks the second, so the track and the values can be looked at together. Pinching zooms
 * the time axis (two fingers then move along it, a double tap zooms in), and the value scale follows
 * what is shown, so a short stretch can be read closely.
 */
public final class ChartView extends View {

    /**
     * Which recorded value to draw, in the order the ride screen lists them. Most are one line; the
     * torque is three on one scale (asked by the throttle, asked by the engine control, given by the
     * engine), so the moments the electronics take torque away show as the lines parting.
     */
    public enum Series {
        SPEED("Speed", "km/h", 0), GPS_SPEED("Speed (GPS)", "km/h", 0), RPM("Engine", "rpm", 0),
        // the bike's own unit: the three compare with each other, not with a spec sheet
        TORQUE("Torque", "", 0, "throttle", "control", "engine"),
        GEAR("Gear", "", 0), THROTTLE("Throttle", "%", 0),
        LEAN("Lean angle", "°", 0),
        ROLL_RATE("Roll rate", "°/s", 1), YAW_RATE("Yaw rate", "°/s", 1), PITCH_RATE("Pitch rate", "°/s", 1),
        WATER("Coolant", "°C", 0), ACCEL("Acceleration", "g", 2),
        OIL("Oil temperature", "°C", 0), INTAKE("Intake air", "°C", 0), FUEL("Fuel economy", "", 1),
        FUEL_RATE("Fuel use", "", 2),
        TYRE_FRONT("Front tyre", "bar", 2), TYRE_REAR("Rear tyre", "bar", 2), BATTERY("Battery", "V", 1),
        OUTSIDE("Outside temperature", "°C", 0), BRAKE("Front brake", "bar", 1),
        // kept raw, as Rideology keeps them
        BRAKE_F_MASTER("Front brake master (unverified)", "", 0),
        BRAKE_R_MASTER("Rear brake master (unverified)", "", 0),
        BRAKE_R_CALIPER("Rear brake caliper (unverified)", "", 0),
        ALTITUDE("Altitude", "m", 0),
        SUSP_FRONT("Front suspension", "mm", 0), SUSP_REAR("Rear suspension", "mm", 0),
        SUSP_SPEED_FRONT("Front suspension speed (unverified)", "", 2),
        SUSP_SPEED_REAR("Rear suspension speed (unverified)", "", 2),
        LONGITUDINAL("Acceleration, forward", "g", 2), LATERAL("Acceleration, sideways", "g", 2),
        VERTICAL("Acceleration, up", "g", 2),
        WHEEL_FRONT("Front wheel (filtered)", "km/h", 0), WHEEL_REAR("Rear wheel (filtered)", "km/h", 0),
        REAR_SLIP("Rear wheel slip", "%", 0), WHEELIE_ANGLE("Wheelie angle", "°", 0);

        public final String title, unit;
        /** Decimals on the axis and in the line under the chart. */
        public final int decimals;
        /** The names of its lines when it draws more than one, for the legend; empty for one line. */
        public final String[] lines;

        Series(String title, String unit, int decimals, String... lines) {
            this.title = title;
            this.unit = unit;
            this.decimals = decimals;
            this.lines = lines;
        }

        /** How many lines it draws. */
        public int lineCount() {
            return Math.max(1, lines.length);
        }
    }

    public interface OnPoint {
        void onSample(int index);
    }

    /** Told the stretch of the ride the chart shows, whenever it is zoomed or moved. */
    public interface OnWindow {
        void onWindow(long fromMs, long toMs);
    }

    /** The chart does not zoom in closer than this: a few seconds would be a handful of points. */
    static final long MIN_WINDOW_MS = 20_000;
    /** Below this span the time axis reads minutes and seconds instead of whole minutes. */
    private static final long CLOCK_SPAN_MS = 10 * 60_000;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final boolean night;
    private final float density;
    private final ScaleGestureDetector scaler;
    private final GestureDetector taps;

    private List<RideTsv.Sample> samples;
    private Series series = Series.SPEED;
    private OnPoint listener;
    private OnWindow windowListener;
    private int marked = -1;
    /** The stretch of the ride shown, in ms since its start: all of it until the rider zooms in. */
    private long viewFrom, viewTo = 1;
    /** A second finger came down in this gesture: the one left does not move the mark until it is lifted. */
    private boolean multiTouch;
    private float lastFocusX;

    public ChartView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        float d = density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(d * 2);
        night = Ui.night(context);
        line.setColor(Ui.accent(night));
        grid.setColor(Ui.chartGrid(night));
        grid.setStrokeWidth(1);
        text.setColor(Ui.chartInk(night));
        text.setTextSize(d * 11);
        dot.setStyle(Paint.Style.FILL);
        mark.setStrokeWidth(d * 2);
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector g) {
                lastFocusX = g.getFocusX();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector g) {
                // the time axis only: how far apart the fingers are across, not up and down
                float before = g.getPreviousSpanX(), now = g.getCurrentSpanX();
                zoom(g.getFocusX(), before > density * 10 ? now / before : 1);
                pan(g.getFocusX() - lastFocusX);     // two fingers moving together move the stretch
                lastFocusX = g.getFocusX();
                return true;
            }
        });
        scaler.setQuickScaleEnabled(false);   // a double tap zooms in, see below
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return performClick();
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoom(e.getX(), 2);
                return true;
            }
        });
    }

    /**
     * What to draw. A new ride shows all of it; the same ride with another value keeps the stretch it is
     * zoomed to, so two values can be compared over the same seconds.
     */
    public void set(List<RideTsv.Sample> samples, Series series, OnPoint listener) {
        boolean newRide = samples != this.samples;
        this.samples = samples;
        this.series = series;
        this.listener = listener;
        this.marked = -1;
        if (newRide) {
            viewFrom = 0;
            viewTo = samples == null || samples.isEmpty() ? 1 : total();
            if (windowListener != null) windowListener.onWindow(viewFrom, viewTo);
        }
        invalidate();
    }

    public void setOnWindow(OnWindow listener) {
        windowListener = listener;
    }

    /** Back to the whole ride. */
    public void showAll() {
        if (samples == null || samples.size() < 2) return;
        setView(0, total());
    }

    @Override
    public boolean performClick() {
        return super.performClick();   // a tap marks the second (onTouchEvent); accessibility sees a click
    }

    private long total() {
        return Math.max(1, samples.get(samples.size() - 1).tMs);
    }

    private float plotLeft() {
        return density * 42;
    }

    private float plotWidth() {
        return Math.max(1, getWidth() - plotLeft() - density * 8);
    }

    /** The time under a point of the chart, in ms since the start. */
    private double timeAt(float x) {
        return viewFrom + (x - plotLeft()) / plotWidth() * (double) (viewTo - viewFrom);
    }

    /** Zooms the time axis by {@code factor} keeping the second under {@code x} where it is. */
    private void zoom(float x, double factor) {
        if (samples == null || samples.size() < 2 || !(factor > 0)) return;
        long total = total();
        double span = viewTo - viewFrom;
        double at = timeAt(x);
        double next = Math.max(Math.min(MIN_WINDOW_MS, total), Math.min(total, span / factor));
        setView(at - (at - viewFrom) / span * next, next);
    }

    /** Moves the stretch shown by {@code dx} pixels (to the right: earlier seconds come in). */
    private void pan(float dx) {
        if (samples == null || samples.size() < 2) return;
        double span = viewTo - viewFrom;
        setView(viewFrom - dx / plotWidth() * span, span);
    }

    private void setView(double from, double span) {
        long[] w = window(from, span, total());
        if (w[0] == viewFrom && w[1] == viewTo) return;
        viewFrom = w[0];
        viewTo = w[1];
        invalidate();
        if (windowListener != null) windowListener.onWindow(viewFrom, viewTo);
    }

    /** {from, to} of a stretch {@code span} long starting at {@code from}, kept inside the ride (0 … total). */
    static long[] window(double from, double span, long total) {
        span = Math.max(1, Math.min(total, span));
        from = Math.max(0, Math.min(total - span, from));
        long f = Math.round(from);
        return new long[]{f, Math.min(total, Math.round(from + span))};
    }

    public void mark(int index) {
        marked = index;
        invalidate();
    }

    /** The value of a sample for this series (its first line), NaN when the ride has none. */
    static double value(RideTsv.Sample s, Series series) {
        return value(s, series, 0);
    }

    /** The value of one of the series' lines, NaN when the ride has none (or the series has no such line). */
    static double value(RideTsv.Sample s, Series series, int line) {
        if (series == Series.TORQUE) {
            switch (line) {
                case 0: return s.torque_rider;
                case 1: return s.torque_req;
                case 2: return s.torque_actual;
                default: return Double.NaN;
            }
        }
        if (line != 0) return Double.NaN;
        switch (series) {
            case SPEED: return s.kmh;
            case GPS_SPEED: return s.gpsKmh;
            // the rotation rates, turned as the accelerations below (Rideology's column names: p3 is the
            // IMU's x rate, p2 its y, p1 its z)
            case ROLL_RATE: return BikeFrames.ImuSusp.roll(s.imu_ax_psip3, s.imu_az_psip2);
            case YAW_RATE: return BikeFrames.ImuSusp.yaw(s.imu_ax_psip3, s.imu_az_psip2);
            case PITCH_RATE: return BikeFrames.ImuSusp.pitch(s.imu_ay_psip1);
            case FUEL_RATE: return s.fuelRate;
            case BRAKE_F_MASTER: return s.brake_f_master;
            case BRAKE_R_MASTER: return s.brake_r_master;
            case BRAKE_R_CALIPER: return s.brake_r_caliper;
            case SUSP_SPEED_FRONT: return s.susp_f_stroke_vp;
            case SUSP_SPEED_REAR: return s.susp_r_stroke_vp;
            case REAR_SLIP: return Ride.rearSlip(s.wheel_f_kmh, s.wheel_r_kmh);
            case WHEELIE_ANGLE: return s.wheelie_deg;
            case RPM: return s.rpm;
            case GEAR: return s.gear;
            case THROTTLE: return s.thrPct;
            case LEAN: return s.lean;
            case WATER: return s.waterC;
            case ACCEL: return s.accG;
            case OIL: return s.oilC;
            case INTAKE: return s.intakeC;
            case FUEL: return s.fuelInst;
            case TYRE_FRONT: return s.tyreFrontKpa / 100;
            case TYRE_REAR: return s.tyreRearKpa / 100;
            case BATTERY: return s.batteryV;
            case OUTSIDE: return s.outsideC;
            case BRAKE: return s.brakeFrontBar;
            case ALTITUDE: return s.altM;
            case SUSP_FRONT: return s.susp_f_stroke_mm;
            case SUSP_REAR: return s.susp_r_stroke_mm;
            // the IMU is mounted at 45°: these come from the same two raw columns, turned (see PROTOCOL.md)
            case LONGITUDINAL: return BikeFrames.ImuSusp.longitudinal(s.imu_ax_g, s.imu_ay_g);
            case LATERAL: return BikeFrames.ImuSusp.lateral(s.imu_az_g);
            case VERTICAL: return BikeFrames.ImuSusp.vertical(s.imu_ax_g, s.imu_ay_g);
            case WHEEL_FRONT: return s.wheel_f_kmh;
            case WHEEL_REAR: return s.wheel_r_kmh;
            default: return Double.NaN;
        }
    }

    /** True when the sample has a value for any of the series' lines. */
    static boolean has(RideTsv.Sample s, Series series) {
        for (int l = 0; l < series.lineCount(); l++) {
            double v = value(s, series, l);
            if (v == v) return true;
        }
        return false;
    }

    /** "Yaw rate +3.2", "Torque 40 / 38 / 36": the series at one second, for the line under the chart. */
    static String describe(RideTsv.Sample s, Series series) {
        StringBuilder sb = new StringBuilder(series.title).append(' ');
        String f = "%." + series.decimals + "f";
        for (int l = 0; l < series.lineCount(); l++) {
            if (l > 0) sb.append(" / ");
            double v = value(s, series, l);
            sb.append(v == v ? String.format(Locale.ROOT, f, v) : "-");
        }
        if (!series.unit.isEmpty()) sb.append(' ').append(series.unit);
        return sb.toString();
    }

    /**
     * The value the track on the map is coloured by: the series' last line, the one drawn in the accent
     * colour (for the torque, what the engine gives).
     */
    static double mapValue(RideTsv.Sample s, Series series) {
        return value(s, series, series.lineCount() - 1);
    }

    /** One line alone in the accent colour; of several, the last (what the engine gives) keeps it. */
    private int lineColour(int l, int count) {
        if (count == 1 || l == count - 1) return Ui.accent(night);
        return l == count - 2 ? Ui.warn(night) : Ui.inkSecondary(night);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (samples == null || samples.size() < 2) return;
        float d = getResources().getDisplayMetrics().density;
        int count = series.lineCount();
        // several lines: room at the top for their legend
        float padL = d * 42, padR = d * 8, padT = d * (count > 1 ? 18 : 6), padB = d * 18;
        float w = getWidth() - padL - padR, h = getHeight() - padT - padB;
        if (w <= 0 || h <= 0) return;
        long total = total();
        if (viewTo <= viewFrom || viewTo > total) { // a ride that grew, or none shown yet: all of it
            viewFrom = 0;
            viewTo = total;
        }
        double span = viewTo - viewFrom;
        // the seconds shown, and one either side so the lines run to the edges
        int first = Math.max(0, firstAtOrAfter(samples, viewFrom) - 1);
        int last = Math.min(samples.size() - 1, firstAtOrAfter(samples, viewTo + 1));

        // the lines share one scale, so they can be compared; zoomed in, it is the scale of what is shown
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int i = first; i <= last; i++) {
            RideTsv.Sample s = samples.get(i);
            if (s.tMs < viewFrom || s.tMs > viewTo) continue;
            for (int l = 0; l < count; l++) {
                double v = value(s, series, l);
                if (v != v) continue;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (min > max) {        // nothing in this stretch: an empty chart with its time axis
            min = 0;
            max = 1;
        }
        if (max - min < 1e-6) { // a flat line in the middle
            min -= 1;
            max += 1;
        }
        String f = "%." + series.decimals + "f";
        canvas.drawLine(padL, padT + h, padL + w, padT + h, grid);
        canvas.drawText((String.format(Locale.ROOT, f, max) + " " + series.unit).trim(), d * 2, padT + d * 10, text);
        canvas.drawText(String.format(Locale.ROOT, f, min), d * 2, padT + h, text);

        canvas.save();
        canvas.clipRect(padL, 0, padL + w, getHeight());   // the points either side end at the edges
        for (int l = 0; l < count; l++) {
            boolean start = true;
            path.reset();
            for (int i = first; i <= last; i++) {
                RideTsv.Sample s = samples.get(i);
                double v = value(s, series, l);
                if (v != v) {
                    start = true;
                    continue;
                }
                float x = (float) (padL + w * ((s.tMs - viewFrom) / span));
                float y = (float) (padT + h - (v - min) / (max - min) * h);
                if (start) {
                    path.moveTo(x, y);
                    start = false;
                } else {
                    path.lineTo(x, y);
                }
            }
            line.setColor(lineColour(l, count));
            canvas.drawPath(path, line);
        }
        // where traction control or ABS stepped in: short marks along the bottom
        for (int i = first; i <= last; i++) {
            RideTsv.Sample s = samples.get(i);
            // >= 0: rides recorded before 0.8 wrote -1 for "the bike sent nothing"
            boolean tcs = (s.tcs == s.tcs && s.tcs > 0) || (s.tcs_hi == s.tcs_hi && s.tcs_hi > 0)
                    || (s.tcs_hb == s.tcs_hb && s.tcs_hb > 0) || (s.tcs_lb == s.tcs_lb && s.tcs_lb > 0);
            boolean abs = s.abs == s.abs && s.abs >= 0 && ((int) s.abs & 0x03) != 0;
            if (!tcs && !abs) continue;
            mark.setColor(abs ? Ui.recording() : Ui.warn(night));
            float x = (float) (padL + w * ((s.tMs - viewFrom) / span));
            canvas.drawLine(x, padT + h - d * 6, x, padT + h, mark);
        }
        canvas.restore();
        if (count > 1) legend(canvas, d, padL + w);
        canvas.drawText(axisLabel(viewFrom, total, (long) span), padL, padT + h + d * 13, text);
        String endLabel = axisLabel(viewTo, total, (long) span);
        canvas.drawText(endLabel, padL + w - text.measureText(endLabel), padT + h + d * 13, text);
        if (marked >= 0 && marked < samples.size()) {
            long t = samples.get(marked).tMs;
            if (t < viewFrom || t > viewTo) return;   // the second picked is outside the stretch shown
            float x = (float) (padL + w * ((t - viewFrom) / span));
            canvas.drawLine(x, padT, x, padT + h, grid);
            for (int l = 0; l < count; l++) {
                double v = value(samples.get(marked), series, l);
                if (v != v) continue;
                float y = (float) (padT + h - (v - min) / (max - min) * h);
                dot.setColor(count == 1 ? Ui.markerOnTrack() : lineColour(l, count));
                canvas.drawCircle(x, y, d * 4, dot);
            }
        }
    }

    /** The first sample at or after {@code tMs} (samples are in time order); the count when there is none. */
    static int firstAtOrAfter(List<RideTsv.Sample> samples, long tMs) {
        int lo = 0, hi = samples.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).tMs < tMs) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** The sample closest in time to {@code tMs}. */
    static int nearest(List<RideTsv.Sample> samples, long tMs) {
        int i = firstAtOrAfter(samples, tMs);
        if (i >= samples.size()) return samples.size() - 1;
        if (i > 0 && tMs - samples.get(i - 1).tMs <= samples.get(i).tMs - tMs) return i - 1;
        return i;
    }

    /** The names of the lines, right-aligned along the top, each after a dot of its colour. */
    private void legend(Canvas canvas, float d, float right) {
        float y = d * 11, x = right;
        for (int l = series.lines.length - 1; l >= 0; l--) {
            String name = series.lines[l];
            x -= text.measureText(name);
            canvas.drawText(name, x, y, text);
            x -= d * 8;
            dot.setColor(lineColour(l, series.lines.length));
            canvas.drawCircle(x + d * 3, y - d * 3.5f, d * 3, dot);
            x -= d * 10;
        }
    }

    /** Wall-clock time of the first sample: over days and months the axis then shows dates, not minutes. */
    private long epochAtZero;

    /** Tells the chart that {@code tMs} 0 is this moment, so a long span can be labelled with dates. */
    public void setEpochAtZero(long epochMs) {
        epochAtZero = epochMs;
        invalidate();
    }

    /**
     * "12 Oct" when the chart spans days ({@code total}), else the time into the ride: "1 h 05 min", or
     * "1:05:30" / "12:30" once the stretch shown ({@code span}) is short enough for seconds to matter.
     */
    private String axisLabel(long ms, long total, long span) {
        if (epochAtZero > 0 && total > 3L * 24 * 60 * 60_000) {
            // the rider's own month names: ROOT has none and falls back to "M10"
            return new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                    .format(new java.util.Date(epochAtZero + ms));
        }
        return span < CLOCK_SPAN_MS ? clock(ms) : minutes(ms);
    }

    static String minutes(long ms) {
        long min = ms / 60_000;
        return min >= 60 ? String.format(Locale.ROOT, "%d h %02d min", min / 60, min % 60) : min + " min";
    }

    static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (samples == null || samples.size() < 2 || listener == null) return false;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) multiTouch = false;
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true;
            // two fingers zoom the chart: the page must not take them for a scroll
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        scaler.onTouchEvent(e);
        taps.onTouchEvent(e);
        // one finger marks the second under it, as before the chart could zoom
        if (!multiTouch && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)) {
            double t = Math.max(viewFrom, Math.min(viewTo, timeAt(e.getX())));
            int index = nearest(samples, Math.round(t));
            mark(index);
            listener.onSample(index);
        }
        return true;
    }
}
