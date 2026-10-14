package io.github.darthbob.kawanav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A gauge made of segments, like the fuel gauge on the dash and in Rideology: {@code filled} of
 * {@code count} segments lit (lime in the dark theme, violet to green in the light one).
 */
public class SegmentBar extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final boolean night;
    private int filled, count = 6;

    public SegmentBar(Context c) {
        super(c);
        night = Ui.night(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void set(int filled, int count) {
        if (filled == this.filled && count == this.count) return;
        this.filled = filled;
        this.count = Math.max(1, count);
        setContentDescription(filled < 0 ? "Not known" : filled + " of " + this.count);
        invalidate();
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
            paint.setColor(i < filled ? Ui.gaugeFill(night, i, count) : Ui.gaugeEmpty(night));
            canvas.drawRoundRect(r, radius, radius, paint);
        }
    }
}
