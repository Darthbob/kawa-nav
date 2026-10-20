package io.github.darthbob.kawanav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;
import java.util.Locale;

/**
 * One recorded value over the ride: a line with its range on the left and the time along the bottom.
 * Touching it marks the second, so the track and the values can be looked at together.
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
        // the rotation rates' scale is not established (PROTOCOL.md, 0x4B): numbers without a unit
        ROLL_RATE("Roll rate", "", 1), YAW_RATE("Yaw rate", "", 1), IMU_RATE1("IMU rate 1 (unverified)", "", 1),
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

    /** Below this front-wheel speed the slip is not worked out: a ratio of two crawling wheels says nothing. */
    static final double SLIP_MIN_KMH = 10;

    public interface OnPoint {
        void onSample(int index);
    }

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final boolean night;

    private List<RideTsv.Sample> samples;
    private Series series = Series.SPEED;
    private OnPoint listener;
    private int marked = -1;

    public ChartView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
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
    }

    public void set(List<RideTsv.Sample> samples, Series series, OnPoint listener) {
        this.samples = samples;
        this.series = series;
        this.listener = listener;
        this.marked = -1;
        invalidate();
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
            case ROLL_RATE: return s.imu_ax_psip3;
            case YAW_RATE: return s.imu_az_psip2;
            case IMU_RATE1: return s.imu_ay_psip1;
            case FUEL_RATE: return s.fuelRate;
            case BRAKE_F_MASTER: return s.brake_f_master;
            case BRAKE_R_MASTER: return s.brake_r_master;
            case BRAKE_R_CALIPER: return s.brake_r_caliper;
            case SUSP_SPEED_FRONT: return s.susp_f_stroke_vp;
            case SUSP_SPEED_REAR: return s.susp_r_stroke_vp;
            case REAR_SLIP: return rearSlip(s.wheel_f_kmh, s.wheel_r_kmh);
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

    /**
     * How much faster the rear wheel turns than the front, in % of the front: positive while the rear
     * spins up under power, negative while it is braked harder. NaN below {@link #SLIP_MIN_KMH} or
     * without both filtered wheel speeds.
     */
    static double rearSlip(double frontKmh, double rearKmh) {
        if (frontKmh != frontKmh || rearKmh != rearKmh || frontKmh < SLIP_MIN_KMH) return Double.NaN;
        return (rearKmh - frontKmh) / frontKmh * 100;
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

        // the lines share one scale, so they can be compared
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (RideTsv.Sample s : samples) {
            for (int l = 0; l < count; l++) {
                double v = value(s, series, l);
                if (v != v) continue;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (min > max) return;
        if (max - min < 1e-6) { // a flat line in the middle
            min -= 1;
            max += 1;
        }
        String f = "%." + series.decimals + "f";
        canvas.drawLine(padL, padT + h, padL + w, padT + h, grid);
        canvas.drawText((String.format(Locale.ROOT, f, max) + " " + series.unit).trim(), d * 2, padT + d * 10, text);
        canvas.drawText(String.format(Locale.ROOT, f, min), d * 2, padT + h, text);

        long total = Math.max(1, samples.get(samples.size() - 1).tMs);
        for (int l = 0; l < count; l++) {
            boolean first = true;
            path.reset();
            for (RideTsv.Sample s : samples) {
                double v = value(s, series, l);
                if (v != v) {
                    first = true;
                    continue;
                }
                float x = padL + w * (s.tMs / (float) total);
                float y = (float) (padT + h - (v - min) / (max - min) * h);
                if (first) {
                    path.moveTo(x, y);
                    first = false;
                } else {
                    path.lineTo(x, y);
                }
            }
            line.setColor(lineColour(l, count));
            canvas.drawPath(path, line);
        }
        if (count > 1) legend(canvas, d, padL + w);
        // where traction control or ABS stepped in: short marks along the bottom
        for (RideTsv.Sample s : samples) {
            // >= 0: rides recorded before 0.8 wrote -1 for "the bike sent nothing"
            boolean tcs = (s.tcs == s.tcs && s.tcs > 0) || (s.tcs_hi == s.tcs_hi && s.tcs_hi > 0)
                    || (s.tcs_hb == s.tcs_hb && s.tcs_hb > 0) || (s.tcs_lb == s.tcs_lb && s.tcs_lb > 0);
            boolean abs = s.abs == s.abs && s.abs >= 0 && ((int) s.abs & 0x03) != 0;
            if (!tcs && !abs) continue;
            mark.setColor(abs ? Ui.recording() : Ui.warn(night));
            float x = padL + w * (s.tMs / (float) total);
            canvas.drawLine(x, padT + h - d * 6, x, padT + h, mark);
        }
        canvas.drawText(axisLabel(0, total), padL, padT + h + d * 13, text);
        String endLabel = axisLabel(total, total);
        canvas.drawText(endLabel, padL + w - text.measureText(endLabel), padT + h + d * 13, text);
        if (marked >= 0 && marked < samples.size()) {
            float x = padL + w * (samples.get(marked).tMs / (float) total);
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

    /** "12 Oct" when the chart spans days, else the minutes of a ride. */
    private String axisLabel(long ms, long total) {
        if (epochAtZero > 0 && total > 3L * 24 * 60 * 60_000) {
            // the rider's own month names: ROOT has none and falls back to "M10"
            return new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                    .format(new java.util.Date(epochAtZero + ms));
        }
        return minutes(ms);
    }

    private static String minutes(long ms) {
        long min = ms / 60_000;
        return min >= 60 ? min / 60 + " h" : min + " min";
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (samples == null || samples.size() < 2 || listener == null) return false;
        if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
            float d = getResources().getDisplayMetrics().density;
            float padL = d * 42, padR = d * 8;
            float f = (e.getX() - padL) / Math.max(1, getWidth() - padL - padR);
            int index = (int) Math.round(Math.max(0, Math.min(1, f)) * (samples.size() - 1));
            mark(index);
            listener.onSample(index);
            return true;
        }
        return super.onTouchEvent(e);
    }
}
