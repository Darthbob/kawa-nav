package io.github.darthbob.kawanav;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * Minimal GATT client for the Kawasaki BLE5 meter. Mirrors Rideology's connect flow:
 * connect (LE) -> MTU 300 -> discover -> enable the three notify characteristics -> ready.
 * Android allows one outstanding GATT operation, so everything goes through a queue. Frames for the
 * dash (arrow, status icons) go in an urgent lane that is served first; a newer frame of the same
 * type replaces one still waiting there, so the dash never lags behind a backlog of requests.
 * All callbacks to {@link Listener} run on the main thread.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked by MainActivity before use
public final class KawaBle {

    public interface Listener {
        void onLog(String line);
        void onStateChanged(boolean ready);

        /** The link dropped or a connection attempt failed (only while not intentionally disconnected). */
        void onLinkLost();

        /** A frame from the meter on one of the notify characteristics. */
        void onNotify(UUID characteristic, byte[] value);

        /** Debug: the meter's services and characteristics, see {@link #setDumpServices}. */
        void onServicesDump(String dump);

        /** A frame was handed to Android for writing (debug capture). */
        default void onTx(byte[] frame) {}
    }

    private static final long OP_TIMEOUT_MS = 3000;
    /** Frames the meter streams on its own (riding log): logged as a count every {@link #STREAM_LOG_MS}. */
    private static final int[] STREAMED = {0x45, 0x4A, 0x4B};
    private static final long STREAM_LOG_MS = 30_000;
    /** Service discovery sometimes misses the Kawasaki service: discover again this often before reconnecting. */
    private static final int DISCOVERY_RETRIES = 2;
    /** Android still busy with an earlier write (its callback came after our timeout): try again this soon. */
    private static final long BUSY_RETRY_MS = 100;
    /** ... at most this often in a row; then the link counts as stuck and is reconnected. */
    private static final int BUSY_RETRIES = 30;
    private static final long RECONNECT_MS = 3000;
    /** Android's "a write is still pending" result (BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY). */
    private static final int WRITE_BUSY = 201;

    /** Which GATT callback the running op waits for; callbacks for anything else are late and ignored. */
    private static final int WAIT_NONE = 0, WAIT_WRITE = 1, WAIT_DESCRIPTOR = 2, WAIT_READ = 3;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    /** Urgent lane: dash frames, at most one waiting per frame type (first byte). */
    private final ArrayDeque<byte[]> urgent = new ArrayDeque<>();
    private final java.util.Map<Integer, String> urgentLabels = new java.util.HashMap<>();
    private final Runnable opTimeout = () -> {
        log("GATT op timed out, continuing");
        opDone();
    };
    private int waiting;
    private int busyRetries;
    private final Runnable busyRetry = () -> {
        if (KawaBle.this.gatt == null) return;
        if (++busyRetries > BUSY_RETRIES) {
            log("Bluetooth stays busy: reconnecting");
            busyRetries = 0;
            disconnect();
            KawaBle.this.listener.onLinkLost();
            return;
        }
        busy = false;
        next();
    };
    /** Connects in a row that found no Kawasaki service (wrong device, stale cache): reconnects slow down. */
    private int missingStreak;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private boolean busy;
    /** Last frame written per frame type (first byte), so repeats are not logged. */
    private final byte[][] lastTx = new byte[256][];
    private boolean ready;
    private int mtu = 23;
    private int discoveries;
    /** Received frames since connecting, per frame type, and the last one (for change-only logging). */
    private final int[] rxCount = new int[256];
    private final int[] rxCountLogged = new int[256];
    private final byte[][] lastRx = new byte[256][];
    private long streamLogAt;
    /** Debug: list all services on connect and read the standard readable characteristics. */
    private boolean dumpServices;
    private java.util.List<String> readLines;
    private int readIndex = -1;

    public KawaBle(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Debug: on the next connects, list the meter's services (read-only, see {@link #dump}). */
    public void setDumpServices(boolean on) {
        dumpServices = on;
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * How long to wait before connecting again after a link loss: 3 s, longer each time in a row the
     * device turned out to have no Kawasaki service (up to 5 min).
     */
    public long reconnectDelayMs() {
        return missingStreak == 0 ? RECONNECT_MS : Math.min(300_000, RECONNECT_MS << Math.min(missingStreak, 7));
    }

    /** ATT MTU negotiated on this connection (23 until the meter answered the MTU request). */
    public int mtu() {
        return mtu;
    }

    /**
     * @param autoConnect true: let Android wait in the background until the bike is in range
     *                    (ignition on) and connect then, which is what a long-running bridge wants.
     */
    public void connect(BluetoothDevice device, boolean autoConnect) {
        disconnect();
        log("Connecting to " + device.getName() + " (" + device.getAddress() + ")"
                + (autoConnect ? ", waiting until it is in range" : ""));
        discoveries = 0;
        mtu = 23;
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE);
    }

    public boolean isConnecting() {
        return gatt != null && !ready;
    }

    public void disconnect() {
        main.removeCallbacks(opTimeout);
        main.removeCallbacks(busyRetry);
        waiting = WAIT_NONE;
        queue.clear();
        urgent.clear();
        urgentLabels.clear();
        busy = false;
        writeChar = null;
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
            log("Disconnected");
        }
        setReady(false);
    }

    /** Queues a write (with response) to the meter's write characteristic, in the normal lane. */
    public boolean write(byte[] data, String label) {
        return write(data, label, false);
    }

    /**
     * Queues a write (with response) to the meter's write characteristic.
     *
     * @param urgent dash frames (arrow, status icons): sent before requests; replaces a frame of the
     *               same type that is still waiting
     */
    public boolean write(byte[] data, String label, boolean urgent) {
        if (!ready || writeChar == null) {
            log("Not connected, dropped: " + label);
            return false;
        }
        if (!urgent) {
            enqueue(() -> send(data, label, false));
            return true;
        }
        int type = data[0] & 0xFF;
        for (java.util.Iterator<byte[]> it = this.urgent.iterator(); it.hasNext(); ) {
            if ((it.next()[0] & 0xFF) == type) it.remove();
        }
        this.urgent.add(data);
        urgentLabels.put(type, label);
        if (!busy) next();
        return true;
    }

    private void send(byte[] data, String label, boolean urgent) {
        waiting = WAIT_WRITE;
        int rc = gatt.writeCharacteristic(writeChar, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        int type = data[0] & 0xFF;
        if (rc == WRITE_BUSY) {
            // Android still waits for an earlier write's answer (late after our timeout): keep this frame
            // first in its lane and try again shortly, instead of failing everything queued behind it
            waiting = WAIT_NONE;
            main.removeCallbacks(opTimeout);
            if (!urgent) {
                queue.addFirst(() -> send(data, label, false));
            } else if (!urgentLabels.containsKey(type)) {
                this.urgent.addFirst(data);
                urgentLabels.put(type, label);
            }
            main.removeCallbacks(busyRetry);
            main.postDelayed(busyRetry, BUSY_RETRY_MS);
            return;
        }
        busyRetries = 0;
        // the bridge repeats the same frames; only log changes and errors
        if (rc != BluetoothGatt.GATT_SUCCESS || !java.util.Arrays.equals(data, lastTx[type])) {
            log("TX " + label + ": " + KawaProtocol.hex(data) + (rc == BluetoothGatt.GATT_SUCCESS ? "" : "  (rc=" + rc + ")"));
        }
        lastTx[type] = data;
        if (rc == BluetoothGatt.GATT_SUCCESS) {
            listener.onTx(data);
        } else {
            opDone();
        }
    }

    private void enqueue(Runnable op) {
        queue.add(op);
        if (!busy) next();
    }

    private void next() {
        if (gatt == null) {
            busy = false;
            return;
        }
        // dash frames first; they only exist once the link is ready (setup ops stay in order)
        byte[] frame = urgent.poll();
        Runnable op = frame != null ? null : queue.poll();
        if (frame == null && op == null) {
            busy = false;
            return;
        }
        busy = true;
        main.postDelayed(opTimeout, OP_TIMEOUT_MS);
        if (frame != null) {
            send(frame, urgentLabels.remove(frame[0] & 0xFF), true);
        } else {
            op.run();
        }
    }

    private void opDone() {
        main.removeCallbacks(opTimeout);
        waiting = WAIT_NONE;
        busy = false;
        next();
    }

    private void setReady(boolean r) {
        if (ready == r) return;
        ready = r;
        listener.onStateChanged(r);
    }

    private void log(String s) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener.onLog(s);
        } else {
            main.post(() -> listener.onLog(s));
        }
    }

    private void onServices(BluetoothGatt g) {
        BluetoothGattService svc = g.getService(KawaProtocol.SERVICE);
        writeChar = svc == null ? null : svc.getCharacteristic(KawaProtocol.WRITE_CHAR);
        if (writeChar == null) {
            if (discoveries == 0 && missingStreak == 0) {
                if (svc == null) {
                    log("Kawasaki BLE5 service not found. Services on device:");
                    for (BluetoothGattService s : g.getServices()) log("  " + s.getUuid());
                } else {
                    log("Write characteristic not found");
                }
            }
            // the bike's stack sometimes reports an incomplete list: look again, then reconnect
            if (discoveries++ < DISCOVERY_RETRIES) {
                log("Discovering services again");
                g.discoverServices();
            } else {
                missingStreak++;
                log("Kawasaki service still missing: reconnecting in " + reconnectDelayMs() / 1000 + " s");
                disconnect();
                listener.onLinkLost();
            }
            return;
        }
        missingStreak = 0;
        if (dumpServices) dump(g);
        java.util.Arrays.fill(rxCount, 0);
        java.util.Arrays.fill(rxCountLogged, 0);
        java.util.Arrays.fill(lastRx, null);
        streamLogAt = System.currentTimeMillis();
        for (UUID id : KawaProtocol.NOTIFY_CHARS) {
            BluetoothGattCharacteristic c = svc.getCharacteristic(id);
            if (c == null) {
                log("Notify characteristic missing: " + id);
                continue;
            }
            enqueue(() -> {
                waiting = WAIT_DESCRIPTOR;
                g.setCharacteristicNotification(c, true);
                BluetoothGattDescriptor d = c.getDescriptor(KawaProtocol.CCCD);
                if (d == null) {
                    log("No CCCD on " + id);
                    opDone();
                    return;
                }
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            });
        }
        enqueue(() -> {
            log("Ready");
            setReady(true);
            opDone();
        });
    }

    /**
     * Debug: lists every service and characteristic with its properties and reads the standard (SIG)
     * readable characteristics, e.g. firmware revision. Never writes, never reads vendor characteristics
     * (Kawasaki's included) or anything in a firmware update service.
     */
    private void dump(BluetoothGatt g) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (BluetoothGattService s : g.getServices()) {
            lines.add(GattNames.label(s.getUuid()));
            boolean noRead = GattNames.isFirmwareUpdate(s.getUuid());
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int idx = lines.size();
                lines.add("  " + GattNames.label(c.getUuid()) + " [" + properties(c.getProperties()) + "]");
                if (noRead || !GattNames.isStandard(c.getUuid())
                        || (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) continue;
                enqueue(() -> {
                    waiting = WAIT_READ;
                    readLines = lines;
                    readIndex = idx;
                    if (!g.readCharacteristic(c)) {
                        lines.set(idx, lines.get(idx) + " = (read refused)");
                        readIndex = -1;
                        opDone();
                    }
                });
            }
        }
        enqueue(() -> {
            String d = String.join("\n", lines);
            log("Bluetooth services (debug):\n" + d);
            listener.onServicesDump(d);
            opDone();
        });
    }

    private static String properties(int p) {
        StringBuilder sb = new StringBuilder();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) sb.append("read ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) sb.append("write ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) sb.append("write-no-resp ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) sb.append("notify ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) sb.append("indicate ");
        return sb.toString().trim();
    }

    /**
     * Logs received frames without flooding the log: the first of each type, then only changes (the
     * meter's frame counter in byte 2 ignored). Riding-log frames the meter streams by itself are
     * counted and summed up every {@link #STREAM_LOG_MS}.
     */
    private void logRx(UUID id, byte[] v) {
        if (v.length == 0) return;
        int type = v[0] & 0xFF;
        rxCount[type]++;
        boolean streamed = false;
        for (int s : STREAMED) streamed |= s == type;
        byte[] last = lastRx[type];
        lastRx[type] = v;
        String head = "RX " + id.toString().substring(0, 8) + ": ";
        if (type == BikeFrames.MODEL_INFO) {
            // carries the VIN in plain text: never into the log (users share it)
            if (last == null) log(head + "03 model info (" + v.length + " bytes, not logged: VIN)");
        } else if (streamed ? rxCount[type] == 1 : last == null || !sameIgnoringCounter(last, v)) {
            log(head + KawaProtocol.hex(v));
        }
        long now = System.currentTimeMillis();
        if (now - streamLogAt < STREAM_LOG_MS) return;
        streamLogAt = now;
        StringBuilder sb = new StringBuilder();
        for (int s : STREAMED) {
            int n = rxCount[s] - rxCountLogged[s];
            rxCountLogged[s] = rxCount[s];
            if (n == 0) continue;
            sb.append(sb.length() == 0 ? "" : "; ")
                    .append(String.format(java.util.Locale.ROOT, "%02X x%d, last %s", s, n, KawaProtocol.hex(lastRx[s])));
        }
        if (sb.length() > 0) log("RX riding log frames in the last " + STREAM_LOG_MS / 1000 + " s: " + sb);
    }

    /** Same frame apart from byte 2, the counter the meter increments on every frame it sends. */
    private static boolean sameIgnoringCounter(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (i != 2 && a[i] != b[i]) return false;
        }
        return true;
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("Connected (status " + status + "), requesting MTU 300");
                    if (!g.requestMtu(300)) g.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("Link lost (status " + status + ")");
                    disconnect();
                    listener.onLinkLost();
                }
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            main.post(() -> {
                if (g != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) KawaBle.this.mtu = mtu;
                log("MTU " + mtu + " (status " + status + "), discovering services");
                g.discoverServices();
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> {
                if (g == gatt) onServices(g);
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> {
                if (g != gatt || waiting != WAIT_DESCRIPTOR) return; // late, after a timeout
                log("Notify on " + d.getCharacteristic().getUuid().toString().substring(0, 8)
                        + (status == BluetoothGatt.GATT_SUCCESS ? " enabled" : " failed, status " + status));
                opDone();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> {
                if (g != gatt || waiting != WAIT_WRITE) return; // late, after a timeout
                if (status != BluetoothGatt.GATT_SUCCESS) log("Write failed, status " + status);
                opDone();
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            main.post(() -> {
                if (g != gatt || waiting != WAIT_READ) return; // late, after a timeout
                if (readLines != null && readIndex >= 0) {
                    readLines.set(readIndex, readLines.get(readIndex) + " = "
                            + (status == BluetoothGatt.GATT_SUCCESS ? GattNames.value(value) : "(status " + status + ")"));
                    readIndex = -1;
                }
                opDone();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            UUID id = c.getUuid();
            main.post(() -> {
                if (g != gatt) return;
                logRx(id, value);
                listener.onNotify(id, value);
            });
        }
    };
}
