package io.github.darthbob.kawanav;

import android.app.Application;

/**
 * The process: runs before any screen, service or receiver, however the app was started (the user,
 * the bike appearing, the helmet connecting, a notification). The log is set up here so a crash
 * anywhere, from the first moment, lands in the log file ({@link Logger#installCrashHandler}).
 */
public class KawaApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        Logger.installCrashHandler();
    }
}
