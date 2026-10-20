package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The track's colours: the scale the chart's value is put on, and the blue-to-red blend. */
public class TrackViewTest {

    @Test
    public void theScaleRunsBetweenThe2ndAndThe98thPercentile() {
        double[] v = new double[101];
        for (int i = 0; i <= 100; i++) v[i] = i;          // 0 … 100
        double[] r = TrackView.colourRange(v);
        assertEquals(2, r[0], 1e-9);
        assertEquals(98, r[1], 1e-9);
    }

    @Test
    public void oneOddSecondDoesNotWashTheRideOut() {
        // an isolated rpm spike (PROTOCOL.md) far above a ride spread over 3000 … 7950 rpm
        double[] v = new double[100];
        for (int i = 0; i < v.length; i++) v[i] = 3000 + 50 * i;
        v[50] = 16_000;
        double[] r = TrackView.colourRange(v);
        assertTrue("the spike sets the top: " + r[1], r[1] < 10_000);
    }

    @Test
    public void secondsWithoutAValueAreLeftOutOfTheScaleAndDrawnGrey() {
        double[] r = TrackView.colourRange(new double[]{Double.NaN, 10, Double.NaN, 20});
        assertEquals(10, r[0], 1e-9);
        assertEquals(20, r[1], 1e-9);
        assertEquals(TrackView.NO_VALUE, TrackView.colourFor(Double.NaN, 10, 20));
        double[] none = TrackView.colourRange(new double[]{Double.NaN, Double.NaN});
        assertTrue(Double.isNaN(none[0]) && Double.isNaN(none[1]));
        assertEquals(TrackView.NO_VALUE, TrackView.colourFor(5, none[0], none[1]));
    }

    @Test
    public void theColoursGoFromBlueThroughGreenAndOrangeToRed() {
        assertEquals(0xFF3D7BFF, TrackView.colourFor(10, 10, 40));   // lowest: blue
        assertEquals(0xFF43A047, TrackView.colourFor(20, 10, 40));   // a third up: green
        assertEquals(0xFFFB8C00, TrackView.colourFor(30, 10, 40));   // two thirds: orange
        assertEquals(0xFFE53935, TrackView.colourFor(40, 10, 40));   // highest: red
        // beyond the percentiles: the ends of the scale, not a colour off it
        assertEquals(0xFF3D7BFF, TrackView.colourFor(-100, 10, 40));
        assertEquals(0xFFE53935, TrackView.colourFor(400, 10, 40));
    }

    @Test
    public void inBetweenTheStopsTheColoursBlend() {
        int c = TrackView.gradient(1 / 6.0);                          // halfway from blue to green
        assertEquals((0x3D + 0x43) / 2.0, (c >> 16) & 0xFF, 1);
        assertEquals((0x7B + 0xA0) / 2.0, (c >> 8) & 0xFF, 1);
        assertEquals((0xFF + 0x47) / 2.0, c & 0xFF, 1);
        assertEquals(0xFF, c >>> 24);
    }

    @Test
    public void aValueThatNeverChangesSitsInTheMiddle() {
        assertEquals(TrackView.gradient(0.5), TrackView.colourFor(7, 7, 7));
    }
}
