package io.github.darthbob.kawanav;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * Speaks the curve and speed-camera warnings and the navigation's instructions with Android's
 * text-to-speech (in the phone's language, see {@link NavText}), or plays the warnings' beeps
 * ({@link CurveBeeps}), as navigation guidance: the same audio Google Maps' voice uses, so it goes to
 * the helmet intercom the same way and lowers music while it plays. Main thread only (the engine's
 * progress callbacks only give the audio focus back).
 */
final class CurveSound {

    /** A phrase asked for while the engine was still starting is said only if it is this fresh. */
    private static final long PENDING_MAX_MS = 3_000;
    private static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    private static final AudioAttributes BEEP_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final TextToSpeech tts;
    private boolean ready;
    private boolean failed;
    private String pending;
    private long pendingAt;
    private int said;
    /** The beeps playing now, or null. */
    private AudioTrack beeping;

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
        // the phrases are in the phone's language when NavText has it, else English
        Locale lang = NavText.phone().locale();
        if (tts.setLanguage(lang) < TextToSpeech.LANG_AVAILABLE) {
            Logger.log("Curve voice: no " + lang.getDisplayLanguage(Locale.ENGLISH) + " voice installed, using the phone's default");
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
        if (pending != null && System.currentTimeMillis() - pendingAt <= PENDING_MAX_MS) speak(pending, true);
        pending = null;
    }

    /** Says it now, cutting off a phrase still being said. */
    void say(String text) {
        say(text, true);
    }

    /** Says it once a phrase still being said is done (a speed camera must not cut off a bend). */
    void sayAfter(String text) {
        say(text, false);
    }

    private void say(String text, boolean cutOff) {
        if (failed) return;
        if (!ready) {
            pending = text;
            pendingAt = System.currentTimeMillis();
            return;
        }
        speak(text, cutOff);
    }

    private void speak(String text, boolean cutOff) {
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            Logger.log("Curve voice: audio in use (a call?), \"" + text + "\" not said");
            return;
        }
        if (cutOff) stopBeeps();
        int queue = cutOff ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        if (tts.speak(text, queue, null, "curve" + ++said) != TextToSpeech.SUCCESS) {
            audio.abandonAudioFocusRequest(focus);
            Logger.log("Curve voice: text-to-speech refused \"" + text + "\"");
        }
    }

    /** False once the phone turned out to have no text-to-speech: the warnings are beeped then. */
    boolean canSpeak() {
        return !failed;
    }

    /** Plays beeps (16-bit stereo at {@link CurveBeeps#RATE}), cutting off words still being said. */
    void beep(short[] pcm) {
        if (pcm.length == 0) return;
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            Logger.log("Curve voice: audio in use (a call?), beeps not played");
            return;
        }
        if (ready) tts.stop();
        stopBeeps();
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(BEEP_ATTRIBUTES)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(CurveBeeps.RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            track.write(pcm, 0, pcm.length);
            track.play();
        } catch (RuntimeException e) {
            audio.abandonAudioFocusRequest(focus);
            Logger.log("Curve voice: beeps not played: " + e);
            return;
        }
        beeping = track;
        long ms = pcm.length / 2 * 1000L / CurveBeeps.RATE;
        main.postDelayed(() -> {
            if (beeping != track) return; // cut off by the next warning, which has the focus now
            stopBeeps();
            audio.abandonAudioFocusRequest(focus);
        }, ms + 200);
    }

    private void stopBeeps() {
        if (beeping == null) return;
        beeping.release();
        beeping = null;
    }

    void shutdown() {
        main.removeCallbacksAndMessages(null);
        stopBeeps();
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
