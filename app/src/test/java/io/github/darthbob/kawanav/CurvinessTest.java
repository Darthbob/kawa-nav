package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Degrees per km of known shapes: a circle of radius R turns 360° in 2πR. */
public class CurvinessTest {

    private static double[][] snake(double radius, int bends) {
        TestGraphs.Way w = TestGraphs.snake(1, 0, 0, radius, bends, 2);
        return new double[][]{w.xs, w.ys};
    }

    private static double middle(double[][] p) {
        double[] v = Curviness.atVertices(p[0], p[1]);
        return v[v.length / 2];
    }

    @Test
    public void straightRoadIsZero() {
        double[] x = new double[50], y = new double[50];
        for (int i = 0; i < 50; i++) x[i] = i * 37.0;
        for (double v : Curviness.atVertices(x, y)) assertEquals(0, v, 1e-6);
        assertEquals(0, Curviness.level(0));
    }

    @Test
    public void bendsOfKnownRadius() {
        // 57296 / R degrees per km, whether the bends go left or right
        double r600 = middle(snake(600, 4));
        double r400 = middle(snake(400, 4));
        double r180 = middle(snake(180, 8));
        double r100 = middle(snake(100, 12));
        assertEquals(57296 / 400.0, r400, 57296 / 400.0 * 0.06);
        assertEquals(57296 / 180.0, r180, 57296 / 180.0 * 0.06);
        assertEquals(57296 / 100.0, r100, 57296 / 100.0 * 0.06);
        assertEquals(0, Curviness.level(r600));
        assertEquals(1, Curviness.level(r400));
        assertEquals(2, Curviness.level(r180));
        assertEquals(3, Curviness.level(r100));
    }

    @Test
    public void pointDensityDoesNotMatter() {
        TestGraphs.Way fine = TestGraphs.snake(1, 0, 0, 200, 6, 1);
        TestGraphs.Way coarse = TestGraphs.snake(1, 0, 0, 200, 6, 10);
        double a = middle(new double[][]{fine.xs, fine.ys});
        double b = middle(new double[][]{coarse.xs, coarse.ys});
        assertEquals(a, b, a * 0.05);
    }

    @Test
    public void oneHairpinStaysLocal() {
        // 3 km straight, a 180° bend of 30 m radius in the middle, 3 km straight back up north
        int n = 0;
        double[] x = new double[200], y = new double[200];
        for (int i = 0; i <= 30; i++, n++) x[n] = i * 100;                 // east to 3000
        for (int a = 270; a <= 450; a += 10, n++) {                         // turn round the bend
            x[n] = 3000 + 30 * Math.cos(Math.toRadians(a)) + 0;
            y[n] = 30 + 30 * Math.sin(Math.toRadians(a));
        }
        for (int i = 1; i <= 30; i++, n++) {                                // back west at y = 60
            x[n] = 3000 - i * 100;
            y[n] = 60;
        }
        double[] xs = java.util.Arrays.copyOf(x, n), ys = java.util.Arrays.copyOf(y, n);
        double[] v = Curviness.atVertices(xs, ys);
        assertEquals("far from the bend", 0, v[2], 1e-6);
        assertEquals("far from the bend", 0, v[n - 3], 1e-6);
        double atBend = v[31 + 9];
        // 180° in a 1 km window
        assertEquals(180, atBend, 25);
        assertTrue(Curviness.between(v, xs, ys, 0, n - 1) < 100);
    }
}
