package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The warnings as beeps: how many, in which ear, how high. */
public class CurveBeepsTest {

    private static final int LEFT = 0, RIGHT = 1;
    private static final int WINDOW = CurveBeeps.RATE / 100; // 10 ms

    /** The beeps on one channel: [first frame, last frame] of each run of sound. */
    private static List<int[]> beeps(short[] pcm, int channel) {
        List<int[]> out = new ArrayList<>();
        int frames = pcm.length / 2;
        int start = -1;
        for (int w = 0; w * WINDOW < frames; w++) {
            int peak = 0;
            for (int f = w * WINDOW; f < Math.min(frames, (w + 1) * WINDOW); f++) {
                peak = Math.max(peak, Math.abs(pcm[2 * f + channel]));
            }
            boolean on = peak > 1000;
            if (on && start < 0) start = w * WINDOW;
            if (!on && start >= 0) {
                out.add(new int[]{start, w * WINDOW - 1});
                start = -1;
            }
        }
        if (start >= 0) out.add(new int[]{start, frames - 1});
        return out;
    }

    private static double energy(short[] pcm, int channel, int from, int to) {
        double e = 0;
        for (int f = from; f <= to; f++) e += (double) pcm[2 * f + channel] * pcm[2 * f + channel];
        return e;
    }

    /** Sign changes in a run: twice the frequency times its length. */
    private static int crossings(short[] pcm, int channel, int[] run) {
        int n = 0;
        for (int f = run[0] + 1; f <= run[1]; f++) {
            if ((pcm[2 * f + channel] >= 0) != (pcm[2 * (f - 1) + channel] >= 0)) n++;
        }
        return n;
    }

    @Test
    public void theKindOfBendIsTheNumberOfBeeps() {
        assertEquals(1, beeps(CurveBeeps.bends(5, false, -1), LEFT).size());  // slight
        assertEquals(1, beeps(CurveBeeps.bends(1, false, -1), LEFT).size());  // normal
        assertEquals(2, beeps(CurveBeeps.bends(3, false, -1), LEFT).size());  // sharp
        assertEquals(3, beeps(CurveBeeps.bends(7, false, -1), LEFT).size());  // hairpin
    }

    @Test
    public void aBendBeepsInTheEarOfItsSide() {
        short[] left = CurveBeeps.bends(3, false, -1);
        int last = left.length / 2 - 1;
        assertTrue(energy(left, LEFT, 0, last) > 10 * energy(left, RIGHT, 0, last));
        short[] right = CurveBeeps.bends(4, false, -1);
        assertTrue(energy(right, RIGHT, 0, last) > 10 * energy(right, LEFT, 0, last));
        // the other ear still hears it: a mono intercom plays both
        assertTrue(energy(right, LEFT, 0, last) > 0);
    }

    @Test
    public void tooFastBeepsHigher() {
        short[] calm = CurveBeeps.bends(1, false, -1), urgent = CurveBeeps.bends(1, true, -1);
        int[] c = beeps(calm, LEFT).get(0), u = beeps(urgent, LEFT).get(0);
        double calmHz = crossings(calm, LEFT, c) / 2.0 / ((c[1] - c[0] + 1) / (double) CurveBeeps.RATE);
        double urgentHz = crossings(urgent, LEFT, u) / 2.0 / ((u[1] - u[0] + 1) / (double) CurveBeeps.RATE);
        assertEquals(CurveBeeps.TONE_HZ, calmHz, 60);
        assertEquals(CurveBeeps.URGENT_HZ, urgentHz, 90);
    }

    @Test
    public void theBendNamedWithItFollowsInItsOwnEar() {
        // sharp left, then a normal right
        short[] pcm = CurveBeeps.bends(3, false, 2);
        List<int[]> right = beeps(pcm, RIGHT);
        List<int[]> left = beeps(pcm, LEFT);
        assertEquals(3, left.size()); // the far ear hears all three, faintly
        int[] then = left.get(2);
        assertTrue(energy(pcm, RIGHT, then[0], then[1]) > 10 * energy(pcm, LEFT, then[0], then[1]));
        int[] first = left.get(0);
        assertTrue(energy(pcm, LEFT, first[0], first[1]) > 10 * energy(pcm, RIGHT, first[0], first[1]));
        assertEquals(3, right.size());
    }

    @Test
    public void aCameraIsTwoTonesInBothEars() {
        short[] pcm = CurveBeeps.camera();
        assertEquals(2, beeps(pcm, LEFT).size());
        int last = pcm.length / 2 - 1;
        assertEquals(energy(pcm, LEFT, 0, last), energy(pcm, RIGHT, 0, last), 1);
    }

    @Test
    public void aSharpBendLastsItsBeepsAndTheGap() {
        int frames = CurveBeeps.bends(3, false, -1).length / 2;
        int expected = 2 * CurveBeeps.BEEP_MS * CurveBeeps.RATE / 1000 + CurveBeeps.GAP_MS * CurveBeeps.RATE / 1000;
        assertEquals(expected, frames);
    }
}
