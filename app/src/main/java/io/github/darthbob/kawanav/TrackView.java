package io.github.darthbob.kawanav;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The ride's track: the recorded positions joined, coloured by speed (blue slow, green, orange, red
 * fast), over OpenStreetMap's map when it is on (see {@link MapTiles}; without a network the track is
 * drawn alone, as before). Pinch or double-tap to zoom, drag to move; {@link #fit()} shows the whole
 * ride again.
 */
public final class TrackView extends View {

    private final float density;
    /** About how big a map tile is drawn: OSM's 256 px pictures, enlarged on a dense screen to stay readable. */
    private final float tilePx;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint casing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private MapTiles tiles;

    private List<RideTsv.Sample> samples;
    private int marked = -1;
    /** Each sample in Web Mercator (0..1), NaN without a position; and its colour. */
    private double[] wx, wy;
    private int[] colours;
    private double minX, maxX, minY, maxY;
    /** What is shown: the centre in Web Mercator, and how many screen pixels the whole world is wide. */
    private double cx, cy, ppw;
    /** The rider zoomed or moved the map: a new size of the view keeps it, instead of fitting the ride again. */
    private boolean moved;

    /** The map is drawn unless the rider turned it off ({@link Hub#KEY_RIDE_MAP}). */
    public TrackView(Context context) {
        super(context);
        boolean map = Hub.prefs(context).getBoolean(Hub.KEY_RIDE_MAP, true);
        density = context.getResources().getDisplayMetrics().density;
        tilePx = MapTiles.TILE * Math.max(1f, density * 0.75f);
        boolean night = Ui.night(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(density * 3);
        casing.setStyle(Paint.Style.STROKE);
        casing.setStrokeCap(Paint.Cap.ROUND);
        casing.setStrokeJoin(Paint.Join.ROUND);
        casing.setStrokeWidth(density * 5.5f);
        casing.setColor(night ? 0xCC000000 : 0xE6FFFFFF);
        marker.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(density * 1.5f);
        if (night) tilePaint.setColorFilter(darkMap());
        if (map) tiles = new MapTiles(context, () -> postInvalidateOnAnimation());
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAround(d.getFocusX(), d.getFocusY(), d.getScaleFactor());
                return true;
            }
        });
        scaler.setQuickScaleEnabled(false); // a double tap zooms in, see below
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                cx += dx / ppw;
                cy += dy / ppw;
                moved = true;
                keepInView();
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return performClick();
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                animateZoom(e.getX(), e.getY(), 2);
                return true;
            }
        });
        setContentDescription("The ride's track on a map. Pinch to zoom, drag to move.");
    }

    /** The ride to draw; {@code marked} (a sample index) gets a dot, -1 for none. */
    public void set(List<RideTsv.Sample> samples, int marked) {
        if (samples != this.samples) {
            this.samples = samples;
            project();
            moved = false;
            fit();
        }
        this.marked = marked;
        if (marked >= 0) showMarked();
        invalidate();
    }

    /** True when OpenStreetMap's map is drawn under the track (its credit then goes on the map too). */
    public boolean hasMap() {
        return tiles != null;
    }

    @Override
    public boolean performClick() {
        return super.performClick();   // a tap does nothing more; accessibility services see it as a click
    }

    /** Back to the whole ride. */
    public void fit() {
        moved = false;
        if (wx == null || getWidth() == 0 || getHeight() == 0) return;
        float pad = density * 16;
        double w = Math.max(1, getWidth() - 2 * pad), h = Math.max(1, getHeight() - 2 * pad);
        double spanX = maxX - minX, spanY = maxY - minY;
        // a ride that never moved is shown at street level, not at the closest zoom there is
        double street = tilePx * (1 << 16);
        ppw = spanX <= 0 && spanY <= 0 ? street
                : Math.min(street, Math.min(w / Math.max(spanX, 1e-12), h / Math.max(spanY, 1e-12)));
        ppw = Math.max(minPpw(), Math.min(maxPpw(), ppw));
        cx = (minX + maxX) / 2;
        cy = (minY + maxY) / 2;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!moved) fit();
        else keepInView();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (tiles != null) tiles.close();
        tiles = null;
    }

    /** Web Mercator positions, the extent of the ride and the colours, once per ride. */
    private void project() {
        wx = null;
        if (samples == null || samples.isEmpty()) return;
        int n = samples.size();
        double[] x = new double[n], y = new double[n];
        int[] c = new int[n];
        double maxSpeed = 1;
        for (RideTsv.Sample s : samples) if (s.kmh == s.kmh) maxSpeed = Math.max(maxSpeed, s.kmh);
        minX = minY = Double.MAX_VALUE;
        maxX = maxY = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            RideTsv.Sample s = samples.get(i);
            if (!s.hasGps()) {
                x[i] = y[i] = Double.NaN;
                continue;
            }
            x[i] = MapTiles.x(s.lon);
            y[i] = MapTiles.y(s.lat);
            c[i] = colour(s.kmh, maxSpeed, s.lean);
            minX = Math.min(minX, x[i]);
            maxX = Math.max(maxX, x[i]);
            minY = Math.min(minY, y[i]);
            maxY = Math.max(maxY, y[i]);
        }
        if (minX > maxX) return; // no positions at all
        wx = x;
        wy = y;
        colours = c;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (wx == null) return false;
        // the page scrolls everywhere else: on the map a finger moves the map
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    /** Zooms by {@code factor} keeping the point under the fingers where it is. */
    private void zoomAround(float fx, float fy, double factor) {
        double ox = (fx - getWidth() / 2.0), oy = (fy - getHeight() / 2.0);
        double px = cx + ox / ppw, py = cy + oy / ppw;
        ppw = Math.max(minPpw(), Math.min(maxPpw(), ppw * factor));
        cx = px - ox / ppw;
        cy = py - oy / ppw;
        moved = true;
        keepInView();
        invalidate();
    }

    private void animateZoom(float fx, float fy, double factor) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(220);
        double[] done = {1};
        a.addUpdateListener(an -> {
            double want = Math.pow(factor, an.getAnimatedFraction());
            zoomAround(fx, fy, want / done[0]);
            done[0] = want;
        });
        a.start();
    }

    /**
     * Out until the whole ride is a quarter of the view (never so far that the world is narrower than the
     * view), in to one level past OSM's closest.
     */
    private double minPpw() {
        double spanPx = Math.max(maxX - minX, maxY - minY);
        double whole = spanPx > 0 ? Math.max(getWidth(), getHeight()) / spanPx : tilePx;
        return Math.max(Math.max(getWidth(), getHeight()), Math.min(whole, tilePx * (1 << 16)) / 4);
    }

    private double maxPpw() {
        return tilePx * (1L << (MapTiles.MAX_ZOOM + 1));
    }

    /** The ride cannot be dragged out of sight: some of it always stays on the screen. */
    private void keepInView() {
        if (wx == null || ppw <= 0) return;
        double halfW = getWidth() / 2.0 / ppw, halfH = getHeight() / 2.0 / ppw;
        cx = Math.max(minX - halfW * 0.8, Math.min(maxX + halfW * 0.8, cx));
        cy = Math.max(minY - halfH * 0.8, Math.min(maxY + halfH * 0.8, cy));
    }

    /** The second picked on the chart is out of sight: the map moves to it, keeping the zoom. */
    private void showMarked() {
        if (wx == null || marked >= wx.length || wx[marked] != wx[marked] || getWidth() == 0) return;
        double sx = (wx[marked] - cx) * ppw + getWidth() / 2.0, sy = (wy[marked] - cy) * ppw + getHeight() / 2.0;
        float m = density * 16;
        if (sx < m || sy < m || sx > getWidth() - m || sy > getHeight() - m) {
            cx = wx[marked];
            cy = wy[marked];
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (wx == null || getWidth() == 0 || ppw <= 0) return;
        if (tiles != null) drawMap(canvas);
        float halfW = getWidth() / 2f, halfH = getHeight() / 2f;
        float step = density * 1.5f;   // closer points than this add nothing to the line
        float margin = density * 8;

        // the line, under it a casing that keeps it readable on any map colour
        path.reset();
        float lastX = 0, lastY = 0;
        boolean first = true;
        for (int i = 0; i < wx.length; i++) {
            if (wx[i] != wx[i]) {
                first = true; // a gap: start a new piece
                continue;
            }
            float x = (float) ((wx[i] - cx) * ppw + halfW), y = (float) ((wy[i] - cy) * ppw + halfH);
            if (first) {
                path.moveTo(x, y);
            } else {
                if (Math.abs(x - lastX) < step && Math.abs(y - lastY) < step) continue;
                if (offScreen(lastX, lastY, x, y, margin)) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
            first = false;
        }
        if (tiles != null) canvas.drawPath(path, casing);

        first = true;
        for (int i = 0; i < wx.length; i++) {
            if (wx[i] != wx[i]) {
                first = true;
                continue;
            }
            float x = (float) ((wx[i] - cx) * ppw + halfW), y = (float) ((wy[i] - cy) * ppw + halfH);
            if (!first) {
                if (Math.abs(x - lastX) < step && Math.abs(y - lastY) < step) continue;
                if (!offScreen(lastX, lastY, x, y, margin)) {
                    line.setColor(colours[i]);
                    canvas.drawLine(lastX, lastY, x, y, line);
                }
            }
            lastX = x;
            lastY = y;
            first = false;
        }

        // where the ride started, and the second picked on the chart
        for (int i = 0; i < wx.length; i++) {
            if (wx[i] != wx[i]) continue;
            dot(canvas, (float) ((wx[i] - cx) * ppw + halfW), (float) ((wy[i] - cy) * ppw + halfH),
                    6, Ui.recordIdle(), 0xFFFFFFFF);
            break;
        }
        if (marked >= 0 && marked < wx.length && wx[marked] == wx[marked]) {
            dot(canvas, (float) ((wx[marked] - cx) * ppw + halfW), (float) ((wy[marked] - cy) * ppw + halfH),
                    5, Ui.markerOnTrack(), 0xFF171717);
        }
    }

    private void dot(Canvas canvas, float x, float y, float radiusDp, int fill, int edge) {
        float r = density * radiusDp;
        marker.setColor(fill);
        canvas.drawCircle(x, y, r, marker);
        ring.setColor(edge);
        canvas.drawCircle(x, y, r, ring);
    }

    /** Both ends beyond the same edge: the segment cannot cross the screen. */
    private boolean offScreen(float ax, float ay, float bx, float by, float m) {
        float w = getWidth(), h = getHeight();
        return (ax < -m && bx < -m) || (ax > w + m && bx > w + m)
                || (ay < -m && by < -m) || (ay > h + m && by > h + m);
    }

    /** The tiles under the view; a missing one is stood in for by a coarser one already loaded. */
    private void drawMap(Canvas canvas) {
        int z = MapTiles.zoomFor(ppw, tilePx);
        int n = 1 << z;
        double size = ppw / n;                         // screen pixels per tile
        double left = cx * ppw - getWidth() / 2.0;     // the view's edges, in world pixels
        double top = cy * ppw - getHeight() / 2.0;
        int x0 = (int) Math.max(0, Math.floor(left / size));
        int x1 = (int) Math.min(n - 1, Math.floor((left + getWidth()) / size));
        int y0 = (int) Math.max(0, Math.floor(top / size));
        int y1 = (int) Math.min(n - 1, Math.floor((top + getHeight()) / size));
        Set<String> keys = new HashSet<>();
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) keys.add(MapTiles.key(z, x, y));
        tiles.frame(keys);  // before asking for them: a request only runs while its tile is still wanted
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                // whole pixels on both sides of every edge: no hairline gaps between tiles
                dst.set((int) Math.round(x * size - left), (int) Math.round(y * size - top),
                        (int) Math.round((x + 1) * size - left), (int) Math.round((y + 1) * size - top));
                Bitmap b = tiles.get(z, x, y);
                if (b != null) {
                    canvas.drawBitmap(b, null, dst, tilePaint);
                    continue;
                }
                for (int k = 1; k <= 4 && z - k >= 0; k++) {
                    Bitmap p = tiles.peek(z - k, x >> k, y >> k);
                    if (p == null) continue;
                    int sub = p.getWidth() >> k;
                    int sx = (x - ((x >> k) << k)) * sub, sy = (y - ((y >> k) << k)) * sub;
                    src.set(sx, sy, sx + sub, sy + sub);
                    canvas.drawBitmap(p, src, dst, tilePaint);
                    break;
                }
            }
        }
    }

    /** At night the map is turned dark: brightness inverted, colours turned back to their own hue, dimmed. */
    private static ColorMatrixColorFilter darkMap() {
        ColorMatrix m = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0});
        // a hue turn of 180°, which brings the inverted colours back: water blue, parks green
        m.postConcat(new ColorMatrix(new float[]{
                -0.574f, 1.430f, 0.144f, 0, 0,
                0.426f, 0.430f, 0.144f, 0, 0,
                0.426f, 1.430f, -0.856f, 0, 0,
                0, 0, 0, 1, 0}));
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.8f, 0.8f, 0.8f, 1);
        m.postConcat(dim);
        return new ColorMatrixColorFilter(m);
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
