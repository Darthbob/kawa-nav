package io.github.darthbob.kawanav;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Requests to the meter, one at a time, the way Rideology sends them: a gap between requests, wait for
 * the answer (a frame of the same type or an ACK naming the request), retry after a timeout, give up
 * after a few tries. Requests the meter rejects (ACK result 01) are remembered as unsupported.
 * Main thread only; time and scheduling come from {@link Io} so this runs in JVM tests.
 */
public final class BikeRequests {

    /** What a request expects back. */
    public static final int DATA_OR_ACK = 0, ACK_ONLY = 1, ANY_ACK = 2, NONE = 3;
    /** Priorities: the rider's own action, the connect sequence, background polling. */
    public static final int USER = 0, STARTUP = 1, POLL = 2;

    static final long GAP_MS = 200;
    static final long TIMEOUT_MS = 3000;
    static final int MAX_TRIES = 3;

    public interface Io {
        /** Writes the frame to the meter; false if it could not be queued (not connected). */
        boolean send(byte[] frame, String label);

        long now();

        void schedule(Runnable r, long delayMs);

        void cancel(Runnable r);

        void log(String line);
    }

    /** Called when a request was answered (ok, result = ACK result or 0 for a data answer) or gave up. */
    public interface Done {
        void on(Request r, boolean ok, int result);
    }

    public static final class Request {
        public final byte[] frame;
        public final String label;
        public final int expect;
        public final int priority;
        final Done done;
        int tries;

        public Request(byte[] frame, String label, int expect, int priority, Done done) {
            this.frame = frame;
            this.label = label;
            this.expect = expect;
            this.priority = priority;
            this.done = done;
        }

        public int id() {
            return frame[0] & 0xFF;
        }

        /** This frame from the meter answers the request. */
        boolean answeredBy(byte[] v) {
            if (v.length == 0) return false;
            int type = v[0] & 0xFF;
            boolean ack = type == BikeFrames.ACK && v.length > 3;
            int cmd = ack ? v[3] & 0xFF : -1;
            switch (expect) {
                case DATA_OR_ACK: return type == id() || cmd == id();
                // the 0x08 meter init has been acknowledged as 0x08 and as 0x13 (meter indication)
                case ACK_ONLY: return cmd == id() || (id() == BikeFrames.METER_INIT && cmd == 0x13);
                case ANY_ACK: return ack;
                default: return false;
            }
        }
    }

    private final Io io;
    private final List<Request> queue = new ArrayList<>();
    private final Set<Integer> unsupported = new HashSet<>();
    private Request pending;
    private long lastSentAt = -GAP_MS;
    private final Runnable pump = this::pump;
    private final Runnable timeout = this::onTimeout;

    public BikeRequests(Io io) {
        this.io = io;
    }

    /** Queues a request behind those of the same or a higher priority. */
    public void submit(Request r) {
        int i = 0;
        while (i < queue.size() && queue.get(i).priority <= r.priority) i++;
        queue.add(i, r);
        pump();
    }

    /** Queues the request unless one of the same frame type is already waiting or running. */
    public boolean submitIfIdle(Request r) {
        if (pending != null && pending.id() == r.id()) return false;
        for (Request q : queue) if (q.id() == r.id()) return false;
        submit(r);
        return true;
    }

    /** The meter rejected this frame type (ACK result 01) since the last {@link #clear}. */
    public boolean unsupported(int id) {
        return unsupported.contains(id);
    }

    public boolean idle() {
        return pending == null && queue.isEmpty();
    }

    /** Drops everything, e.g. when the link is lost. Requests in the queue are not reported as done. */
    public void clear() {
        io.cancel(pump);
        io.cancel(timeout);
        queue.clear();
        pending = null;
        unsupported.clear();
        lastSentAt = -GAP_MS;
    }

    /** A frame from the meter: completes the running request if it answers it. */
    public void onFrame(byte[] v) {
        // the Z900 answers the 0x1E read with an ACK result 01 and then sends the frame all the same,
        // 0.1-0.2 s later: a type whose frame arrives is not unsupported, whatever its ACK said
        int type = v.length > 0 ? v[0] & 0xFF : -1;
        if (type != BikeFrames.ACK && unsupported.remove(type)) {
            io.log("Bike: " + BikeFrames.name(type) + " answered after its ACK said 01");
        }
        Request r = pending;
        if (r == null || !r.answeredBy(v)) return;
        int result = (v[0] & 0xFF) == BikeFrames.ACK && v.length > 4 ? v[4] & 0xFF : 0;
        if (result == 1) {
            unsupported.add(r.id());
            io.log("Bike: " + r.label + " not supported (rejected)");
        }
        finish(r, true, result);
    }

    private void pump() {
        if (pending != null || queue.isEmpty()) return;
        long wait = lastSentAt + GAP_MS - io.now();
        if (wait > 0) {
            io.cancel(pump);
            io.schedule(pump, wait);
            return;
        }
        Request r = queue.remove(0);
        r.tries++;
        lastSentAt = io.now();
        if (!io.send(r.frame, r.label)) {
            finish(r, false, -1);
            return;
        }
        if (r.expect == NONE) {
            finish(r, true, 0);
            return;
        }
        pending = r;
        io.schedule(timeout, TIMEOUT_MS);
    }

    private void onTimeout() {
        Request r = pending;
        if (r == null) return;
        pending = null;
        if (r.tries < MAX_TRIES) {
            queue.add(0, r); // try again right away, ahead of the rest
            pump();
        } else {
            io.log("Bike: no answer to " + r.label + " after " + MAX_TRIES + " tries");
            finish(r, false, -1);
        }
    }

    private void finish(Request r, boolean ok, int result) {
        if (pending == r) {
            pending = null;
            io.cancel(timeout);
        }
        if (r.done != null) r.done.on(r, ok, result);
        pump();
    }
}
