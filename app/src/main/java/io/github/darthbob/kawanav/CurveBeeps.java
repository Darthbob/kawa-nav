package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;

/**
 * The warnings as beeps instead of words, for riders who would rather not hear a voice. A bend beeps
 * in the ear of its side (the other ear hears it faintly, so a mono intercom still plays it): one beep
 * for a slight or normal bend, two for a sharp one, three for a hairpin, higher and quicker when the
 * rider comes at it too fast. A bend named with it ("then") follows after a pause, in its own ear. A
 * speed camera is two tones rising, in both ears. Plain Java: 16-bit stereo PCM that
 * {@link CurveSound} plays.
 */
final class CurveBeeps {
    private CurveBeeps() {}

    static final int RATE = 22_050;
    static final double TONE_HZ = 880, URGENT_HZ = 1320;
    static final int BEEP_MS = 120, GAP_MS = 90, URGENT_GAP_MS = 50, THEN_PAUSE_MS = 300;
    /** Loudness in the ear of the bend's side and in the other one. */
    static final double NEAR = 0.6, FAR = 0.12;
    /** A short fade at both ends of a tone, so it does not click. */
    private static final int FADE_MS = 6;

    /** One tone, or a silence ({@code hz} 0), and how loud it is in each ear. */
    private static final class Tone {
        final double hz, left, right;
        final int ms;

        Tone(double hz, int ms, double left, double right) {
            this.hz = hz;
            this.ms = ms;
            this.left = left;
            this.right = right;
        }
    }

    /** The beeps of a warning. */
    static short[] of(CurveAlerts.Warning w) {
        return bends(w.curve.turn, w.slowDown, w.then == null ? -1 : w.then.turn);
    }

    /**
     * The beeps of a bend, its arrow {@code turn}, and of the one named with it ({@code thenTurn}, -1
     * for none).
     */
    static short[] bends(int turn, boolean slowDown, int thenTurn) {
        List<Tone> tones = new ArrayList<>();
        bend(tones, turn, slowDown);
        if (thenTurn >= 0) {
            tones.add(new Tone(0, THEN_PAUSE_MS, 0, 0));
            bend(tones, thenTurn, false);
        }
        return render(tones);
    }

    /** A speed camera ahead: two tones rising, in both ears. */
    static short[] camera() {
        List<Tone> tones = new ArrayList<>();
        tones.add(new Tone(660, 160, NEAR, NEAR));
        tones.add(new Tone(0, 40, 0, 0));
        tones.add(new Tone(990, 220, NEAR, NEAR));
        return render(tones);
    }

    /** How many beeps a bend gets: 1 slight or normal, 2 sharp, 3 hairpin. */
    static int count(int turn) {
        return Math.max(1, CurveAlerts.severity(turn));
    }

    private static void bend(List<Tone> tones, int turn, boolean slowDown) {
        boolean right = turn % 2 == 0;
        double left = right ? FAR : NEAR, rightEar = right ? NEAR : FAR;
        int n = count(turn);
        for (int i = 0; i < n; i++) {
            if (i > 0) tones.add(new Tone(0, slowDown ? URGENT_GAP_MS : GAP_MS, 0, 0));
            tones.add(new Tone(slowDown ? URGENT_HZ : TONE_HZ, BEEP_MS, left, rightEar));
        }
    }

    /** Interleaved stereo samples, left first. */
    private static short[] render(List<Tone> tones) {
        int frames = 0;
        for (Tone t : tones) frames += t.ms * RATE / 1000;
        short[] pcm = new short[frames * 2];
        int at = 0;
        int fade = FADE_MS * RATE / 1000;
        for (Tone t : tones) {
            int n = t.ms * RATE / 1000;
            for (int i = 0; i < n; i++, at++) {
                if (t.hz <= 0) continue;
                double env = Math.min(1, Math.min(i, n - 1 - i) / (double) fade);
                double v = Math.sin(2 * Math.PI * t.hz * i / RATE) * env * Short.MAX_VALUE;
                pcm[2 * at] = (short) Math.round(v * t.left);
                pcm[2 * at + 1] = (short) Math.round(v * t.right);
            }
        }
        return pcm;
    }
}
