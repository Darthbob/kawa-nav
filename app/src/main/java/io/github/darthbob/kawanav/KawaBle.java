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
 * Android allows one outstanding GATT operation, so everything goes through a queue.
 * All callbacks to {@link Listener} run on the main thread.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked by MainActivity before use
public final class KawaBle {

    public interface Listener {
        void onLog(String line);
        void onStateChanged(boolean ready);

        /** The link dropped or a connection attempt failed (only while not intentionally disconnected). */
        void onLinkLost();
    }

    private static final long OP_TIMEOUT_MS = 3000;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final Runnable opTimeout = () -> {
        log("GATT op timed out, continuing");
        opDone();
    };

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private boolean busy;
    private byte[] lastTx;
    private boolean ready;

    public KawaBle(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * @param autoConnect true: let Android wait in the background until the bike is in range
     *                    (ignition on) and connect then, which is what a long-running bridge wants.
     */
    public void connect(BluetoothDevice device, boolean autoConnect) {
        disconnect();
        log("Connecting to " + device.getName() + " (" + device.getAddress() + ")"
                + (autoConnect ? ", waiting until it is in range" : ""));
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE);
    }

    public boolean isConnecting() {
        return gatt != null && !ready;
    }

    public void disconnect() {
        main.removeCallbacks(opTimeout);
        queue.clear();
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

    /** Queues a write (with response) to the meter's write characteristic. */
    public boolean write(byte[] data, String label) {
        if (!ready || writeChar == null) {
            log("Not connected, dropped: " + label);
            return false;
        }
        enqueue(() -> {
            int rc = gatt.writeCharacteristic(writeChar, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            // the bridge repeats the same frame every second; only log changes and errors
            if (rc != BluetoothGatt.GATT_SUCCESS || !java.util.Arrays.equals(data, lastTx)) {
                log("TX " + label + ": " + KawaProtocol.hex(data) + (rc == BluetoothGatt.GATT_SUCCESS ? "" : "  (rc=" + rc + ")"));
            }
            lastTx = data;
            if (rc != BluetoothGatt.GATT_SUCCESS) opDone();
        });
        return true;
    }

    private void enqueue(Runnable op) {
        queue.add(op);
        if (!busy) next();
    }

    private void next() {
        Runnable op = queue.poll();
        if (op == null || gatt == null) {
            busy = false;
            return;
        }
        busy = true;
        main.postDelayed(opTimeout, OP_TIMEOUT_MS);
        op.run();
    }

    private void opDone() {
        main.removeCallbacks(opTimeout);
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
        if (svc == null) {
            log("Kawasaki BLE5 service not found. Services on device:");
            for (BluetoothGattService s : g.getServices()) log("  " + s.getUuid());
            return;
        }
        writeChar = svc.getCharacteristic(KawaProtocol.WRITE_CHAR);
        if (writeChar == null) {
            log("Write characteristic not found");
            return;
        }
        for (UUID id : KawaProtocol.NOTIFY_CHARS) {
            BluetoothGattCharacteristic c = svc.getCharacteristic(id);
            if (c == null) {
                log("Notify characteristic missing: " + id);
                continue;
            }
            enqueue(() -> {
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
                log("MTU " + mtu + " (status " + status + "), discovering services");
                g.discoverServices();
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> onServices(g));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> {
                log("Notify on " + d.getCharacteristic().getUuid().toString().substring(0, 8)
                        + (status == BluetoothGatt.GATT_SUCCESS ? " enabled" : " failed, status " + status));
                opDone();
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> {
                if (status != BluetoothGatt.GATT_SUCCESS) log("Write failed, status " + status);
                opDone();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            log("RX " + c.getUuid().toString().substring(0, 8) + ": " + KawaProtocol.hex(value));
        }
    };
}
