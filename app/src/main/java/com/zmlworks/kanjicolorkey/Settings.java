package com.zmlworks.kanjicolorkey;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The only things written to disk: a few switches and the theme (no key material). Excluded from backups.
 * Since 0.2.0 every security option is opt-in (off unless the user turns it on). Migration from 0.1.0:
 * a value the user saved is kept; "allowScreenshots" (0.1.0) becomes blockScreenshots = !allowScreenshots;
 * options that were never set take the new defaults (off).
 */
final class Settings {
    static final String THEME_SYSTEM = "system", THEME_LIGHT = "light", THEME_DARK = "dark";
    private final SharedPreferences p;
    Settings(Context c) { p = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    boolean blockScreenshots() {
        if (p.contains("blockScreenshots")) return p.getBoolean("blockScreenshots", false);
        if (p.contains("allowScreenshots")) return !p.getBoolean("allowScreenshots", false); // saved by 0.1.0
        return false;
    }
    boolean wipeOnBackground() { return p.getBoolean("wipeOnBackground", false); }
    boolean keepScreenOn() { return p.getBoolean("keepScreenOn", false); }
    boolean clipClear() { return p.getBoolean("clipClear", false); }
    String theme() {
        String t = p.getString("theme", THEME_SYSTEM);
        return THEME_LIGHT.equals(t) || THEME_DARK.equals(t) ? t : THEME_SYSTEM;
    }

    void setBlockScreenshots(boolean v) { p.edit().putBoolean("blockScreenshots", v).remove("allowScreenshots").apply(); }
    void setWipeOnBackground(boolean v) { p.edit().putBoolean("wipeOnBackground", v).apply(); }
    void setKeepScreenOn(boolean v) { p.edit().putBoolean("keepScreenOn", v).apply(); }
    void setClipClear(boolean v) { p.edit().putBoolean("clipClear", v).apply(); }
    void setTheme(String t) { p.edit().putString("theme", t).apply(); }
}
