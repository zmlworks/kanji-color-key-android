package com.zmlworks.kanjicolorkey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

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
    static final File OUT = new File("/workspace/android/out/screens-0.4.2");

    @Before public void setUp() {
        PoolStore.get(RuntimeEnvironment.getApplication());
        PoolStore.kana(RuntimeEnvironment.getApplication());
        RamKeyStore.get().clear();
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
        Bitmap b = Bitmap.createBitmap(Math.max(1, root.getMeasuredWidth()),
                Math.max(1, root.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        return b;
    }

    @Test public void restoreBlocks_andRamStoreScreens() throws Exception {
        ActivityController<RestoreActivity> ctl =
                Robolectric.buildActivity(RestoreActivity.class).setup();
        RestoreActivity a = ctl.get();
        for (int i = 0; i < 40; i++) { ShadowLooper.idleMainLooper(); Thread.sleep(15); }

        assertNotNull(a.findViewById(R.id.ramStoreBar));
        assertNotNull(a.findViewById(R.id.blockList));
        save(shot(a.findViewById(R.id.root)), "01-restore-empty.png");

        LinearLayout list = a.findViewById(R.id.blockList);
        assertTrue(list.getChildCount() >= 1);
        View first = list.getChildAt(0);
        EditText charIn = first.findViewById(R.id.charIn);
        charIn.setText("處");
        ShadowLooper.idleMainLooper();
        assertEquals(View.VISIBLE, first.findViewById(R.id.sampleRow).getVisibility());
        assertEquals(View.VISIBLE, first.findViewById(R.id.flick).getVisibility());

        FlickColorView flick = first.findViewById(R.id.flick);
        // fill strokes with colors (or ?)
        for (int i = 0; i < 30; i++) {
            flick.getListenerForTest().onColor(i % 4);
            ShadowLooper.idleMainLooper();
            if (a.findViewById(R.id.bFinish).isEnabled()) break;
        }
        save(shot(a.findViewById(R.id.root)), "02-restore-one-char.png");

        a.findViewById(R.id.bAddChar).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(list.getChildCount() >= 2);
        save(shot(a.findViewById(R.id.root)), "03-restore-two-blocks.png");

        // Draft saved in RAM
        assertTrue(RamKeyStore.get().hasRestoreDraft());
        RamStoreUi.refresh(a);
        save(shot(a.findViewById(R.id.root)), "04-restore-with-ram-status.png");

        // Clear store
        a.findViewById(R.id.bRamClear).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(RamKeyStore.get().hasRestoreDraft());
        save(shot(a.findViewById(R.id.ramStoreBar)), "05-ram-cleared.png");

        // Key-locked main via store
        RamKeyStore.get().setCompositeHex(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        android.content.Intent i = new android.content.Intent(
                RuntimeEnvironment.getApplication(), MainActivity.class);
        i.putExtra(MainActivity.EXTRA_USE_STORE_KEY, true);
        i.putExtra(MainActivity.EXTRA_KEY_LOCKED, true);
        MainActivity m = Robolectric.buildActivity(MainActivity.class, i).setup().get();
        for (int k = 0; k < 30; k++) { ShadowLooper.idleMainLooper(); Thread.sleep(15); }
        assertFalse(m.findViewById(R.id.bRand).isEnabled());
        assertNotNull(m.findViewById(R.id.ramStoreBar));
        save(shot(m.findViewById(R.id.root)), "06-main-store-key.png");

        // Dark restore
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0)
                .edit().putString("theme", Settings.THEME_DARK).commit();
        RamKeyStore.get().clear();
        RestoreActivity dark = Robolectric.buildActivity(RestoreActivity.class).setup().get();
        for (int k = 0; k < 30; k++) { ShadowLooper.idleMainLooper(); Thread.sleep(15); }
        save(shot(dark.findViewById(R.id.root)), "07-restore-dark.png");
    }
}
