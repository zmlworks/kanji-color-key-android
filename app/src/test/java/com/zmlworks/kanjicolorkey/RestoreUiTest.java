package com.zmlworks.kanjicolorkey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RestoreUiTest {
    static final File OUT = new File("/workspace/android/out/screens-0.4.0");

    @Before public void setUp() {
        PoolStore.get(RuntimeEnvironment.getApplication());
        PoolStore.kana(RuntimeEnvironment.getApplication());
        OUT.mkdirs();
    }

    static void save(Bitmap b, String name) throws Exception {
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    static Bitmap shot(View root) {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(411 * 3, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(891 * 3, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(root.getMeasuredWidth(), root.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        return b;
    }

    @Test public void restoreFlowScreenshots_andKeyLocked() throws Exception {
        ActivityController<RestoreActivity> ctl =
                Robolectric.buildActivity(RestoreActivity.class).setup();
        RestoreActivity a = ctl.get();
        ShadowLooper.idleMainLooper();
        // wait pool load
        for (int i = 0; i < 50; i++) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(20);
        }
        save(shot(a.findViewById(R.id.root)), "01-restore-chars.png");

        EditText chars = a.findViewById(R.id.charsIn);
        chars.setText("處"); // one kanji from pool
        a.findViewById(R.id.bStartStrokes).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, a.findViewById(R.id.panelStrokes).getVisibility());
        save(shot(a.findViewById(R.id.root)), "02-restore-flick.png");

        // Flick a few colors via listener
        FlickColorView flick = a.findViewById(R.id.flick);
        assertNotNull(flick);
        // Simulate entering colors until results (or a few strokes)
        for (int i = 0; i < 20; i++) {
            flick.getListenerForTest().onColor(i % 4);
            ShadowLooper.idleMainLooper();
            if (a.findViewById(R.id.panelResults).getVisibility() == View.VISIBLE) break;
        }
        if (a.findViewById(R.id.panelResults).getVisibility() == View.VISIBLE) {
            save(shot(a.findViewById(R.id.root)), "03-restore-results.png");
        } else {
            // force finish by filling remaining with ?
            for (int i = 0; i < 40; i++) {
                flick.getListenerForTest().onColor(-1);
                ShadowLooper.idleMainLooper();
                if (a.findViewById(R.id.panelResults).getVisibility() == View.VISIBLE) break;
            }
            save(shot(a.findViewById(R.id.root)), "03-restore-results.png");
        }

        // Key-locked MainActivity
        String hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Intent i = new Intent(RuntimeEnvironment.getApplication(), MainActivity.class);
        i.putExtra(MainActivity.EXTRA_KEY_HEX, hex);
        i.putExtra(MainActivity.EXTRA_KEY_LOCKED, true);
        ActivityController<MainActivity> mctl = Robolectric.buildActivity(MainActivity.class, i).setup();
        MainActivity m = mctl.get();
        ShadowLooper.idleMainLooper();
        for (int k = 0; k < 30; k++) { ShadowLooper.idleMainLooper(); Thread.sleep(20); }
        assertTrue(!m.findViewById(R.id.bRand).isEnabled());
        assertEquals(View.VISIBLE, m.findViewById(R.id.keyLockedHint).getVisibility());
        save(shot(m.findViewById(R.id.root)), "04-key-locked.png");

        // Dark restore
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0)
                .edit().putString("theme", Settings.THEME_DARK).commit();
        ActivityController<RestoreActivity> dark =
                Robolectric.buildActivity(RestoreActivity.class).setup();
        ShadowLooper.idleMainLooper();
        for (int k = 0; k < 30; k++) { ShadowLooper.idleMainLooper(); Thread.sleep(20); }
        save(shot(dark.get().findViewById(R.id.root)), "05-restore-chars-dark.png");
    }
}
