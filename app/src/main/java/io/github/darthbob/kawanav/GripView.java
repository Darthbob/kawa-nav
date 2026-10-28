package io.github.darthbob.kawanav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The grip a ride used, one dot per riding second ({@link RideAnalysis}): sideways from the lean, left
 * and right, against the change of speed, braking down and accelerating up. Rings every half g, so how
 * far a second went towards the tyres' limit, and whether the rider braked or opened the throttle while
 * leant over, can be read off the cloud.
 */
public final class GripView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float[] side = new float[0], forward = new float[0];
    /** The outer edge of the drawing, in g: the next half g above the furthest second, at least 1 g. */
    private double reach = 1;

    public GripView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        boolean night = Ui.night(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1);
        ring.setColor(Ui.chartGrid(night));
        text.setColor(Ui.chartInk(night));
        text.setTextSize(density * 11);
        dot.setStyle(Paint.Style.FILL);
        // many seconds fall on the same spot: each dot faint, so the dense middle shows as dense
        dot.setColor(Ui.accent(night) & 0x00FFFFFF | 0x59000000);
    }

    public void set(RideAnalysis a) {
        side = a.sideG;
        forward = a.forwardG;
        double far = 0;
        for (int i = 0; i < side.length; i++) far = Math.max(far, Math.hypot(side[i], forward[i]));
        reach = Math.max(1, Math.ceil(far * 2) / 2);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float label = text.getTextSize() * 1.4f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - label;
        if (r <= 0) return;
        float perG = (float) (r / reach);
        canvas.drawLine(cx - r, cy, cx + r, cy, ring);
        canvas.drawLine(cx, cy - r, cx, cy + r, ring);
        text.setTextAlign(Paint.Align.LEFT);
        for (double g = 0.5; g <= reach + 1e-9; g += 0.5) {
            canvas.drawCircle(cx, cy, (float) (g * perG), ring);
            canvas.drawText(String.format(java.util.Locale.ROOT, g == Math.rint(g) ? "%.0f g" : "%.1f g", g),
                    cx + (float) (g * perG * 0.71) + density * 2, cy - (float) (g * perG * 0.71) - density * 2, text);
        }
        float d = Math.max(1.5f, density * 1.6f);
        for (int i = 0; i < side.length; i++) {
            canvas.drawCircle(cx + side[i] * perG, cy - forward[i] * perG, d, dot);
        }
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("accelerating", cx, cy - r - density * 4, text);
        canvas.drawText("braking", cx, cy + r + label - density * 2, text);
        float gap = density * 6;
        boolean beside = cx - r > text.measureText("right") + gap;
        float y = beside ? cy + text.getTextSize() * 0.35f : cy - density * 4;
        text.setTextAlign(beside ? Paint.Align.RIGHT : Paint.Align.LEFT);
        canvas.drawText("left", beside ? cx - r - gap : cx - r, y, text);
        text.setTextAlign(beside ? Paint.Align.LEFT : Paint.Align.RIGHT);
        canvas.drawText("right", beside ? cx + r + gap : cx + r, y, text);
    }
}
