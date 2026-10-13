package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Request queue: one at a time, gap, answers, ACK results, timeouts and retries, priorities. */
public class BikeRequestsTest {

    /** Fake clock and scheduler; {@link #advance} runs what falls due. */
    static final class FakeIo implements BikeRequests.Io {
        long now;
        boolean connected = true;
        final List<String> sent = new ArrayList<>();
        final List<Object[]> tasks = new ArrayList<>(); // {Runnable, dueAt}

        @Override
        public boolean send(byte[] frame, String label) {
            if (!connected) return false;
            sent.add(KawaProtocol.hex(frame));
            return true;
        }

        @Override
        public long now() {
            return now;
        }

        @Override
        public void schedule(Runnable r, long delayMs) {
            tasks.add(new Object[]{r, now + delayMs});
        }

        @Override
        public void cancel(Runnable r) {
            tasks.removeIf(t -> t[0] == r);
        }

        @Override
        public void log(String line) {}

        void advance(long ms) {
            long end = now + ms;
            while (true) {
                Object[] next = null;
                for (Object[] t : tasks) if ((long) t[1] <= end && (next == null || (long) t[1] < (long) next[1])) next = t;
                if (next == null) break;
                tasks.remove(next);
                now = (long) next[1];
                ((Runnable) next[0]).run();
            }
            now = end;
        }
    }

    FakeIo io;
    BikeRequests q;
    List<String> done;

    @Before
    public void setUp() {
        io = new FakeIo();
        q = new BikeRequests(io);
        done = new ArrayList<>();
    }

    BikeRequests.Request req(int id, int expect, int priority) {
        return new BikeRequests.Request(KawaProtocol.readRequest(id), "req " + id, expect, priority,
                (r, ok, result) -> done.add(String.format("%02X %s %d", r.id(), ok, result)));
    }

    static byte[] hx(String s) {
        return KawaProtocol.parseHex(s);
    }

    @Test
    public void oneAtATimeWithGap() {
        q.submit(req(0x03, BikeRequests.DATA_OR_ACK, BikeRequests.STARTUP));
        q.submit(req(0x40, BikeRequests.DATA_OR_ACK, BikeRequests.STARTUP));
        assertEquals(List.of("03 00 00"), io.sent);
        q.onFrame(hx("03207a030002714d4c3545523530"));
        assertEquals(List.of("03 true 0"), done);
        assertEquals(1, io.sent.size()); // gap of 200 ms first
        io.advance(BikeRequests.GAP_MS);
        assertEquals("40 00 00", io.sent.get(1));
    }

    @Test
    public void ackCompletesAndRejectionIsRemembered() {
        q.submit(req(0x42, BikeRequests.ACK_ONLY, BikeRequests.STARTUP));
        q.onFrame(hx("2002854100")); // ACK for another request: ignored
        assertTrue(done.isEmpty());
        q.onFrame(hx("20029b4201"));
        assertEquals(List.of("42 true 1"), done);
        assertTrue(q.unsupported(0x42));
        assertFalse(q.unsupported(0x41));
    }

    @Test
    public void ackOnlyIgnoresDataFrame() {
        q.submit(req(0x45, BikeRequests.ACK_ONLY, BikeRequests.STARTUP));
        q.onFrame(hx("4534a4ffff"));
        assertTrue(done.isEmpty());
        q.onFrame(hx("2002934500"));
        assertEquals(List.of("45 true 0"), done);
    }

    @Test
    public void meterInitAcceptsAckAs13() {
        q.submit(new BikeRequests.Request(KawaProtocol.meterInitFrame(), "init", BikeRequests.ACK_ONLY,
                BikeRequests.STARTUP, (r, ok, res) -> done.add("08 " + ok)));
        q.onFrame(hx("200c92130000"));
        assertEquals(List.of("08 true"), done);
    }

    @Test
    public void timeoutRetriesThenGivesUp() {
        q.submit(req(0x41, BikeRequests.DATA_OR_ACK, BikeRequests.POLL));
        for (int i = 0; i < BikeRequests.MAX_TRIES; i++) io.advance(BikeRequests.TIMEOUT_MS);
        assertEquals(BikeRequests.MAX_TRIES, io.sent.size());
        assertEquals(List.of("41 false -1"), done);
        assertTrue(q.idle());
    }

    @Test
    public void priorityOrder() {
        q.submit(req(0x03, BikeRequests.DATA_OR_ACK, BikeRequests.STARTUP)); // sent at once
        q.submit(req(0x41, BikeRequests.DATA_OR_ACK, BikeRequests.POLL));
        q.submit(req(0x40, BikeRequests.DATA_OR_ACK, BikeRequests.STARTUP));
        q.submit(req(0x48, BikeRequests.DATA_OR_ACK, BikeRequests.USER));
        q.onFrame(hx("0320"));
        io.advance(BikeRequests.GAP_MS);
        q.onFrame(hx("4820"));
        io.advance(BikeRequests.GAP_MS);
        q.onFrame(hx("4020"));
        io.advance(BikeRequests.GAP_MS);
        assertEquals(List.of("03 00 00", "48 00 00", "40 00 00", "41 00 00"), io.sent);
    }

    @Test
    public void noAnswerExpectedCompletesOnSend() {
        q.submit(req(0x0B, BikeRequests.NONE, BikeRequests.STARTUP));
        assertEquals(List.of("0B true 0"), done);
    }

    @Test
    public void notConnectedFails() {
        io.connected = false;
        q.submit(req(0x41, BikeRequests.DATA_OR_ACK, BikeRequests.POLL));
        assertEquals(List.of("41 false -1"), done);
    }

    @Test
    public void submitIfIdleSkipsDuplicates() {
        assertTrue(q.submitIfIdle(req(0x41, BikeRequests.DATA_OR_ACK, BikeRequests.POLL)));
        assertFalse(q.submitIfIdle(req(0x41, BikeRequests.DATA_OR_ACK, BikeRequests.POLL)));
        q.onFrame(hx("4152"));
        io.advance(BikeRequests.GAP_MS);
        assertTrue(q.submitIfIdle(req(0x41, BikeRequests.DATA_OR_ACK, BikeRequests.POLL)));
    }

    @Test
    public void clearDropsEverything() {
        q.submit(req(0x03, BikeRequests.DATA_OR_ACK, BikeRequests.STARTUP));
        q.submit(req(0x40, BikeRequests.DATA_OR_ACK, BikeRequests.STARTUP));
        q.clear();
        io.advance(10_000);
        assertEquals(1, io.sent.size());
        assertTrue(done.isEmpty());
        assertTrue(q.idle());
    }
}
