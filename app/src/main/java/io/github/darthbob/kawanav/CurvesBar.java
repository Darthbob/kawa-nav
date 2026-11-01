package io.github.darthbob.kawanav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * How winding the route ahead is ({@link Navigator#curvesAhead}), as one bar from the rider (left) to
 * the end of the stretch (right), in the map's colours: blue straight, green sweeping, orange winding,
 * red very winding.
 */
public final class CurvesBar extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path round = new Path();
    private final int empty;
    private List<double[]> runs = Collections.emptyList();
    private double metres = 1;

    public CurvesBar(Context context) {
        super(context);
        empty = Ui.gaugeEmpty(Ui.night(context));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the label next to it says it
    }

    /** Runs of {from, to, level} in metres ahead, over a stretch of {@code metres}. */
    public void set(List<double[]> runs, double metres) {
        this.runs = runs == null ? Collections.emptyList() : runs;
        this.metres = Math.max(1, metres);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        round.reset();
        round.addRoundRect(0, 0, w, h, h / 2, h / 2, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(round);
        canvas.drawColor(empty);
        for (double[] r : runs) {
            paint.setColor(RouteMapView.LEVEL_COLOURS[(int) r[2]]);
            canvas.drawRect((float) (r[0] / metres * w), 0, (float) Math.ceil(r[1] / metres * w), h, paint);
        }
        canvas.restore();
    }
}
