package com.zmlworks.kanjicolorkey;

import android.content.Context;
import android.content.SharedPreferences;

/** The only things written to disk: three switches (no key material). Excluded from backups. */
final class Settings {
    private final SharedPreferences p;
    Settings(Context c) { p = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    boolean allowScreenshots() { return p.getBoolean("allowScreenshots", false); }
    boolean wipeOnBackground() { return p.getBoolean("wipeOnBackground", true); }
    boolean keepScreenOn() { return p.getBoolean("keepScreenOn", true); }
    void setAllowScreenshots(boolean v) { p.edit().putBoolean("allowScreenshots", v).apply(); }
    void setWipeOnBackground(boolean v) { p.edit().putBoolean("wipeOnBackground", v).apply(); }
    void setKeepScreenOn(boolean v) { p.edit().putBoolean("keepScreenOn", v).apply(); }
}
