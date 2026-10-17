package com.zmlworks.kanjicolorkey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.PersistableBundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
import java.util.Map;
import java.util.Set;

/** The real activity on the JVM (Robolectric, native graphics): behaviour checks + PNG screenshots in build/reports/screens/. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class UiTest {
    static final File OUT = new File("build/reports/screens");
    ActivityController<MainActivity> ctl;
    MainActivity a;

    @Before public void setUp() {
        PoolStore.get(RuntimeEnvironment.getApplication()); // loaded synchronously, so the activity renders at once
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit();
        ctl = Robolectric.buildActivity(MainActivity.class).setup();
        a = ctl.get();
        ShadowLooper.idleMainLooper();
    }

    <T extends View> T v(int id) { return a.findViewById(id); }
    String text(int id) { return ((TextView) v(id)).getText().toString(); }
    void click(int id) { v(id).performClick(); ShadowLooper.idleMainLooper(); }

    void waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long end = System.currentTimeMillis() + 60_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timeout");
            Thread.sleep(20);
            ShadowLooper.idleMainLooper();
        }
    }

    // ---------- security ----------
    @Test public void noPermissionsNoBackupSecureWindow() throws Exception {
        PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), PackageManager.GET_PERMISSIONS);
        assertTrue(pi.requestedPermissions == null || pi.requestedPermissions.length == 0);
        assertEquals(0, a.getApplicationInfo().flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
        assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
        // text fields: no autofill, no saved state, no personalised keyboard learning
        for (int id : new int[]{R.id.hex, R.id.bin, R.id.pass, R.id.salt, R.id.dec}) {
            EditText e = v(id);
            assertFalse(e.isSaveEnabled());
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, e.getImportantForAutofill());
            assertTrue((e.getImeOptions() & 0x1000000) != 0); // IME_FLAG_NO_PERSONALIZED_LEARNING
        }
    }

    @Test public void settingAllowsScreenshots() {
        new Settings(a).setAllowScreenshots(true);
        a.applyWindowFlags();
        assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        new Settings(a).setAllowScreenshots(false);
        a.applyWindowFlags();
        assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    @Test public void settingsDialogTogglesScreenshots() {
        click(R.id.bSettings);
        AlertDialog dlg = a.shownDialog();
        assertNotNull(dlg);
        android.widget.CheckBox first = findCheckBox(dlg.getWindow().getDecorView());
        assertNotNull(first);
        assertFalse(first.isChecked());
        first.performClick();
        assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        assertTrue(new Settings(a).allowScreenshots());
        first.performClick();
        assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    static android.widget.CheckBox findCheckBox(View v) {
        if (v instanceof android.widget.CheckBox) return (android.widget.CheckBox) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            android.widget.CheckBox c = findCheckBox(((ViewGroup) v).getChildAt(i));
            if (c != null) return c;
        }
        return null;
    }

    @Test public void wipesKeyAndInputsInTheBackground() {
        ((EditText) v(R.id.hex)).setText("abcd");
        ((EditText) v(R.id.pass)).setText("secret");
        ((EditText) v(R.id.dec)).setText("(8bit)a1b2");
        String before = text(R.id.keyInfo);
        ctl.pause().stop();
        assertTrue(a.isWiped());
        assertEquals("", text(R.id.hex));
        assertEquals("", text(R.id.pass));
        assertEquals("", text(R.id.dec));
        assertEquals("", text(R.id.keyInfo));
        assertEquals(0, ((ViewGroup) v(R.id.cards)).getChildCount());
        ctl.start().resume();
        ShadowLooper.idleMainLooper();
        assertFalse(a.isWiped());
        assertTrue(text(R.id.keyInfo).startsWith("鍵 (256bit) "));
        assertNotEquals(before, text(R.id.keyInfo));
        assertEquals(a.getString(R.string.wiped), text(R.id.err));
    }

    @Test public void noWipeWhenSwitchedOff() {
        new Settings(a).setWipeOnBackground(false);
        ((EditText) v(R.id.hex)).setText("abcd");
        String before = text(R.id.keyInfo);
        ctl.pause().stop().start().resume();
        assertEquals("abcd", text(R.id.hex));
        assertEquals(before, text(R.id.keyInfo));
    }

    @Test public void clipboardCopyIsSensitiveAndCleared() {
        click(R.id.bCopyKey);
        ClipboardManager cm = a.getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        assertNotNull(clip);
        String hex = clip.getItemAt(0).getText().toString();
        assertEquals(64, hex.length());
        assertTrue(text(R.id.keyInfo).endsWith(hex));
        PersistableBundle ex = clip.getDescription().getExtras();
        assertTrue(ex.getBoolean("android.content.extra.IS_SENSITIVE"));
        assertTrue(SecureClip.isPending());
        ShadowLooper.idleMainLooper(SecureClip.CLEAR_MS + 10, java.util.concurrent.TimeUnit.MILLISECONDS);
        ClipData after = cm.getPrimaryClip();
        assertTrue(after == null || after.getItemCount() == 0 || after.getItemAt(0).getText() == null || after.getItemAt(0).getText().length() == 0);
    }

    @Test public void clipboardNotClearedIfReplaced() {
        click(R.id.bCopyKey);
        ClipboardManager cm = a.getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("other", "something else"));
        ShadowLooper.idleMainLooper(SecureClip.CLEAR_MS + 10, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals("something else", cm.getPrimaryClip().getItemAt(0).getText().toString());
    }

    // ---------- encode (web parity) ----------
    @Test public void initialRandomKeyRendersBothEndings() {
        assertTrue(text(R.id.keyInfo).matches("鍵 \\(256bit\\) [0-9a-f]{64}"));
        assertEquals("256", text(R.id.bitsV));
        WebModel.Result r = a.last;
        assertNotNull(r);
        assertEquals(r.common, ((ViewGroup) v(R.id.cards)).getChildCount());
        assertEquals(WebModel.head(r), text(R.id.info));
        assertTrue(r.a.text.startsWith("M(256bit)"));
        byte[] k = a.keyBits();
        assertEquals(r.a.text, Kck.encodeMixed(PoolStore.cached().pool, k, 0, Set.of(), false).text);
    }

    @Test public void tapTogglesSymbolAndLaterPositionsReset() {
        ViewGroup cards = v(R.id.cards);
        cards.getChildAt(1).performClick(); ShadowLooper.idleMainLooper();
        assertEquals(Set.of(2), a.alphaPositions());
        assertEquals(Kck.Type.ALPHA, a.last.a.tokens.get(1).type);
        cards = v(R.id.cards);
        cards.getChildAt(3).performClick(); ShadowLooper.idleMainLooper();
        assertEquals(Set.of(2, 4), a.alphaPositions());
        ((ViewGroup) v(R.id.cards)).getChildAt(1).performClick(); ShadowLooper.idleMainLooper();
        assertEquals(Set.of(), a.alphaPositions()); // back to kanji at 2, 4 reset
        click(R.id.bAllA);
        for (Kck.Token t : a.last.a.tokens) assertEquals(Kck.Type.ALPHA, t.type);
        click(R.id.bAllK);
        assertEquals(Set.of(), a.alphaPositions());
    }

    @Test public void hexBinaryAndCheckBits() {
        String hex = "c2fb9f70272dae3adee45198b576b4873f5ad57ac41696bdfaa92e6de9923e14";
        ((EditText) v(R.id.hex)).setText(hex);
        click(R.id.bHex);
        assertEquals("鍵 (256bit) " + hex, text(R.id.keyInfo));
        assertEquals("256（入力値）", text(R.id.bitsV));
        click(R.id.chkPlus);
        assertTrue(a.last.a.text.startsWith("M(256+1bit)"));
        ((EditText) v(R.id.chk)).setText("12");
        ShadowLooper.idleMainLooper();
        assertEquals(Kck.encodeMixed(PoolStore.cached().pool, Kck.hexToBits(hex), 12, Set.of(), false).text, a.last.a.text);
        ((EditText) v(R.id.chk)).setText("99");
        assertEquals("64", text(R.id.chk));
        ((EditText) v(R.id.hex)).setText("xyz");
        click(R.id.bHex);
        assertEquals("invalid hex", text(R.id.err));
        ((EditText) v(R.id.bin)).setText("0101 1010_1111");
        click(R.id.bBin);
        assertEquals("鍵 (12bit) 5af", text(R.id.keyInfo));
        click(R.id.bRand);
        assertTrue(text(R.id.keyInfo).startsWith("鍵 (256bit) "));
    }

    @Test public void passphraseDerivationMatchesWebApp() throws Exception {
        ((EditText) v(R.id.pass)).setText("correct horse battery staple");
        ((EditText) v(R.id.salt)).setText("me");
        click(R.id.bPass);
        assertEquals("導出中…", text(R.id.keyInfo));
        waitFor(() -> text(R.id.keyInfo).startsWith("鍵"));
        String want = null;
        KckVectorsTest.load();
        for (Map<String, Object> k : KckVectorsTest.list("kdf")) if (KckVectorsTest.i(k, "iterations") == 600000) want = KckVectorsTest.s(k, "hex");
        assertEquals("鍵 (256bit) " + want, text(R.id.keyInfo));
        assertEquals(want, text(R.id.hex));
    }

    // ---------- decode ----------
    @SuppressWarnings("unchecked")
    @Test public void demoDecodesWithUnknownColor() throws Exception {
        click(R.id.bDemo);
        WebModel.Demo dm = WebModel.demo(PoolStore.cached().pool);
        assertEquals(dm.decodeInput, text(R.id.dec));
        assertEquals(Set.of(3, 6), a.alphaPositions());
        LinearLayout out = v(R.id.decOut);
        waitFor(() -> out.getChildCount() > 1);
        String summary = ((TextView) out.getChildAt(0)).getText().toString();
        assertTrue(summary, summary.startsWith("4通り試行 → 候補 "));
        // same candidate list, in the same order, as the web app's demo (vectors.json "demo")
        KckVectorsTest.load();
        java.util.List<Object> want = (java.util.List<Object>) ((Map<String, Object>) KckVectorsTest.V.get("demo")).get("candidates");
        assertEquals(want.size() + 1, out.getChildCount());
        for (int i = 0; i < want.size(); i++)
            assertEquals(want.get(i), ((TextView) ((ViewGroup) out.getChildAt(i + 1)).getChildAt(0)).getText().toString());
        assertTrue(want.contains(WebModel.DEMO_HEX));
    }

    @Test public void decodeErrorIsShown() throws Exception {
        ((EditText) v(R.id.dec)).setText("従0231");
        click(R.id.bDec);
        LinearLayout out = v(R.id.decOut);
        waitFor(() -> out.getChildCount() == 1 && !((TextView) out.getChildAt(0)).getText().toString().equals("デコード中…"));
        assertEquals("missing header like (256+12bit)", ((TextView) out.getChildAt(0)).getText().toString());
    }

    @Test public void decodesKanjiFormatWrittenWithColorNames() throws Exception {
        byte[] k = Kck.randomBits(256);
        Kck.Encoded e = Kck.encodeBits(PoolStore.cached().pool, k, 12);
        String body = e.text.substring("(256+12bit)".length()).replace('0', '黒').replace('1', '赤').replace('2', '青').replace('3', '緑');
        ((EditText) v(R.id.dec)).setText("(256+12bit)" + body);
        click(R.id.bDec);
        LinearLayout out = v(R.id.decOut);
        waitFor(() -> out.getChildCount() > 1);
        assertEquals(Kck.bitsToHex(k), ((TextView) ((ViewGroup) out.getChildAt(1)).getChildAt(0)).getText().toString());
    }

    @Test public void explainOpens() {
        assertEquals(View.GONE, v(R.id.explain).getVisibility());
        click(R.id.explainToggle);
        assertEquals(View.VISIBLE, v(R.id.explain).getVisibility());
        assertTrue(text(R.id.explain).contains("Grok Bot"));
        assertTrue(text(R.id.footer).contains("KanjiVG"));
    }

    // ---------- screenshots ----------
    static final int W = 1233, H = 2673;

    Bitmap shot() {
        View d = a.getWindow().getDecorView();
        d.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        d.layout(0, 0, W, H);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        d.draw(c);
        return b;
    }

    void save(Bitmap b, String name) throws Exception {
        OUT.mkdirs();
        try (FileOutputStream o = new FileOutputStream(new File(OUT, name))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    void scrollToView(int id, int above) {
        ScrollView s = v(R.id.scroll);
        View t = v(id);
        int y = 0;
        for (View p = t; p != s; p = (View) p.getParent()) y += p.getTop();
        s.scrollTo(0, Math.max(0, y - above));
    }

    @Test public void screenshots() throws Exception {
        // fixed key so the pictures are stable
        ((EditText) v(R.id.hex)).setText(WebModel.DEMO_HEX);
        click(R.id.bHex);
        ((EditText) v(R.id.hex)).setText("");
        shot(); save(shot(), "01-top.png");
        scrollToView(R.id.seqBox, 20); save(shot(), "02-encode.png");
        ((ViewGroup) v(R.id.cards)).getChildAt(2).performClick(); ShadowLooper.idleMainLooper();
        shot(); scrollToView(R.id.seqBox, 20); save(shot(), "03-encode-glyph-at-3.png");
        click(R.id.bAllA);
        shot(); scrollToView(R.id.seqBox, 20); save(shot(), "04-all-alpha.png");
        click(R.id.bDemo);
        LinearLayout out = v(R.id.decOut);
        waitFor(() -> out.getChildCount() > 1);
        shot(); scrollToView(R.id.dec, 400); save(shot(), "05-demo-decode.png");
        click(R.id.explainToggle);
        shot(); scrollToView(R.id.explainToggle, 20); save(shot(), "06-explain.png");
        click(R.id.bSettings);
        AlertDialog dlg = a.shownDialog();
        assertNotNull(dlg);
        save(withDialog(dlg), "07-settings.png");
        dlg.dismiss();
        click(R.id.bLicenses);
        save(withDialog(a.shownDialog()), "08-licenses.png");
    }

    Bitmap withDialog(AlertDialog dlg) {
        ShadowLooper.idleMainLooper();
        Bitmap b = shot();
        Canvas c = new Canvas(b);
        c.drawColor(0x99000000);
        View d = dlg.getWindow().getDecorView();
        int w = W - 2 * 80;
        d.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H - 400, View.MeasureSpec.AT_MOST));
        d.layout(0, 0, w, d.getMeasuredHeight());
        c.save();
        c.translate(80, (H - d.getMeasuredHeight()) / 2f);
        d.draw(c);
        c.restore();
        return b;
    }
}
