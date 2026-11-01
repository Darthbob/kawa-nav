package io.github.darthbob.kawanav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/**
 * A maneuver as an arrow, drawn from its dash turn id ({@link KawaProtocol#TURN_NAMES}): the next one
 * on the navigation screen, big beside its distance, and the one after it, small. Turns bend the arrow
 * by their angle; forks, ramps and exits show the way not taken faintly; roundabouts are a ring with
 * the exit number; the destination is a flag and a via point its number. Drawn in a 100 × 100 box.
 */
public final class TurnIcon extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint faint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path head = new Path();
    private int onFill;
    private int turn = KawaProtocol.TURN_NONE;

    /** In the accent colour; see {@link #colours}. */
    public TurnIcon(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(11);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        faint.set(line);
        faint.setStrokeWidth(8);
        fill.setStyle(Paint.Style.FILL);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        boolean night = Ui.night(context);
        colours(Ui.accent(night), Ui.onAccent(night));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the text next to it says it
    }

    /** The arrow's colour, and that of a via point's number on it. */
    public void colours(int colour, int onColour) {
        line.setColor(colour);
        faint.setColor(colour & 0x00FFFFFF | 0x55000000);
        fill.setColor(colour);
        label.setColor(colour);
        onFill = onColour;
        invalidate();
    }

    /** The turn id to show; {@link KawaProtocol#TURN_NONE}: nothing. */
    public void setTurn(int t) {
        if (t == turn) return;
        turn = t;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0 || turn == KawaProtocol.TURN_NONE) return;
        canvas.save();
        canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
        canvas.scale(size / 100, size / 100);
        draw(canvas, turn);
        canvas.restore();
    }

    private void draw(Canvas c, int t) {
        switch (t) {
            case 0: case 11: case 42: bend(c, 0, 0, false); return;      // straight, fork middle, departure
            case 1: case 2: bend(c, 90, side(t), false); return;
            case 3: case 4: bend(c, 135, side(t), false); return;
            case 5: case 6: bend(c, 45, side(t), false); return;
            case 7: case 8: uTurn(c, side(t)); return;
            case 9: case 10: case 12: case 13: case 14: case 15: bend(c, 40, side(t), true); return;
            case 16: case 17: merge(c, side(t)); return;
            case 43: flag(c); return;
            default:
                if (t >= 18 && t <= 29) roundabout(c, t - 17, false);
                else if (t >= 30 && t <= 41) roundabout(c, t - 29, true);
                else if (t >= 44 && t <= 55) via(c, t - 43);
        }
    }

    /** Odd ids go left, even ones right (1 left, 2 right, ... 17 merge right). */
    private static int side(int t) {
        return t % 2 == 1 ? -1 : 1;
    }

    /**
     * Up to the junction, then off at {@code angle} degrees to the side; with {@code fork} the straight
     * way on is shown faintly too.
     */
    private void bend(Canvas c, double angle, int side, boolean fork) {
        double a = Math.toRadians(angle) * side;
        float jy = angle > 100 ? 48 : 56, leg = angle == 0 ? 34 : 28;
        // keep the whole arrow in the box: the junction moves away from the side it turns to
        float jx = 50 - (float) (Math.sin(a) * (angle > 100 ? 14 : 18));
        if (fork) c.drawLine(jx, jy, jx, 14, faint);
        float ex = jx + (float) (Math.sin(a) * leg), ey = jy - (float) (Math.cos(a) * leg);
        path.reset();
        path.moveTo(jx, 92);
        path.lineTo(jx, jy);
        path.lineTo(ex, ey);
        c.drawPath(path, line);
        arrowHead(c, ex, ey, a);
    }

    /** A filled head at the line's end pointing along {@code a} (radians, 0 = up, clockwise). */
    private void arrowHead(Canvas c, float x, float y, double a) {
        arrowHead(c, x, y, a, 20, 15);
    }

    private void arrowHead(Canvas c, float x, float y, double a, float len, float half) {
        double sx = Math.sin(a), sy = -Math.cos(a);
        head.reset();
        head.moveTo(x + (float) (sx * len), y + (float) (sy * len));
        head.lineTo(x - (float) (sy * half), y + (float) (sx * half));
        head.lineTo(x + (float) (sy * half), y - (float) (sx * half));
        head.close();
        c.drawPath(head, fill);
    }

    /** Up, round and back down on the side it turns to. */
    private void uTurn(Canvas c, int side) {
        float up = 50 - side * 14, down = 50 + side * 14;
        path.reset();
        path.moveTo(up, 92);
        path.lineTo(up, 42);
        // over the top: from the right point to the left one for a left U-turn, and the other way
        path.arcTo(Math.min(up, down), 28, Math.max(up, down), 56, side < 0 ? 0 : 180, side * 180, false);
        path.lineTo(down, 58);
        c.drawPath(path, line);
        arrowHead(c, down, 58, Math.PI);
    }

    /** Joining the road faintly drawn straight up, from the other side. */
    private void merge(Canvas c, int side) {
        float road = 50 + side * 6, from = 50 - side * 24;
        c.drawLine(road, 92, road, 14, faint);
        path.reset();
        path.moveTo(from, 92);
        path.cubicTo(from, 62, road, 60, road, 38);
        c.drawPath(path, line);
        arrowHead(c, road, 38, 0);
    }

    /** The ring with the exit number, entered from below; a head on the ring shows the way it turns. */
    private void roundabout(Canvas c, int exit, boolean counterClockwise) {
        c.drawLine(50, 92, 50, 70, line);
        c.drawCircle(50, 42, 25, line);
        arrowHead(c, counterClockwise ? 57 : 43, 17, counterClockwise ? -Math.PI / 2 : Math.PI / 2, 14, 10);
        label.setTextSize(exit >= 10 ? 26 : 32);
        c.drawText(String.valueOf(exit), 50, 42 - (label.ascent() + label.descent()) / 2, label);
    }

    private void flag(Canvas c) {
        c.drawLine(32, 92, 32, 12, line);
        head.reset();
        head.moveTo(32, 12);
        head.lineTo(80, 28);
        head.lineTo(32, 46);
        head.close();
        c.drawPath(head, fill);
    }

    /** A filled disc with the via point's number. */
    private void via(Canvas c, int number) {
        c.drawCircle(50, 50, 36, fill);
        label.setColor(onFill);
        label.setTextSize(number >= 10 ? 34 : 42);
        c.drawText(String.valueOf(number), 50, 50 - (label.ascent() + label.descent()) / 2, label);
        label.setColor(fill.getColor());
    }
}
