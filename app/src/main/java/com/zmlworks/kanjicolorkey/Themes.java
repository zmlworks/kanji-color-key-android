package com.zmlworks.kanjicolorkey;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.WindowManager;

/** Shared light/dark handling for every activity (system or in-app choice), and window security flags. */
public final class Themes {
    private Themes() {}

    /** Call from attachBaseContext AFTER super.attachBaseContext(base). */
    public static void override(Activity a, Context base) {
        String t = new Settings(base).theme();
        if (Settings.THEME_SYSTEM.equals(t)) return;
        Configuration c = new Configuration();
        c.fontScale = 0; // only override uiMode
        int night = Settings.THEME_DARK.equals(t) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        c.uiMode = (base.getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        a.applyOverrideConfiguration(c);
    }

    public static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** True when following the system and the system's night mode differs from what this activity shows. */
    public static boolean systemNightChanged(Activity a, Configuration newConfig, boolean shownNight) {
        if (!Settings.THEME_SYSTEM.equals(new Settings(a).theme())) return false;
        boolean night = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night != shownNight;
    }

    public static int[] palette(Context c) {
        return isNight(c) ? Kck.COLOR_ARGB_DARK : Kck.COLOR_ARGB;
    }

    public static void applyWindowFlags(Activity a) {
        Settings s = new Settings(a);
        if (s.blockScreenshots()) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (android.os.Build.VERSION.SDK_INT >= 33) a.setRecentsScreenshotEnabled(!s.blockScreenshots());
        if (s.keepScreenOn()) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
