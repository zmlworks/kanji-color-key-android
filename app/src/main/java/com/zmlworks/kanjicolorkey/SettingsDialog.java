package com.zmlworks.kanjicolorkey;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** The settings dialog (security options + theme), shared by the main, KeePass and restore screens. */
public final class SettingsDialog {
    private SettingsDialog() {}

    public interface Host {
        /** The theme changed: recreate the activity (keeping in-memory state where the activity can). */
        void onThemeChanged();
    }

    public static AlertDialog show(Activity a, Host host) {
        Settings settings = new Settings(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 4));
        box.addView(label(a, R.string.set_security));
        int[] labels = {R.string.set_screenshots, R.string.set_wipe, R.string.set_keep_screen, R.string.set_clip_clear};
        boolean[] on = {settings.blockScreenshots(), settings.wipeOnBackground(), settings.keepScreenOn(), settings.clipClear()};
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            CheckBox cb = new CheckBox(a);
            cb.setText(labels[i]);
            cb.setChecked(on[i]);
            cb.setTextSize(15);
            cb.setTextColor(a.getColor(R.color.text));
            cb.setPadding(dp(a, 8), dp(a, 10), 0, dp(a, 10));
            cb.setOnCheckedChangeListener((b, checked) -> {
                if (which == 0) settings.setBlockScreenshots(checked);
                else if (which == 1) settings.setWipeOnBackground(checked);
                else if (which == 2) settings.setKeepScreenOn(checked);
                else settings.setClipClear(checked);
                Themes.applyWindowFlags(a);
            });
            box.addView(cb);
        }
        box.addView(label(a, R.string.set_theme));
        RadioGroup rg = new RadioGroup(a);
        String[] themes = {Settings.THEME_SYSTEM, Settings.THEME_LIGHT, Settings.THEME_DARK};
        int[] tl = {R.string.theme_system, R.string.theme_light, R.string.theme_dark};
        for (int i = 0; i < 3; i++) {
            RadioButton rb = new RadioButton(a);
            rb.setId(View.generateViewId());
            rb.setText(tl[i]);
            rb.setTextSize(15);
            rb.setTextColor(a.getColor(R.color.text));
            rb.setTag(themes[i]);
            rg.addView(rb);
            if (themes[i].equals(settings.theme())) rg.check(rb.getId());
        }
        final AlertDialog[] ref = new AlertDialog[1];
        rg.setOnCheckedChangeListener((group, id) -> {
            String t = (String) group.findViewById(id).getTag();
            if (t.equals(settings.theme())) return;
            settings.setTheme(t);
            if (ref[0] != null) ref[0].dismiss();
            host.onThemeChanged();
        });
        box.addView(rg);
        TextView where = new TextView(a);
        where.setText(R.string.set_where);
        where.setTextSize(13);
        where.setTextColor(a.getColor(R.color.dim));
        where.setPadding(0, dp(a, 8), 0, dp(a, 2));
        box.addView(where);
        TextView note = new TextView(a);
        note.setText(R.string.set_note);
        note.setTextSize(13);
        note.setTextColor(a.getColor(R.color.dim));
        note.setPadding(0, dp(a, 8), 0, dp(a, 4));
        box.addView(note);
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        ref[0] = new AlertDialog.Builder(a)
                .setTitle(R.string.settings)
                .setView(sv)
                .setPositiveButton(R.string.close, null)
                .create();
        ref[0].show();
        return ref[0];
    }

    private static TextView label(Activity a, int res) {
        TextView t = new TextView(a);
        t.setText(res);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(a.getColor(R.color.text));
        t.setPadding(0, dp(a, 10), 0, dp(a, 2));
        return t;
    }

    private static int dp(Activity a, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics()));
    }
}
