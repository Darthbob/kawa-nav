package io.github.darthbob.kawanav;

import android.app.Application;

/**
 * The process: runs before any screen, service or receiver, however the app was started (the user,
 * the bike appearing, the helmet connecting, a notification). The log is set up here so a crash
 * anywhere, from the first moment, lands in the log file ({@link Logger#installCrashHandler}). The
 * light or dark theme chosen in the settings is made sure of here too ({@link Ui#keepTheme}), and the
 * texts are set up in the language Android chose ({@link Lang}).
 */
public class KawaApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        Logger.installCrashHandler();
        Lang.init(this);
        Ui.keepTheme(this);
    }
}
