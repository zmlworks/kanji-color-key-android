package com.zmlworks.kanjicolorkey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** 0.4.3 restore screen (IME-safe char blocks, sample = flick surface, auto mismatch) + light/dark screenshots. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RestoreUiTest {
    static final File OUT = new File(System.getenv("KCK_SCREENS_DIR") != null
            ? System.getenv("KCK_SCREENS_DIR") : "build/reports/screens-0.4.3");
    static final int W = 411 * 3, H = 891 * 3;

    @Before public void setUp() {
        PoolStore.get(RuntimeEnvironment.getApplication());
        PoolStore.kana(RuntimeEnvironment.getApplication());
        RamKeyStore.get().clear();
        prefs().edit().clear().commit();
        OUT.mkdirs();
    }

    @After public void tearDown() {
        RamKeyStore.get().clear();
        prefs().edit().clear().commit();
    }

    static android.content.SharedPreferences prefs() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0);
    }

    static void save(Bitmap b, String name) throws Exception {
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    static Bitmap shot(View root) { return shot(root, H); }

    static Bitmap shot(View root, int h) {
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(root.getMeasuredWidth(), root.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        return b;
    }

    static Bitmap withDialog(View root, AlertDialog dlg) {
        ShadowLooper.idleMainLooper();
        Bitmap b = shot(root);
        Canvas c = new Canvas(b);
        c.drawColor(0x99000000);
        View d = dlg.getWindow().getDecorView();
        int w = W - 2 * 80;
        d.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H - 400, View.MeasureSpec.AT_MOST));
        d.layout(0, 0, w, d.getMeasuredHeight());
        c.save();
        c.translate(80, (H - d.getMeasuredHeight()) / 2f);
        d.draw(c);
        c.restore();
        return b;
    }

    static void idle() {
        for (int i = 0; i < 40; i++) { ShadowLooper.idleMainLooper(); try { Thread.sleep(10); } catch (InterruptedException ignored) { } }
    }

    static RestoreActivity start() {
        RestoreActivity a = Robolectric.buildActivity(RestoreActivity.class).setup().get();
        idle();
        return a;
    }

    static InputConnection ic(EditText e) {
        e.requestFocus();
        return e.onCreateInputConnection(new EditorInfo());
    }

    static RestoreActivity.BlockHolder block(RestoreActivity a, int i) { return a.blocksForTest().get(i); }

    static String ch(RestoreActivity a, int i) { return block(a, i).charIn.getText().toString(); }

    @Test public void fiveBlocks_finishOffUntilOneChar() {
        RestoreActivity a = start();
        assertEquals(5, ((LinearLayout) a.findViewById(R.id.blockList)).getChildCount());
        assertFalse(a.findViewById(R.id.bFinish).isEnabled());
        block(a, 0).charIn.setText("山");
        ShadowLooper.idleMainLooper();
        assertTrue(a.findViewById(R.id.bFinish).isEnabled()); // no colors yet: still allowed
    }

    @Test public void imeComposition_isNotCommittedUntilConfirmed() {
        RestoreActivity a = start();
        EditText e = block(a, 0).charIn;
        InputConnection ic = ic(e);
        ic.setComposingText("あ", 1);
        ShadowLooper.idleMainLooper();
        assertTrue(RestoreActivity.isComposing(e.getText()));
        assertNull("composing あ must not be taken", block(a, 0).glyph);
        ic.setComposingText("あさ", 1);
        ShadowLooper.idleMainLooper();
        assertNull(block(a, 0).glyph);
        assertEquals("", ch(a, 1));
        ic.setComposingText("朝", 1); // conversion shown, still composing
        ShadowLooper.idleMainLooper();
        assertNull(block(a, 0).glyph);
        ic.commitText("朝", 1);
        ShadowLooper.idleMainLooper();
        assertNotNull(block(a, 0).glyph);
        assertEquals("朝", block(a, 0).glyph.ch);
        assertEquals(View.VISIBLE, block(a, 0).sampleRow.getVisibility());
    }

    @Test public void imeFinishComposing_withoutTextChange_commits() {
        RestoreActivity a = start();
        EditText e = block(a, 1).charIn;
        InputConnection ic = ic(e);
        ic.setComposingText("日", 1);
        ShadowLooper.idleMainLooper();
        assertNull(block(a, 1).glyph);
        ic.finishComposingText();
        idle();
        assertNotNull(block(a, 1).glyph);
        assertEquals("日", block(a, 1).glyph.ch);
    }

    @Test public void multiCharCommit_splitsIntoFollowingBlocks() {
        RestoreActivity a = start();
        block(a, 2).charIn.setText("x"); // block 4 occupied → a new block gets inserted for the 3rd char
        ShadowLooper.idleMainLooper();
        block(a, 3).charIn.setText("");
        ShadowLooper.idleMainLooper();
        InputConnection ic = ic(block(a, 0).charIn);
        ic.commitText("朝日山", 1);
        ShadowLooper.idleMainLooper();
        assertEquals("朝", ch(a, 0));
        assertEquals("日", ch(a, 1));
        assertEquals("山", ch(a, 2));
        assertEquals("x", ch(a, 3));
        assertEquals(6, a.blocksForTest().size());
    }

    @Test public void focusLoss_commits() {
        RestoreActivity a = start();
        EditText e = block(a, 0).charIn;
        e.requestFocus();
        e.getText().append("木");
        ShadowLooper.idleMainLooper();
        block(a, 1).charIn.requestFocus();
        ShadowLooper.idleMainLooper();
        assertEquals("木", block(a, 0).glyph.ch);
    }

    @Test public void sampleIsTheFlickSurface_realTouch() throws Exception {
        RestoreActivity a = start();
        block(a, 0).charIn.setText("川");
        ShadowLooper.idleMainLooper();
        View root = a.findViewById(R.id.root);
        shot(root);
        FlickSurfaceLayout s = block(a, 0).sampleBox;
        assertFalse(s.isShowingPetalsForTest());
        float cx = s.getWidth() / 2f, cy = s.getHeight() / 2f;
        long t = SystemClock.uptimeMillis();
        s.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0));
        assertTrue(s.isShowingPetalsForTest());
        s.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_MOVE, cx + 200, cy, 0));
        s.dispatchTouchEvent(MotionEvent.obtain(t, t + 90, MotionEvent.ACTION_UP, cx + 200, cy, 0));
        assertFalse(s.isShowingPetalsForTest());
        assertEquals(1, block(a, 0).colors[0]); // right = 赤
        // tap in place = ?
        s.dispatchTouchEvent(MotionEvent.obtain(t, t + 200, MotionEvent.ACTION_DOWN, cx, cy, 0));
        s.dispatchTouchEvent(MotionEvent.obtain(t, t + 260, MotionEvent.ACTION_UP, cx + 3, cy, 0));
        assertEquals(-1, block(a, 0).colors[1]);
        // up = 黒 → third stroke; highlight then stays on the last stroke
        s.dispatchTouchEvent(MotionEvent.obtain(t, t + 300, MotionEvent.ACTION_DOWN, cx, cy, 0));
        s.dispatchTouchEvent(MotionEvent.obtain(t, t + 360, MotionEvent.ACTION_UP, cx, cy - 200, 0));
        assertEquals(0, block(a, 0).colors[2]);
        assertEquals(View.GONE, block(a, 0).fallbackRow.getVisibility());
    }

    @Test public void finish_withMiddleEmptyAndUnfilled_doesNotCrash() {
        RestoreActivity a = start();
        block(a, 0).charIn.setText("山");
        block(a, 2).charIn.setText("川");
        ShadowLooper.idleMainLooper();
        for (int i = 0; i < 3; i++) block(a, 0).sampleBox.getListenerForTest().onColor(i % 4);
        a.findViewById(R.id.bFinish).performClick();
        idle();
        assertEquals(View.VISIBLE, a.findViewById(R.id.panelResults).getVisibility());
        String all = allText(a.findViewById(R.id.resultsBox));
        assertTrue(all, all.contains("スキップ"));
        assertTrue(all, all.contains("?"));
    }

    @Test public void finish_unknownChar_showsErrorNoCrash() {
        RestoreActivity a = start();
        block(a, 0).charIn.setText("☃");
        ShadowLooper.idleMainLooper();
        a.findViewById(R.id.bFinish).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(View.GONE, a.findViewById(R.id.panelResults).getVisibility());
        assertTrue(((TextView) a.findViewById(R.id.status)).getText().toString().contains("☃"));
    }

    static String allText(View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof TextView) sb.append(((TextView) v).getText()).append('\n');
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) sb.append(allText(((ViewGroup) v).getChildAt(i)));
        return sb.toString();
    }

    /** Two wrong colors with 16 check bits: exact fails → depth 1 runs automatically → depth-2 question appears. */
    @Test public void mismatch_autoDepth1_thenAsksDepth2() throws Exception {
        RestoreActivity a = start();
        String hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Kck.Encoded enc = Kck.encodeMixed(PoolStore.cached().pool, Kck.hexToBits(hex), 16,
                java.util.Collections.emptySet(), java.util.Collections.emptySet(), Kck.Tail.KANJI,
                PoolStore.cachedKana().set);
        ((EditText) a.findViewById(R.id.checkBits)).setText("16");
        List<Kck.Token> toks = enc.tokens;
        while (a.blocksForTest().size() < toks.size()) a.findViewById(R.id.bAddChar).performClick();
        for (int i = 0; i < toks.size(); i++) {
            block(a, i).charIn.setText(toks.get(i).ch);
            ShadowLooper.idleMainLooper();
            int[] cs = toks.get(i).colors;
            for (int j = 0; j < cs.length; j++) {
                int c = cs[j];
                if (i == 0 && j < 2) c = (c + 1) % 4; // two wrong strokes
                block(a, i).sampleBox.getListenerForTest().onColor(c);
            }
        }
        a.findViewById(R.id.bFinish).performClick();
        AlertDialog dlg = null;
        for (int i = 0; i < 1500 && (dlg = a.shownDialogForTest()) == null; i++) {
            ShadowLooper.idleMainLooper(); Thread.sleep(10);
        }
        assertNotNull("depth-2 question shown after automatic depth 1", dlg);
        assertTrue(dlg.isShowing());
        save(withDialog(a.findViewById(R.id.root), dlg), "10-restore-ask-depth2.png");
        // accept → depth 2 finds the key (the two flipped strokes are the first two digits)
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        String all = "";
        for (int i = 0; i < 3000; i++) {
            ShadowLooper.idleMainLooper(); Thread.sleep(10);
            all = allText(a.findViewById(R.id.resultsBox));
            if (all.contains(hex)) break;
        }
        assertTrue(all, all.contains(hex));
        save(shot(a.findViewById(R.id.root)), "18-restore-found-depth2.png");
    }

    @Test public void screenshots_restore() throws Exception {
        RestoreActivity a = start();
        View root = a.findViewById(R.id.root);
        save(shot(root), "04-restore-5-blocks-light.png");

        InputConnection ic = ic(block(a, 0).charIn);
        ic.setComposingText("あさ", 1);
        ShadowLooper.idleMainLooper();
        save(shot(root), "05-restore-ime-composing.png");
        ic.commitText("朝", 1);
        ShadowLooper.idleMainLooper();
        block(a, 1).charIn.setText("日");
        ShadowLooper.idleMainLooper();
        RestoreActivity.BlockHolder h = block(a, 0);
        for (int i = 0; i < 5; i++) h.sampleBox.getListenerForTest().onColor(i % 4);
        shot(root);
        h.sampleBox.showPetalsForTest(h.sampleBox.getWidth() * 0.45f, h.sampleBox.getHeight() * 0.5f, 1);
        save(shot(root), "06-restore-flick-petals-light.png");
        h.sampleBox.showPetalsForTest(-1, -1, -1);
        java.lang.reflect.Field f = FlickSurfaceLayout.class.getDeclaredField("touching");
        f.setAccessible(true); f.setBoolean(h.sampleBox, false);
        for (int i = 5; i < h.colors.length; i++) h.sampleBox.getListenerForTest().onColor(i % 4);
        save(shot(root, H * 2), "07-restore-filled-light-tall.png");

        AlertDialog s = clickSettings(a);
        save(withDialog(root, s), "11-restore-settings-light.png");
        s.dismiss();

        prefs().edit().putString("theme", Settings.THEME_DARK).commit();
        RestoreActivity d = start(); // draft restored from RAM
        View droot = d.findViewById(R.id.root);
        assertEquals("朝", ch(d, 0));
        save(shot(droot), "08-restore-dark.png");
        RestoreActivity.BlockHolder dh = block(d, 1);
        shot(droot);
        dh.sampleBox.showPetalsForTest(dh.sampleBox.getWidth() * 0.6f, dh.sampleBox.getHeight() * 0.4f, 3);
        save(shot(droot), "09-restore-flick-petals-dark.png");
    }

    static AlertDialog clickSettings(Activity a) {
        a.findViewById(R.id.bSettings).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dlg = null;
        if (a instanceof RestoreActivity) dlg = ((RestoreActivity) a).shownDialogForTest();
        else if (a instanceof KeePassActivity) dlg = ((KeePassActivity) a).shownSettingsDialog();
        else if (a instanceof MainActivity) dlg = ((MainActivity) a).shownDialog();
        assertNotNull(dlg);
        return dlg;
    }

    @Test public void screenshots_mainAndKeepass() throws Exception {
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
        View mroot = m.findViewById(R.id.root);
        assertEquals(View.VISIBLE, m.findViewById(R.id.bSettings).getVisibility());
        shot(mroot);
        assertTrue("gear inside the screen", m.findViewById(R.id.bSettings).getWidth() > 0);
        int[] loc = new int[2];
        save(shot(mroot), "01-main-light.png");
        save(withDialog(mroot, clickSettings(m)), "03-main-settings-light.png");

        KeePassActivity k = Robolectric.buildActivity(KeePassActivity.class).setup().get();
        idle();
        View kroot = k.getWindow().getDecorView().findViewById(android.R.id.content);
        save(shot(kroot), "12-keepass-light.png");
        assertTrue(k.findViewById(R.id.bSettings).isShown());

        prefs().edit().putString("theme", Settings.THEME_DARK).commit();
        MainActivity md = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle();
        save(shot(md.findViewById(R.id.root)), "02-main-dark.png");

        KeePassActivity kd = Robolectric.buildActivity(KeePassActivity.class).setup().get();
        idle();
        assertTrue(Themes.isNight(kd));
        View kdroot = kd.getWindow().getDecorView().findViewById(android.R.id.content);
        save(shot(kdroot), "13-keepass-dark.png");
        kd.findViewById(R.id.tabOpen).performClick();
        ShadowLooper.idleMainLooper();
        save(shot(kdroot), "14-keepass-dark-open.png");
        kd.findViewById(R.id.tabCreate).performClick();
        ShadowLooper.idleMainLooper();
        save(shot(kdroot), "15-keepass-dark-create.png");
        save(withDialog(kdroot, clickSettings(kd)), "16-keepass-dark-settings.png");
    }

    /** Following the system (no in-app override): a night device gives a dark KeePass screen. */
    @Test @Config(qualifiers = "ja-w411dp-h891dp-port-night-xxhdpi")
    public void keepass_followsSystemNight() throws Exception {
        KeePassActivity k = Robolectric.buildActivity(KeePassActivity.class).setup().get();
        idle();
        assertTrue(Themes.isNight(k));
        assertEquals(k.getColor(R.color.bg), 0xFF15171C);
        save(shot(k.getWindow().getDecorView().findViewById(android.R.id.content)), "17-keepass-system-night.png");
    }

    /** In-app light beats a night device. */
    @Test @Config(qualifiers = "ja-w411dp-h891dp-port-night-xxhdpi")
    public void keepass_inAppLightOverridesSystemNight() {
        prefs().edit().putString("theme", Settings.THEME_LIGHT).commit();
        KeePassActivity k = Robolectric.buildActivity(KeePassActivity.class).setup().get();
        assertFalse(Themes.isNight(k));
    }

    @Test public void restoreHeader_labelsNotSquished() {
        RestoreActivity a = start();
        shot(a.findViewById(R.id.root));
        TextView lab = a.findViewById(R.id.labelDataBits);
        assertEquals(1, lab.getLineCount());
        assertTrue(lab.getWidth() > lab.getHeight());
        assertEquals(1, ((TextView) a.findViewById(R.id.labelCheckBits)).getLineCount());
    }
}
