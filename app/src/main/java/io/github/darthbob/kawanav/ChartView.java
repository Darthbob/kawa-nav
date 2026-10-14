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

    /** Which recorded value to draw. */
    public enum Series {
        SPEED("Speed", "km/h"), RPM("Engine", "rpm"), GEAR("Gear", ""), THROTTLE("Throttle", "%"),
        LEAN("Lean angle", "°"), WATER("Coolant", "°C"), ACCEL("Acceleration", "g");

        public final String title, unit;

        Series(String title, String unit) {
            this.title = title;
            this.unit = unit;
        }
    }

    public interface OnPoint {
        void onSample(int index);
    }

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private List<RideTsv.Sample> samples;
    private Series series = Series.SPEED;
    private OnPoint listener;
    private int marked = -1;

    public ChartView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(d * 2);
        boolean night = Ui.night(context);
        line.setColor(Ui.accent(night));
        grid.setColor(Ui.chartGrid(night));
        grid.setStrokeWidth(1);
        text.setColor(Ui.chartInk(night));
        text.setTextSize(d * 11);
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

    /** The value of a sample for this series, NaN when the ride has none. */
    static double value(RideTsv.Sample s, Series series) {
        switch (series) {
            case SPEED: return s.kmh;
            case RPM: return s.rpm;
            case GEAR: return s.gear;
            case THROTTLE: return s.thrPct;
            case LEAN: return s.lean;
            case WATER: return s.waterC;
            case ACCEL: return s.accG;
            default: return Double.NaN;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (samples == null || samples.size() < 2) return;
        float d = getResources().getDisplayMetrics().density;
        float padL = d * 42, padR = d * 8, padT = d * 6, padB = d * 18;
        float w = getWidth() - padL - padR, h = getHeight() - padT - padB;
        if (w <= 0 || h <= 0) return;

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (RideTsv.Sample s : samples) {
            double v = value(s, series);
            if (v != v) continue;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (min > max) return;
        if (max - min < 1e-6) { // a flat line in the middle
            min -= 1;
            max += 1;
        }
        canvas.drawLine(padL, padT + h, padL + w, padT + h, grid);
        canvas.drawText(String.format(Locale.ROOT, "%.0f %s", max, series.unit), d * 2, padT + d * 10, text);
        canvas.drawText(String.format(Locale.ROOT, "%.0f", min), d * 2, padT + h, text);

        long total = Math.max(1, samples.get(samples.size() - 1).tMs);
        float prevX = 0, prevY = 0;
        boolean first = true;
        path.reset();
        for (RideTsv.Sample s : samples) {
            double v = value(s, series);
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
            prevX = x;
            prevY = y;
        }
        canvas.drawPath(path, line);
        canvas.drawText(minutes(0), padL, padT + h + d * 13, text);
        String endLabel = minutes(total);
        canvas.drawText(endLabel, padL + w - text.measureText(endLabel), padT + h + d * 13, text);
        if (marked >= 0 && marked < samples.size()) {
            float x = padL + w * (samples.get(marked).tMs / (float) total);
            canvas.drawLine(x, padT, x, padT + h, grid);
            double v = value(samples.get(marked), series);
            if (v == v) {
                float y = (float) (padT + h - (v - min) / (max - min) * h);
                Paint p = new Paint(line);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.markerOnTrack());
                canvas.drawCircle(x, y, d * 4, p);
            }
        }
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
