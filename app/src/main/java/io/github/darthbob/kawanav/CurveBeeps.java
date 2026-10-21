package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.List;

/**
 * The warnings as beeps instead of words, for riders who would rather not hear a voice. A bend beeps
 * in the ear of its side (the other ear hears it faintly, so a mono intercom still plays it): one beep
 * for a slight or normal bend, two for a sharp one, three for a hairpin, higher and quicker when the
 * rider comes at it too fast. A bend named with it ("then") follows after a pause, in its own ear. A
 * speed camera sounds like a two-tone siren, high-low twice without a break, in both ears: nothing a
 * bend sounds like, also through a mono intercom. Plain Java: 16-bit stereo PCM that
 * {@link CurveSound} plays.
 */
final class CurveBeeps {
    private CurveBeeps() {}

    static final int RATE = 22_050;
    static final double TONE_HZ = 880, URGENT_HZ = 1320;
    static final int BEEP_MS = 120, GAP_MS = 90, URGENT_GAP_MS = 50, THEN_PAUSE_MS = 300;
    /** The speed camera's siren: a high and a low tone, each this long, twice over. */
    static final double SIREN_HIGH_HZ = 988, SIREN_LOW_HZ = 659;
    static final int SIREN_TONE_MS = 160, SIREN_TONES = 4;
    /** Loudness in the ear of the bend's side and in the other one. */
    static final double NEAR = 0.6, FAR = 0.12;
    /** A short fade at both ends of a sound, so it does not click. */
    private static final int FADE_MS = 6;

    /** One tone, or a silence ({@code hz} 0), how loud it is in each ear, and whether it fades in and out. */
    private static final class Tone {
        final double hz, left, right;
        final int ms;
        final boolean fadeIn, fadeOut;

        Tone(double hz, int ms, double left, double right) {
            this(hz, ms, left, right, true, true);
        }

        Tone(double hz, int ms, double left, double right, boolean fadeIn, boolean fadeOut) {
            this.hz = hz;
            this.ms = ms;
            this.left = left;
            this.right = right;
            this.fadeIn = fadeIn;
            this.fadeOut = fadeOut;
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

    /** A speed camera ahead: high-low, high-low without a break, in both ears. */
    static short[] camera() {
        List<Tone> tones = new ArrayList<>();
        for (int i = 0; i < SIREN_TONES; i++) {
            tones.add(new Tone(i % 2 == 0 ? SIREN_HIGH_HZ : SIREN_LOW_HZ, SIREN_TONE_MS, NEAR, NEAR,
                    i == 0, i == SIREN_TONES - 1));
        }
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

    /**
     * Interleaved stereo samples, left first. The phase runs on from one tone to the next, so tones
     * that follow each other without a fade change pitch without a click.
     */
    private static short[] render(List<Tone> tones) {
        int frames = 0;
        for (Tone t : tones) frames += t.ms * RATE / 1000;
        short[] pcm = new short[frames * 2];
        int at = 0;
        int fade = FADE_MS * RATE / 1000;
        double phase = 0;
        for (Tone t : tones) {
            int n = t.ms * RATE / 1000;
            for (int i = 0; i < n; i++, at++) {
                if (t.hz <= 0) {
                    phase = 0;
                    continue;
                }
                double env = 1;
                if (t.fadeIn) env = Math.min(env, i / (double) fade);
                if (t.fadeOut) env = Math.min(env, (n - 1 - i) / (double) fade);
                double v = Math.sin(phase) * env * Short.MAX_VALUE;
                phase += 2 * Math.PI * t.hz / RATE;
                pcm[2 * at] = (short) Math.round(v * t.left);
                pcm[2 * at + 1] = (short) Math.round(v * t.right);
            }
        }
        return pcm;
    }
}
