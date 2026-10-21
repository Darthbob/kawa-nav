package io.github.darthbob.kawanav;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * Speaks the curve warnings with Android's text-to-speech, as navigation guidance: the same audio
 * Google Maps' voice uses, so it goes to the helmet intercom the same way and lowers music while it
 * speaks. Main thread only (the engine's progress callbacks only give the audio focus back).
 */
final class CurveSound {

    /** A phrase asked for while the engine was still starting is said only if it is this fresh. */
    private static final long PENDING_MAX_MS = 3_000;
    private static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final TextToSpeech tts;
    private boolean ready;
    private boolean failed;
    private String pending;
    private long pendingAt;
    private int said;

    CurveSound(Context context) {
        Context c = context.getApplicationContext();
        audio = c.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(ATTRIBUTES)
                .build();
        tts = new TextToSpeech(c, this::started);
    }

    private void started(int status) {
        if (status != TextToSpeech.SUCCESS) {
            failed = true;
            Logger.log("Curve voice: no text-to-speech on this phone (status " + status + ")");
            return;
        }
        // the phrases are English; without an English voice the phone's own one reads them
        if (tts.setLanguage(Locale.ENGLISH) < TextToSpeech.LANG_AVAILABLE) {
            Logger.log("Curve voice: no English voice installed, using the phone's language");
        }
        tts.setAudioAttributes(ATTRIBUTES);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {}

            @Override
            public void onDone(String id) {
                audio.abandonAudioFocusRequest(focus);
            }

            @Override
            public void onError(String id) {
                audio.abandonAudioFocusRequest(focus);
            }
        });
        ready = true;
        if (pending != null && System.currentTimeMillis() - pendingAt <= PENDING_MAX_MS) speak(pending);
        pending = null;
    }

    /** Says it now, cutting off a phrase still being said. */
    void say(String text) {
        if (failed) return;
        if (!ready) {
            pending = text;
            pendingAt = System.currentTimeMillis();
            return;
        }
        speak(text);
    }

    private void speak(String text) {
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            Logger.log("Curve voice: audio in use (a call?), \"" + text + "\" not said");
            return;
        }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "curve" + ++said) != TextToSpeech.SUCCESS) {
            audio.abandonAudioFocusRequest(focus);
            Logger.log("Curve voice: text-to-speech refused \"" + text + "\"");
        }
    }

    void shutdown() {
        tts.shutdown();
        audio.abandonAudioFocusRequest(focus);
    }

    /** A Bluetooth or wired headset is connected: what is said goes there, not out of the phone's speaker. */
    static boolean headsetConnected(Context c) {
        for (AudioDeviceInfo d : c.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (d.getType()) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }
}
