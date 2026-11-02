package io.github.darthbob.kawanav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A gauge made of segments, like the fuel gauge on the dash and in Rideology: {@code filled} of
 * {@code count} segments lit (lime in both themes). On reserve, like the dash, only the first segment,
 * orange and blinking ({@link #setReserve}).
 */
public class SegmentBar extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final boolean night;
    private int filled, count = 6;
    /** On reserve, and whether the blinking segment is lit right now. */
    private boolean reserve, lit = true;
    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            lit = !lit;
            invalidate();
            postDelayed(this, BLINK_MS);
        }
    };
    /** Half a blink: on, then off for as long. */
    private static final long BLINK_MS = 500;

    public SegmentBar(Context c) {
        super(c);
        night = Ui.night(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void set(int filled, int count) {
        if (filled == this.filled && count == this.count) return;
        this.filled = filled;
        this.count = Math.max(1, count);
        describe();
        invalidate();
    }

    /** Shows the reserve: the first segment orange, blinking while the gauge is on screen. */
    public void setReserve(boolean on) {
        if (on == reserve) return;
        reserve = on;
        lit = true;
        describe();
        blinking(on && isAttachedToWindow() && isShown());
        invalidate();
    }

    private void describe() {
        setContentDescription(reserve ? "Reserve" : filled < 0 ? "Not known" : filled + " of " + count);
    }

    private void blinking(boolean on) {
        removeCallbacks(blink);
        if (on) postDelayed(blink, BLINK_MS);
        else lit = true;
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        // no blinking behind another screen or with the phone locked
        blinking(reserve && isVisible);
    }

    @Override
    protected void onDetachedFromWindow() {
        blinking(false);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float gap = Ui.dp(getContext(), 3);
        float w = (getWidth() - getPaddingLeft() - getPaddingRight() - gap * (count - 1)) / count;
        float top = getPaddingTop(), bottom = getHeight() - getPaddingBottom();
        float radius = Ui.dp(getContext(), 2);
        for (int i = 0; i < count; i++) {
            float left = getPaddingLeft() + i * (w + gap);
            r.set(left, top, left + w, bottom);
            if (reserve) paint.setColor(i == 0 && lit ? Ui.warnFill() : Ui.gaugeEmpty(night));
            else paint.setColor(i < filled ? Ui.gaugeFill(night, i, count) : Ui.gaugeEmpty(night));
            canvas.drawRoundRect(r, radius, radius, paint);
        }
    }
}
