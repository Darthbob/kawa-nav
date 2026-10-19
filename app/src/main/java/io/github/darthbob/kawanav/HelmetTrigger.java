package io.github.darthbob.kawanav;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * Autostart fallback: a Bluetooth headset (the helmet intercom) connects to the phone by itself when
 * switched on, which Android announces as ACL_CONNECTED (allowed for manifest receivers). When the
 * chosen helmet connects, the bridge is started like an autostart from {@link BikePresence}: it
 * stops again if the bike does not connect within a few minutes.
 *
 * Starting a foreground service from a broadcast needs a background-start exemption: unrestricted
 * battery use or the bike autostart link (companion app).
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is required to receive ACL broadcasts at all
public class HelmetTrigger extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) return;
        Logger.init(context);
        // the user may have revoked "Nearby devices": getAddress()/getName() would throw, and the
        // bridge could not talk to the bike either
        if (!Hub.bluetoothAllowed(context)) {
            Logger.log("Helmet connected, but " + Hub.BLUETOOTH_HINT);
            return;
        }
        BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        String helmet = Hub.prefs(context).getString(Hub.KEY_HELMET, null);
        if (d == null || helmet == null || !helmet.equals(d.getAddress())) return;

        // the helmet is switched on to ride: it always starts, also after a manual stop (that pause is
        // for the bike, which advertises again right after Stop; the helmet stays connected then)
        if (Hub.autostartPaused(context)) {
            Hub.setStoppedByUser(context, false);
            Logger.log("Helmet " + d.getName() + " connected: autostart pause after the manual stop ended");
        }
        if (Hub.serviceRunning) {
            Logger.log("Helmet " + d.getName() + " connected, bridge already running");
            return;
        }
        Logger.log("Helmet " + d.getName() + " connected: starting the bridge");
        try {
            BridgeService.startAuto(context);
        } catch (Exception e) {
            Logger.log("Helmet trigger: Android did not allow the start (" + e.getClass().getSimpleName()
                    + "); allow unrestricted battery use or set up the bike autostart");
        }
    }

    /** Paired Classic Bluetooth audio devices (headsets, intercoms) that can serve as trigger. */
    public static List<BluetoothDevice> candidates(Context c) {
        List<BluetoothDevice> out = new ArrayList<>();
        if (!Hub.bluetoothAllowed(c)) return out;
        BluetoothAdapter a = ((BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        if (a == null) return out;
        for (BluetoothDevice d : a.getBondedDevices()) {
            if (d.getType() == BluetoothDevice.DEVICE_TYPE_LE) continue; // the bike itself, watches, …
            BluetoothClass bc = d.getBluetoothClass();
            if (bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO) out.add(d);
        }
        return out;
    }
}
