package io.github.darthbob.kawanav;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The planner's and the navigation's map: a route coloured by how winding it is (blue straight, green
 * sweeping, orange winding, red very winding), its points, and the rider's position. Under it
 * OpenStreetMap's map where it can be fetched ({@link MapTiles}), and otherwise the offline roads of
 * the downloaded regions ({@link RoadStore}), so it works without a network too. Drag, pinch or double
 * tap; a tap and a long press report where they were.
 */
public final class RouteMapView extends View {

    public interface Listener {
        void onTap(double lat, double lon);

        void onLongPress(double lat, double lon);
    }

    /** Colours of the curviness levels. */
    static final int[] LEVEL_COLOURS = {0xFF3D7BFF, 0xFF43A047, 0xFFFB8C00, 0xFFE53935};
    /** Offline roads are drawn from this tile zoom on (closer than about 1:250 000). */
    private static final int OFFLINE_FROM_ZOOM = 11;

    private final float density;
    private final float tilePx;
    private final boolean night;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint casing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint credit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final Path arrow = new Path();
    private final Rect src = new Rect(), dst = new Rect();
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MapTiles tiles;
    private Listener listener;

    // what is drawn, in Web Mercator
    private double[] rx, ry;
    private int[] segColour;
    private final List<double[]> markers = new ArrayList<>(); // {x, y, kind}: 0 start, 1 via, 2 destination
    private double posX = Double.NaN, posY, posHeading = Double.NaN;
    private boolean follow;
    /** The view's centre in Web Mercator and the world's width in screen pixels. */
    private double cx = MapTiles.x(11), cy = MapTiles.y(46), ppw;
    /**
     * Heading up: while following the rider the map turns so the way ahead points up, and the rider sits
     * low on the screen. {@link #bearing} is the map's turn now (0: north up); it eases towards the target.
     */
    private boolean headingUp;
    private double bearing, targetBearing, lastHeading = Double.NaN;
    /** Where on the screen the centre (cx, cy) is drawn, and the map turns around. */
    private float ax, ay;

    // offline roads: tiles of RoadStore, as Web Mercator polylines with their road rank
    private final RoadStore store;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> new Thread(r, "map-roads"));
    private final Map<String, List<float[]>> roadTiles = Collections.synchronizedMap(
            new LinkedHashMap<String, List<float[]>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<float[]>> e) {
                    return size() > 120;
                }
            });
    private final Set<String> roadLoading = Collections.synchronizedSet(new HashSet<>());

    public RouteMapView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        tilePx = MapTiles.TILE * Math.max(1f, density * 0.75f);
        night = Ui.night(context);
        store = new RoadStore(context);
        ppw = tilePx * (1 << 9);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(density * 4.5f);
        casing.setStyle(Paint.Style.STROKE);
        casing.setStrokeCap(Paint.Cap.ROUND);
        casing.setStrokeJoin(Paint.Join.ROUND);
        casing.setStrokeWidth(density * 7.5f);
        casing.setColor(night ? 0xDD000000 : 0xEEFFFFFF);
        road.setStyle(Paint.Style.STROKE);
        road.setStrokeCap(Paint.Cap.ROUND);
        marker.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(density * 2);
        credit.setTextSize(density * 10);
        credit.setColor(night ? 0xAAFFFFFF : 0xAA000000);
        if (night) tilePaint.setColorFilter(darkMap());
        if (Hub.prefs(context).getBoolean(Hub.KEY_RIDE_MAP, true)) tiles = new MapTiles(context, this::postInvalidateOnAnimation);
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAround(d.getFocusX(), d.getFocusY(), d.getScaleFactor());
                return true;
            }
        });
        scaler.setQuickScaleEnabled(false);
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                double[] d = unturn(dx, dy);
                cx += d[0] / ppw;
                cy += d[1] / ppw;
                follow = false;
                aim();
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) {
                    double[] ll = latLon(e.getX(), e.getY());
                    listener.onTap(ll[0], ll[1]);
                }
                return performClick();
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (listener == null) return;
                double[] ll = latLon(e.getX(), e.getY());
                listener.onLongPress(ll[0], ll[1]);
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAround(e.getX(), e.getY(), 2);
                return true;
            }
        });
        setContentDescription("Map. Drag to move, pinch to zoom, tap to choose a place.");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** The route to draw (null: none), coloured by its edges' curviness. */
    public void setRoute(Route r, RouteGraph g) {
        if (r == null || r.lat.length < 2) {
            rx = ry = null;
            segColour = null;
            invalidate();
            return;
        }
        int n = r.lat.length;
        rx = new double[n];
        ry = new double[n];
        for (int i = 0; i < n; i++) {
            rx[i] = MapTiles.x(r.lon[i]);
            ry[i] = MapTiles.y(r.lat[i]);
        }
        segColour = new int[n];
        int s = 0;
        for (int i = 0; i + 1 < n; i++) {
            double mid = (r.dist[i] + r.dist[i + 1]) / 2;
            while (s + 1 < r.states.length && r.stateFrom[s + 1] <= mid) s++;
            int level = g == null ? 0 : RouteGraph.curvy(g.flags(RouteGraph.edgeOf(r.states[s])));
            segColour[i] = LEVEL_COLOURS[level];
        }
        invalidate();
    }

    /** Start, via points and destination ({lat, lon}); the first is the start, the last the destination. */
    public void setPoints(List<double[]> pts) {
        markers.clear();
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            int kind = i == 0 ? 0 : i == pts.size() - 1 ? 2 : 1;
            markers.add(new double[]{MapTiles.x(p[1]), MapTiles.y(p[0]), kind});
        }
        invalidate();
    }

    /** The rider's position and heading (NaN: unknown); with {@link #setFollow} the map keeps it in the middle. */
    public void setPosition(double lat, double lon, double heading) {
        posX = MapTiles.x(lon);
        posY = MapTiles.y(lat);
        posHeading = heading;
        if (heading == heading) lastHeading = heading; // standing still keeps the map as it was
        if (follow) {
            cx = posX;
            cy = posY;
        }
        aim();
        invalidate();
    }

    /** The map turns with the rider's direction while it follows them (off: north up). */
    public void setHeadingUp(boolean on) {
        headingUp = on;
        aim();
        invalidate();
    }

    /** The turn the map eases towards: the rider's heading in heading-up while following, else north. */
    private void aim() {
        targetBearing = headingUp && follow && lastHeading == lastHeading ? lastHeading : 0;
    }

    /** A screen offset (from the turning point) as an offset in the unturned map. */
    private double[] unturn(double dx, double dy) {
        double b = Math.toRadians(bearing), c = Math.cos(b), s = Math.sin(b);
        return new double[]{dx * c - dy * s, dx * s + dy * c};
    }

    /** Keeps the rider in the middle at street level (also when the first position is still to come). */
    public void setFollow(boolean f) {
        follow = f;
        if (f) {
            ppw = Math.max(ppw, tilePx * (1 << 16));
            if (posX == posX) {
                cx = posX;
                cy = posY;
            }
        }
        aim();
        invalidate();
    }

    public boolean following() {
        return follow;
    }

    /** Moves to the place at a zoom level. */
    public void center(double lat, double lon, int zoom) {
        cx = MapTiles.x(lon);
        cy = MapTiles.y(lat);
        ppw = tilePx * (1L << zoom);
        follow = false;
        invalidate();
    }

    /** The whole route (or the points) in view. */
    public void fitRoute() {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        if (rx != null) {
            for (int i = 0; i < rx.length; i++) {
                minX = Math.min(minX, rx[i]);
                maxX = Math.max(maxX, rx[i]);
                minY = Math.min(minY, ry[i]);
                maxY = Math.max(maxY, ry[i]);
            }
        }
        for (double[] m : markers) {
            minX = Math.min(minX, m[0]);
            maxX = Math.max(maxX, m[0]);
            minY = Math.min(minY, m[1]);
            maxY = Math.max(maxY, m[1]);
        }
        if (minX > maxX || getWidth() == 0) return;
        float pad = density * 36;
        double w = Math.max(1, getWidth() - 2 * pad), h = Math.max(1, getHeight() - 2 * pad);
        double span = Math.max((maxX - minX) / w, (maxY - minY) / h);
        ppw = span <= 0 ? tilePx * (1 << 15) : Math.min(tilePx * (1 << 16), 1 / span);
        cx = (minX + maxX) / 2;
        cy = (minY + maxY) / 2;
        follow = false;
        aim();
        invalidate();
    }

    /** {lat, lon} of the view's centre. */
    public double[] centre() {
        return new double[]{lat(cy), cx * 360 - 180};
    }

    private double[] latLon(float sx, float sy) {
        anchor();
        double[] d = unturn(sx - ax, sy - ay);
        double wx = cx + d[0] / ppw, wy = cy + d[1] / ppw;
        return new double[]{lat(wy), wx * 360 - 180};
    }

    /** The turning point: the middle, or low on the screen while heading up and following. */
    private void anchor() {
        ax = getWidth() / 2f;
        ay = headingUp && follow ? getHeight() * 0.68f : getHeight() / 2f;
    }

    private static double lat(double wy) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * wy))));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    private void zoomAround(float fx, float fy, double factor) {
        anchor();
        double[] o = unturn(fx - ax, fy - ay);
        double ox = o[0], oy = o[1];
        double px = cx + ox / ppw, py = cy + oy / ppw;
        ppw = Math.max(tilePx * 4, Math.min(tilePx * (1L << (MapTiles.MAX_ZOOM + 1)), ppw * factor));
        if (!follow) {
            cx = px - ox / ppw;
            cy = py - oy / ppw;
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (tiles != null) tiles.close();
        tiles = null;
        loader.shutdownNow();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(night ? 0xFF1B1B1F : 0xFFF2EFE9);
        // ease the map's turn towards the rider's heading: no jumps from one fix to the next
        double turn = RoadNetwork.angleDiff(bearing, targetBearing);
        if (Math.abs(turn) > 0.3) {
            bearing = (bearing + turn * 0.18 + 360) % 360;
            postInvalidateOnAnimation();
        } else {
            bearing = targetBearing;
        }
        anchor();
        float halfW = ax, halfH = ay;
        canvas.save();
        canvas.rotate((float) -bearing, ax, ay);
        int z = MapTiles.zoomFor(ppw, tilePx);
        boolean missing = tiles == null || drawMap(canvas, z);
        if (missing && z >= OFFLINE_FROM_ZOOM) drawOfflineRoads(canvas, halfW, halfH);

        if (rx != null) {
            float step = density * 1.5f;
            path.reset();
            float lx = 0, ly = 0;
            for (int i = 0; i < rx.length; i++) {
                float x = (float) ((rx[i] - cx) * ppw + halfW), y = (float) ((ry[i] - cy) * ppw + halfH);
                if (i == 0) path.moveTo(x, y);
                else if (Math.abs(x - lx) >= step || Math.abs(y - ly) >= step) path.lineTo(x, y);
                else continue;
                lx = x;
                ly = y;
            }
            canvas.drawPath(path, casing);
            lx = (float) ((rx[0] - cx) * ppw + halfW);
            ly = (float) ((ry[0] - cy) * ppw + halfH);
            for (int i = 1; i < rx.length; i++) {
                float x = (float) ((rx[i] - cx) * ppw + halfW), y = (float) ((ry[i] - cy) * ppw + halfH);
                if (Math.abs(x - lx) < step && Math.abs(y - ly) < step && i < rx.length - 1) continue;
                line.setColor(segColour[i - 1]);
                canvas.drawLine(lx, ly, x, y, line);
                lx = x;
                ly = y;
            }
        }
        for (double[] m : markers) {
            float x = (float) ((m[0] - cx) * ppw + halfW), y = (float) ((m[1] - cy) * ppw + halfH);
            int kind = (int) m[2];
            dot(canvas, x, y, kind == 1 ? 6 : 8, kind == 0 ? Ui.recordIdle() : kind == 2 ? 0xFF171717 : 0xFFFFFFFF,
                    kind == 1 ? 0xFF171717 : 0xFFFFFFFF);
        }
        if (posX == posX) {
            float x = (float) ((posX - cx) * ppw + halfW), y = (float) ((posY - cy) * ppw + halfH);
            if (posHeading == posHeading) {
                double h = Math.toRadians(posHeading);
                float r = density * 14;
                arrow.reset();
                arrow.moveTo(x + (float) (Math.sin(h) * r), y - (float) (Math.cos(h) * r));
                arrow.lineTo(x + (float) (Math.sin(h + 2.5) * r * 0.7), y - (float) (Math.cos(h + 2.5) * r * 0.7));
                arrow.lineTo(x + (float) (Math.sin(h - 2.5) * r * 0.7), y - (float) (Math.cos(h - 2.5) * r * 0.7));
                arrow.close();
                marker.setColor(0xFF1E88E5);
                canvas.drawPath(arrow, marker);
                ring.setColor(0xFFFFFFFF);
                canvas.drawPath(arrow, ring);
            } else {
                dot(canvas, x, y, 7, 0xFF1E88E5, 0xFFFFFFFF);
            }
        }
        canvas.restore();
        String c = tiles != null && !missing ? "© OpenStreetMap contributors" : "Roads © OpenStreetMap contributors";
        canvas.drawText(c, getWidth() - credit.measureText(c) - density * 6, getHeight() - density * 6, credit);
    }

    /** Screen pixels from the turning point to the farthest corner: what must be drawn when the map turns. */
    private double reach() {
        double w = getWidth(), h = getHeight();
        if (bearing == 0) return Math.max(Math.max(ax, w - ax), Math.max(ay, h - ay)) + 1;
        return Math.max(Math.hypot(Math.max(ax, w - ax), ay), Math.hypot(Math.max(ax, w - ax), h - ay)) + 1;
    }

    private void dot(Canvas canvas, float x, float y, float radiusDp, int fill, int edge) {
        float r = density * radiusDp;
        marker.setColor(fill);
        canvas.drawCircle(x, y, r, marker);
        ring.setColor(edge);
        canvas.drawCircle(x, y, r, ring);
    }

    /** OpenStreetMap's tiles; returns true when some had nothing to show (no network, not cached). */
    private boolean drawMap(Canvas canvas, int z) {
        int n = 1 << z;
        double size = ppw / n;
        // world pixels at the canvas origin; the tiles cover a circle round the turning point that holds the
        // whole screen, whichever way the map is turned
        double left = cx * ppw - ax, top = cy * ppw - ay;
        double reach = reach();
        int x0 = (int) Math.max(0, Math.floor((cx * ppw - reach) / size)), x1 = (int) Math.min(n - 1, Math.floor((cx * ppw + reach) / size));
        int y0 = (int) Math.max(0, Math.floor((cy * ppw - reach) / size)), y1 = (int) Math.min(n - 1, Math.floor((cy * ppw + reach) / size));
        Set<String> keys = new HashSet<>();
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) keys.add(MapTiles.key(z, x, y));
        tiles.frame(keys);
        boolean missing = false;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                dst.set((int) Math.round(x * size - left), (int) Math.round(y * size - top),
                        (int) Math.round((x + 1) * size - left), (int) Math.round((y + 1) * size - top));
                Bitmap b = tiles.get(z, x, y);
                if (b != null) {
                    canvas.drawBitmap(b, null, dst, tilePaint);
                    continue;
                }
                boolean stood = false;
                for (int k = 1; k <= 4 && z - k >= 0; k++) {
                    Bitmap p = tiles.peek(z - k, x >> k, y >> k);
                    if (p == null) continue;
                    int sub = p.getWidth() >> k;
                    int sx = (x - ((x >> k) << k)) * sub, sy = (y - ((y >> k) << k)) * sub;
                    src.set(sx, sy, sx + sub, sy + sub);
                    canvas.drawBitmap(p, src, dst, tilePaint);
                    stood = true;
                    break;
                }
                if (!stood) missing = true;
            }
        }
        return missing;
    }

    /** The downloaded regions' roads under the view, loaded tile by tile in the background. */
    private void drawOfflineRoads(Canvas canvas, float halfW, float halfH) {
        double r = reach() / ppw;
        double north = lat(cy - r), south = lat(cy + r), west = (cx - r) * 360 - 180, east = (cx + r) * 360 - 180;
        int ty0 = RoadStore.tileY(south), ty1 = RoadStore.tileY(north);
        int tx0 = RoadStore.tileX(west), tx1 = RoadStore.tileX(east);
        if ((long) (ty1 - ty0 + 1) * (tx1 - tx0 + 1) > 100) return;
        int zoom = MapTiles.zoomFor(ppw, tilePx);
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                String key = ty + "_" + tx;
                List<float[]> ways = roadTiles.get(key);
                if (ways == null) {
                    requestRoads(ty, tx, key);
                    continue;
                }
                for (float[] w : ways) {
                    int rank = (int) w[0];
                    if (zoom < 13 && rank > 4) continue; // small streets only when close
                    road.setStrokeWidth(density * (rank <= 1 ? 3.5f : rank <= 3 ? 2.5f : 1.4f));
                    road.setColor(roadColour(rank));
                    path.reset();
                    for (int i = 1; i + 1 < w.length; i += 2) {
                        float x = (float) ((w[i] - cx) * ppw + halfW), y = (float) ((w[i + 1] - cy) * ppw + halfH);
                        if (i == 1) path.moveTo(x, y);
                        else path.lineTo(x, y);
                    }
                    canvas.drawPath(path, road);
                }
            }
        }
    }

    private int roadColour(int rank) {
        if (night) return rank <= 1 ? 0xFFB8752E : rank <= 3 ? 0xFF8F8A6A : 0xFF5A5A60;
        return rank <= 1 ? 0xFFE8913A : rank <= 3 ? 0xFFF3D36B : 0xFFFFFFFF;
    }

    private void requestRoads(int ty, int tx, String key) {
        if (!store.hasAny(ty, tx) || !roadLoading.add(key)) return;
        loader.execute(() -> {
            List<float[]> ways = new ArrayList<>();
            for (RoadNetwork.RawWay w : store.load(ty, tx)) {
                float[] f = new float[1 + 2 * w.lats.length];
                f[0] = RoadNetwork.rank(w.highway);
                for (int i = 0; i < w.lats.length; i++) {
                    f[1 + 2 * i] = (float) MapTiles.x(w.lons[i]);
                    f[2 + 2 * i] = (float) MapTiles.y(w.lats[i]);
                }
                ways.add(f);
            }
            // bigger roads last, so they lie on top
            ways.sort((a, b) -> Float.compare(b[0], a[0]));
            roadTiles.put(key, ways);
            roadLoading.remove(key);
            main.post(this::invalidate);
        });
    }

    /** At night the map is turned dark like the ride map's. */
    private static ColorMatrixColorFilter darkMap() {
        ColorMatrix m = new ColorMatrix(new float[]{-1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0});
        m.postConcat(new ColorMatrix(new float[]{-0.574f, 1.430f, 0.144f, 0, 0, 0.426f, 0.430f, 0.144f, 0, 0,
                0.426f, 1.430f, -0.856f, 0, 0, 0, 0, 0, 1, 0}));
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.8f, 0.8f, 0.8f, 1);
        m.postConcat(dim);
        return new ColorMatrixColorFilter(m);
    }
}
