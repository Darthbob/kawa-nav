package io.github.darthbob.kawanav;

import android.os.Handler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.UUID;

/**
 * Debug builds: plays a {@link RawCapture} file back as if the bike sent it, at its recorded pace, so
 * vehicle data, the ride recorder and the screens can be tried at home. Only received frames are played.
 */
public final class FrameReplay {
    private FrameReplay() {}

    public interface Sink {
        void frame(UUID characteristic, byte[] value);
    }

    /** One received frame of a capture line ({@code epochMs RX uuid8 hex}), null for anything else. */
    static Object[] parse(String line) {
        String[] p = line.trim().split(" ");
        if (p.length != 4 || !"RX".equals(p[1])) return null;
        byte[] v = KawaProtocol.parseHex(p[3]);
        if (v == null) return null;
        UUID ch = KawaProtocol.NOTIFY_CHARS[0];
        for (UUID u : KawaProtocol.NOTIFY_CHARS) if (u.toString().startsWith(p[2])) ch = u;
        try {
            return new Object[]{Long.parseLong(p[0]), ch, v};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static volatile Thread running;

    /** Plays {@code f} on a background thread, handing each frame to {@code sink} on {@code main}. */
    public static void start(File f, Handler main, Sink sink) {
        stop();
        Thread t = new Thread(() -> {
            Logger.log("Replay of " + f.getName() + " started");
            long first = -1, startedAt = System.currentTimeMillis();
            int n = 0;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (Thread.currentThread() != running) return;
                    Object[] fr = parse(line);
                    if (fr == null) continue;
                    long at = (long) fr[0];
                    if (first < 0) first = at;
                    long wait = startedAt + (at - first) - System.currentTimeMillis();
                    if (wait > 0) Thread.sleep(wait);
                    Thread self = Thread.currentThread();
                    main.post(() -> {
                        if (running == self) sink.frame((UUID) fr[1], (byte[]) fr[2]); // not after stop()
                    });
                    n++;
                }
                Logger.log("Replay of " + f.getName() + " finished: " + n + " frames");
            } catch (InterruptedException e) {
                Logger.log("Replay stopped after " + n + " frames");
            } catch (Exception e) {
                Logger.log("Replay failed: " + e);
            } finally {
                if (running == Thread.currentThread()) running = null;
            }
        }, "replay");
        running = t;
        t.start();
    }

    public static void stop() {
        Thread t = running;
        running = null;
        if (t != null) t.interrupt();
    }
}
