package io.github.darthbob.kawanav;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanFilter;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.companion.CompanionDeviceService;
import android.companion.DevicePresenceEvent;
import android.companion.ObservingDevicePresenceRequest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.os.Build;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Autostart: the bike is a BLE-only device that never connects to the phone by itself (unlike a
 * Bluetooth headset), it only advertises while the ignition is on. Android's companion device
 * manager watches for those advertisements in a low-power scan and binds this service when the
 * bike appears; we then start {@link BridgeService}. Companion apps may start foreground services
 * from the background, so this works with the phone locked in a pocket.
 *
 * Setup once, next to the bike with the ignition on: {@link #associate} shows the system dialog.
 * {@link #pair} does the same and then pairs (bonds) the phone with the bike, so Rideology is not
 * needed for the first pairing.
 */
@SuppressLint("MissingPermission") // every Bluetooth call is behind Hub.bluetoothAllowed
public class BikePresence extends CompanionDeviceService {

    public static final int REQ_ASSOCIATE = 2;

    // ------------------------------------------------------------------ presence events

    @Override
    @SuppressLint("NewApi") // Android calls this only from Android 16 (API 36) on
    public void onDevicePresenceEvent(DevicePresenceEvent event) {
        int e = event.getEvent();
        Logger.init(this);
        if (e == DevicePresenceEvent.EVENT_BLE_APPEARED || e == DevicePresenceEvent.EVENT_BT_CONNECTED) {
            appeared("event " + e);
        } else if (e == DevicePresenceEvent.EVENT_BLE_DISAPPEARED || e == DevicePresenceEvent.EVENT_BT_DISCONNECTED) {
            disappeared("event " + e);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onDeviceAppeared(AssociationInfo info) { // Android 13-15
        Logger.init(this);
        appeared("appeared");
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onDeviceDisappeared(AssociationInfo info) {
        Logger.init(this);
        disappeared("disappeared");
    }

    /** Remembered for a pause after a manual stop: see {@link #appeared}. */
    private void disappeared(String how) {
        Logger.log("Autostart: bike out of range (" + how + ")");
        if (Hub.autostartPaused(this) && Hub.prefs(this).getLong(Hub.KEY_BIKE_GONE_AT, 0) == 0) {
            Hub.prefs(this).edit().putLong(Hub.KEY_BIKE_GONE_AT, System.currentTimeMillis()).apply();
        }
    }

    private void appeared(String how) {
        if (!enabled(this)) return;
        if (Hub.autostartPaused(this)) {
            long goneAt = Hub.prefs(this).getLong(Hub.KEY_BIKE_GONE_AT, 0);
            if (goneAt == 0 || System.currentTimeMillis() - goneAt < Hub.BIKE_OFF_MS) {
                // the blip right after Stop (link closed, bike advertises again): stay stopped
                Hub.prefs(this).edit().putLong(Hub.KEY_BIKE_GONE_AT, 0).apply();
                Logger.log("Autostart: bike seen (" + how + "), but the bridge was stopped by hand: not starting");
                return;
            }
            Hub.setStoppedByUser(this, false);
            Logger.log("Autostart: bike was switched off since the manual stop: autostart active again");
        }
        if (Hub.serviceRunning) {
            Logger.log("Autostart: bike seen (" + how + "), bridge already running");
            return;
        }
        // the user may have revoked "Nearby devices" while the association stays: starting the bridge
        // would throw SecurityException on the first Bluetooth call, on the main thread
        if (!Hub.bluetoothAllowed(this)) {
            Logger.log("Autostart: bike seen (" + how + "), but " + Hub.BLUETOOTH_HINT);
            return;
        }
        Logger.log("Autostart: bike seen (" + how + "), starting the bridge");
        try {
            BridgeService.startAuto(this);
        } catch (Exception ex) {
            Logger.log("Autostart: could not start the bridge: " + ex);
        }
    }

    // ------------------------------------------------------------------ setup

    private static CompanionDeviceManager cdm(Context c) {
        return c.getSystemService(CompanionDeviceManager.class);
    }

    /** The association with the bike, or null when autostart is not set up. */
    public static AssociationInfo association(Context c) {
        List<AssociationInfo> l = cdm(c).getMyAssociations();
        return l.isEmpty() ? null : l.get(0);
    }

    public static boolean enabled(Context c) {
        return association(c) != null;
    }

    /**
     * Shows the system dialog listing nearby Kawasaki bikes (the paired one first). The bike must be
     * on: it is only found while it advertises (ignition on, no other app connected to it).
     */
    public static void associate(Activity a, BluetoothDevice paired) {
        associate(a, paired, false);
    }

    /**
     * Pairs the phone with the bike: picks it in the system dialog (which also sets up autostart),
     * then bonds with it. With autostart already set up, bonds with that bike directly.
     */
    public static void pair(Activity a) {
        AssociationInfo info = association(a);
        if (info != null) {
            bond(a, info);
        } else {
            Hub.pairStatus = "Looking for the bike: ignition on, Rideology closed…";
            associate(a, null, true);
        }
    }

    private static void bond(Context c, AssociationInfo info) {
        BluetoothAdapter adapter = c.getSystemService(BluetoothManager.class).getAdapter();
        BluetoothDevice d = adapter.getRemoteDevice(info.getDeviceMacAddress().toString().toUpperCase(java.util.Locale.ROOT));
        if (d.getBondState() == BluetoothDevice.BOND_BONDED) {
            paired(c, d);
            return;
        }
        Context app = c.getApplicationContext();
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                BluetoothDevice b = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                if (b == null || !b.getAddress().equals(d.getAddress())) return;
                int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                if (state == BluetoothDevice.BOND_BONDED) {
                    paired(ctx, b);
                    app.unregisterReceiver(this);
                } else if (state == BluetoothDevice.BOND_NONE) {
                    Hub.pairStatus = "Pairing failed: ignition on, Rideology closed, then try again";
                    Logger.log("Pairing with " + d.getAddress() + " failed or was declined");
                    app.unregisterReceiver(this);
                }
            }
        }, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED);
        Logger.log("Pairing with " + info.getDisplayName() + " (" + d.getAddress() + ")…");
        if (d.createBond()) {
            Hub.pairStatus = "Pairing… confirm on the phone (and the dash) if asked";
        } else {
            Hub.pairStatus = "Pairing could not start: ignition on, then try again";
            Logger.log("Pairing could not start (createBond refused)");
        }
    }

    private static void paired(Context c, BluetoothDevice d) {
        Hub.prefs(c).edit().putString(Hub.KEY_DEVICE, d.getAddress()).apply();
        Hub.pairStatus = "";
        Logger.log("Paired with " + d.getName() + " (" + d.getAddress() + ")");
    }

    private static void associate(Activity a, BluetoothDevice paired, boolean thenPair) {
        AssociationRequest.Builder b = new AssociationRequest.Builder().setSingleDevice(false);
        if (paired != null) {
            b.addDeviceFilter(new BluetoothLeDeviceFilter.Builder()
                    .setScanFilter(new ScanFilter.Builder().setDeviceAddress(paired.getAddress()).build()).build());
        }
        b.addDeviceFilter(new BluetoothLeDeviceFilter.Builder()
                .setNamePattern(Pattern.compile("(?i).*kawasaki.*")).build());
        Logger.log("Autostart: looking for the bike (ignition on)…");
        cdm(a).associate(b.build(), a.getMainExecutor(), new CompanionDeviceManager.Callback() {
            @Override
            public void onAssociationPending(IntentSender sender) {
                try {
                    a.startIntentSenderForResult(sender, REQ_ASSOCIATE, null, 0, 0, 0);
                } catch (IntentSender.SendIntentException e) {
                    Logger.log("Autostart: cannot show the dialog: " + e);
                }
            }

            @Override
            public void onAssociationCreated(AssociationInfo info) {
                Logger.log("Autostart: linked to " + info.getDisplayName() + " (" + info.getDeviceMacAddress() + ")");
                observe(a, info);
                if (thenPair) bond(a, info);
                if (a instanceof MainActivity) ((MainActivity) a).askBackgroundLocation();
            }

            @Override
            public void onFailure(CharSequence error) {
                Logger.log("Autostart: setup failed: " + error);
                if (thenPair) Hub.pairStatus = "No bike selected: ignition on, Rideology closed, then try again";
            }
        });
    }

    /** Asks Android to wake us when the bike appears; survives reboots (the system keeps it). */
    @SuppressWarnings("deprecation")
    public static void observe(Context c, AssociationInfo info) {
        try {
            if (Build.VERSION.SDK_INT >= 36) {
                cdm(c).startObservingDevicePresence(new ObservingDevicePresenceRequest.Builder()
                        .setAssociationId(info.getId()).build());
            } else {
                cdm(c).startObservingDevicePresence(info.getDeviceMacAddress().toString());
            }
            Logger.log("Autostart: watching for the bike (association " + info.getId() + ")");
        } catch (Exception e) {
            Logger.log("Autostart: cannot watch for the bike: " + e);
        }
    }

    public static void disable(Context c) {
        AssociationInfo info = association(c);
        if (info == null) return;
        cdm(c).disassociate(info.getId());
        Logger.log("Autostart off (bike link removed)");
    }
}
