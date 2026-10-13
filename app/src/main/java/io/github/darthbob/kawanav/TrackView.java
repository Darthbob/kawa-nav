package io.github.darthbob.kawanav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.List;

/**
 * The ride's track: the recorded positions joined, coloured by speed (blue slow, green, orange, red
 * fast). A simple view drawing what it is given — no map underneath, so it works offline.
 */
public final class TrackView extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private List<RideTsv.Sample> samples;
    private int marked = -1;

    public TrackView(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(context.getResources().getDisplayMetrics().density * 3);
        marker.setStyle(Paint.Style.FILL);
    }

    /** The ride to draw; {@code marked} (a sample index) gets a dot, -1 for none. */
    public void set(List<RideTsv.Sample> samples, int marked) {
        this.samples = samples;
        this.marked = marked;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (samples == null || samples.isEmpty()) return;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (RideTsv.Sample s : samples) {
            if (!s.hasGps()) continue;
            minLat = Math.min(minLat, s.lat);
            maxLat = Math.max(maxLat, s.lat);
            minLon = Math.min(minLon, s.lon);
            maxLon = Math.max(maxLon, s.lon);
        }
        if (minLat > maxLat) return; // no positions at all
        double centerLat = Math.toRadians((minLat + maxLat) / 2);
        double spanX = Math.max(1e-6, (maxLon - minLon) * Math.cos(centerLat));
        double spanY = Math.max(1e-6, maxLat - minLat);
        float pad = getResources().getDisplayMetrics().density * 12;
        float w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
        // keep the shape's proportions, as with a map
        double scale = Math.min(w / spanX, h / spanY);
        float offX = (float) (pad + (w - spanX * scale) / 2);
        float offY = (float) (pad + (h - spanY * scale) / 2);

        double maxSpeed = 1;
        for (RideTsv.Sample s : samples) if (s.kmh == s.kmh) maxSpeed = Math.max(maxSpeed, s.kmh);

        float prevX = 0, prevY = 0;
        boolean first = true;
        for (int i = 0; i < samples.size(); i++) {
            RideTsv.Sample s = samples.get(i);
            if (!s.hasGps()) {
                first = true; // a gap: start a new piece
                continue;
            }
            float x = (float) (offX + (s.lon - minLon) * Math.cos(centerLat) * scale);
            float y = (float) (offY + (maxLat - s.lat) * scale);
            if (!first) {
                line.setColor(colour(s.kmh, maxSpeed, s.lean));
                canvas.drawLine(prevX, prevY, x, y, line);
            }
            prevX = x;
            prevY = y;
            first = false;
            if (i == marked) {
                marker.setColor(Ui.markerOnTrack());
                float r = getResources().getDisplayMetrics().density * 5;
                canvas.drawCircle(x, y, r, marker);
            }
        }
        // where the ride started
        for (RideTsv.Sample s : samples) {
            if (!s.hasGps()) continue;
            marker.setColor(Ui.accent());
            float r = getResources().getDisplayMetrics().density * 6;
            canvas.drawCircle((float) (offX + (s.lon - minLon) * Math.cos(centerLat) * scale),
                    (float) (offY + (maxLat - s.lat) * scale), r, marker);
            break;
        }
    }

    /** Slow blue, then green, orange, red for the fastest part. Leaning hard turns the line's ends darker. */
    static int colour(double kmh, double maxSpeed, double lean) {
        if (kmh != kmh) return 0xFF9E9E9E;
        double f = kmh / maxSpeed;
        if (f < 0.35) return 0xFF3D7BFF;
        if (f < 0.6) return 0xFF43A047;
        if (f < 0.85) return 0xFFFB8C00;
        return 0xFFE53935;
    }
}
