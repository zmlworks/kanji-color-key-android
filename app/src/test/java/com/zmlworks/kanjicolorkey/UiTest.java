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
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
        PoolStore.kana(RuntimeEnvironment.getApplication());
        RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0).edit().clear().commit();
        start();
    }

    void start() {
        ctl = Robolectric.buildActivity(MainActivity.class).setup();
        a = ctl.get();
        ShadowLooper.idleMainLooper();
    }

    static android.content.SharedPreferences prefs() { return RuntimeEnvironment.getApplication().getSharedPreferences("settings", 0); }
    Kck.Pool pool() { return PoolStore.cached().pool; }
    Kck.KanaSet kana() { return PoolStore.cachedKana().set; }
    static Map<Integer, Kck.Type> t(Object... kv) {
        Map<Integer, Kck.Type> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((Integer) kv[i], (Kck.Type) kv[i + 1]);
        return m;
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
        // 0.2.0: every security option is opt-in, so by default screenshots are allowed and the screen may turn off
        assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // text fields: no autofill, no saved state, no personalised keyboard learning
        for (int id : new int[]{R.id.hex, R.id.bin, R.id.pass, R.id.salt, R.id.dec}) {
            EditText e = v(id);
            assertFalse(e.isSaveEnabled());
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, e.getImportantForAutofill());
            assertTrue((e.getImeOptions() & 0x1000000) != 0); // IME_FLAG_NO_PERSONALIZED_LEARNING
        }
    }

    @Test public void settingBlocksScreenshotsAndKeepsScreenOn() {
        Settings st = new Settings(a);
        assertFalse(st.blockScreenshots()); assertFalse(st.wipeOnBackground()); assertFalse(st.keepScreenOn()); assertFalse(st.clipClear());
        assertEquals(Settings.THEME_SYSTEM, st.theme());
        st.setBlockScreenshots(true); st.setKeepScreenOn(true);
        a.applyWindowFlags();
        assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
        st.setBlockScreenshots(false); st.setKeepScreenOn(false);
        a.applyWindowFlags();
        assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Test public void migrationFromVersion010KeepsSavedValues() {
        // 0.1.0 stored only what the user changed: allowScreenshots / wipeOnBackground / keepScreenOn
        prefs().edit().clear().putBoolean("allowScreenshots", false).putBoolean("wipeOnBackground", true).commit();
        Settings st = new Settings(a);
        assertTrue(st.blockScreenshots());      // the user had explicitly kept screenshots blocked
        assertTrue(st.wipeOnBackground());      // saved value kept
        assertFalse(st.keepScreenOn());         // never set: new default (off)
        assertFalse(st.clipClear());
        prefs().edit().clear().putBoolean("allowScreenshots", true).commit();
        assertFalse(new Settings(a).blockScreenshots());
        new Settings(a).setBlockScreenshots(true);
        assertFalse(prefs().contains("allowScreenshots"));
        assertTrue(new Settings(a).blockScreenshots());
        ctl.pause().stop().destroy();
        start();                                 // a restart applies the migrated value
        assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    @Test public void settingsDialogCheckboxesAllOffByDefault() {
        click(R.id.bSettings);
        AlertDialog dlg = a.shownDialog();
        assertNotNull(dlg);
        List<CheckBox> boxes = new ArrayList<>();
        collect(dlg.getWindow().getDecorView(), CheckBox.class, boxes);
        assertEquals(4, boxes.size());
        for (CheckBox b : boxes) assertFalse(b.getText().toString(), b.isChecked());
        boxes.get(0).performClick();
        assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertTrue(new Settings(a).blockScreenshots());
        boxes.get(0).performClick();
        assertEquals(0, a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        boxes.get(2).performClick();
        assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
        boxes.get(3).performClick();
        assertTrue(new Settings(a).clipClear());
        List<RadioButton> radios = new ArrayList<>();
        collect(dlg.getWindow().getDecorView(), RadioButton.class, radios);
        assertEquals(3, radios.size());
        assertTrue(radios.get(0).isChecked()); // follow the system
    }

    static <T extends View> void collect(View v, Class<T> c, List<T> out) {
        if (c.isInstance(v)) out.add(c.cast(v));
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), c, out);
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
        new Settings(a).setWipeOnBackground(true);
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

    @Test public void noWipeByDefault() {
        ((EditText) v(R.id.hex)).setText("abcd");
        String before = text(R.id.keyInfo);
        ctl.pause().stop().start().resume();
        assertEquals("abcd", text(R.id.hex));
        assertEquals(before, text(R.id.keyInfo));
    }

    @Test public void clipboardCopyIsSensitiveAndNotClearedByDefault() {
        click(R.id.bCopyKey);
        ClipboardManager cm = a.getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        assertTrue(clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
        assertFalse(SecureClip.isPending());
        ShadowLooper.idleMainLooper(SecureClip.CLEAR_MS + 10, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(64, cm.getPrimaryClip().getItemAt(0).getText().length());
    }

    @Test public void clipboardCopyIsSensitiveAndClearedWhenSwitchedOn() {
        new Settings(a).setClipClear(true);
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
        new Settings(a).setClipClear(true);
        click(R.id.bCopyKey);
        ClipboardManager cm = a.getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("other", "something else"));
        ShadowLooper.idleMainLooper(SecureClip.CLEAR_MS + 10, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals("something else", cm.getPrimaryClip().getItemAt(0).getText().toString());
    }

    // ---------- encode (web parity) ----------
    @Test public void initialRandomKeyRendersTheEndings() {
        assertTrue(text(R.id.keyInfo).matches("鍵 \\(256bit\\) [0-9a-f]{64}"));
        assertEquals("256", text(R.id.bitsV));
        WebModel.Result r = a.last;
        assertNotNull(r);
        assertEquals(r.common, ((ViewGroup) v(R.id.cards)).getChildCount());
        assertEquals(WebModel.head(r), text(R.id.info));
        assertTrue(r.first().text.startsWith("M(256bit)"));
        byte[] k = a.keyBits();
        assertEquals(r.first().text, Kck.encodeMixed(pool(), k, 0, Set.of(), false).text); // all kanji = v1.1 output
        assertTrue(((CheckBox) v(R.id.tAlpha)).isChecked());
        assertTrue(((CheckBox) v(R.id.tKana)).isChecked());
        assertFalse(((CheckBox) v(R.id.tFixed)).isChecked());
        assertFalse(((CheckBox) v(R.id.lookAlways)).isChecked());
        List<String> tails = new ArrayList<>();
        for (WebModel.Variant x : r.variants) tails.add(x.tailName());
        assertEquals("kanji", tails.get(0));
        assertTrue(tails.contains("alpha") || tails.contains("kana"));
    }

    void tap(int pos) { ((ViewGroup) v(R.id.cards)).getChildAt(pos - 1).performClick(); ShadowLooper.idleMainLooper(); }

    @Test public void tapRotatesKanjiAlphaKanaAndLaterPositionsReset() {
        tap(2);
        assertEquals(t(2, Kck.Type.ALPHA), a.symbolTypes());
        assertEquals(Kck.Type.ALPHA, a.last.first().tokens.get(1).type);
        tap(2);
        assertEquals(t(2, Kck.Type.KANA), a.symbolTypes());
        assertEquals(Kck.Type.KANA, a.last.first().tokens.get(1).type);
        tap(4);
        assertEquals(t(2, Kck.Type.KANA, 4, Kck.Type.ALPHA), a.symbolTypes());
        tap(2);                                                  // kana → kanji; 4 resets
        assertEquals(t(), a.symbolTypes());
        click(R.id.bAllA);
        for (Kck.Token x : a.last.first().tokens) assertEquals(Kck.Type.ALPHA, x.type);
        click(R.id.bAllKana);
        for (Kck.Token x : a.last.first().tokens) assertTrue(x.type == Kck.Type.KANA || x.type == Kck.Type.KANA_GLYPH);
        click(R.id.bAllK);
        assertEquals(t(), a.symbolTypes());
    }

    @Test public void typeTogglesMakeTheRotationTwoWay() {
        ((CheckBox) v(R.id.tKana)).performClick(); ShadowLooper.idleMainLooper();      // kana off: kanji ⇄ alpha
        tap(1); assertEquals(t(1, Kck.Type.ALPHA), a.symbolTypes());
        tap(1); assertEquals(t(), a.symbolTypes());
        assertFalse(v(R.id.bAllKana).isEnabled());
        assertFalse(v(R.id.tFixed).isEnabled());
        for (WebModel.Variant x : a.last.variants) assertTrue(x.tailName(), !x.tailName().equals("kana"));
        ((CheckBox) v(R.id.tKana)).performClick();
        ((CheckBox) v(R.id.tAlpha)).performClick(); ShadowLooper.idleMainLooper();     // alpha off: kanji ⇄ kana
        tap(1); assertEquals(t(1, Kck.Type.KANA), a.symbolTypes());
        tap(1); assertEquals(t(), a.symbolTypes());
        assertFalse(v(R.id.bAllA).isEnabled());
        tap(3);
        ((CheckBox) v(R.id.tKana)).performClick(); ShadowLooper.idleMainLooper();      // both off: only kanji, no taps
        assertEquals(t(), a.symbolTypes());                                              // kana positions dropped
        assertEquals(a.getString(R.string.encode_hint_none), text(R.id.encHint));
        assertFalse(((ViewGroup) v(R.id.cards)).getChildAt(0).isClickable());
        assertEquals(1, a.last.variants.size());
    }

    @Test public void allKanaVariableByDefaultAndKfOnRequest() {
        click(R.id.bAllKana);
        assertFalse(a.last.fixed);
        assertTrue(a.last.first().text.startsWith("M(256bit)"));
        ((CheckBox) v(R.id.tFixed)).performClick(); ShadowLooper.idleMainLooper();
        assertTrue(a.last.fixed);
        Kck.Encoded e = a.last.first();
        assertTrue(e.text.startsWith("KF(256bit)"));
        assertEquals(24, e.tokens.size());
        assertEquals(24, ((ViewGroup) v(R.id.cards)).getChildCount());
        assertEquals(Kck.encodeKanaFixed(kana(), a.keyBits(), 0).text, e.text);
        tap(1);                                                                          // KF cards are not tappable
        assertTrue(a.last.fixed);
        click(R.id.bAllK);
        assertFalse(a.last.fixed);                                                       // KF applies to all-kana only
    }

    @Test public void hexBinaryAndCheckBits() {
        String hex = "c2fb9f70272dae3adee45198b576b4873f5ad57ac41696bdfaa92e6de9923e14";
        ((EditText) v(R.id.hex)).setText(hex);
        click(R.id.bHex);
        assertEquals("鍵 (256bit) " + hex, text(R.id.keyInfo));
        assertEquals("256（入力値）", text(R.id.bitsV));
        click(R.id.chkPlus);
        assertTrue(a.last.first().text.startsWith("M(256+1bit)"));
        ((EditText) v(R.id.chk)).setText("12");
        ShadowLooper.idleMainLooper();
        assertEquals(Kck.encodeMixed(pool(), Kck.hexToBits(hex), 12, Set.of(), false).text, a.last.first().text);
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
        WebModel.Demo dm = WebModel.demo(pool(), kana());
        assertEquals(dm.decodeInput, text(R.id.dec));
        assertEquals(t(3, Kck.Type.ALPHA, 5, Kck.Type.KANA, 8, Kck.Type.KANA), a.symbolTypes());
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

    String decodeFirst(String input) throws Exception {
        ((EditText) v(R.id.dec)).setText(input);
        click(R.id.bDec);
        LinearLayout out = v(R.id.decOut);
        waitFor(() -> out.getChildCount() > 1 || (out.getChildCount() == 1 && !((TextView) out.getChildAt(0)).getText().toString().equals("デコード中…")));
        return out.getChildCount() > 1 ? ((TextView) ((ViewGroup) out.getChildAt(1)).getChildAt(0)).getText().toString() : null;
    }

    String summary() { return ((TextView) ((LinearLayout) v(R.id.decOut)).getChildAt(0)).getText().toString(); }

    @Test public void kanaRoundTripsThroughTheUi() throws Exception {
        for (int r = 0; r < 6; r++) {
            byte[] k = Kck.randomBits(256);
            Kck.Encoded e = r == 5 ? Kck.encodeKanaFixed(kana(), k, 12)
                    : Kck.encodeMixed(pool(), k, 12, Set.of(2), Set.of(1, 3, 5), Kck.Tail.values()[r % 3], kana());
            assertEquals(Kck.bitsToHex(k), decodeFirst(e.text));
        }
    }

    @Test public void lookalikeFallbackAndAlways() throws Exception {
        // a kana written as its hiragana/katakana partner: found by the automatic fallback
        byte[] k = null; String text = null;
        for (int r = 0; r < 200 && text == null; r++) {
            k = Kck.randomBits(64);
            Kck.Encoded e = Kck.encodeMixed(pool(), k, 12, Set.of(), Set.of(1), Kck.Tail.KANJI, kana());
            String c = e.tokens.get(0).ch;
            String partner = c.equals("お") ? "オ" : c.equals("オ") ? "お" : c.equals("さ") ? "サ" : c.equals("サ") ? "さ" : null;
            if (partner != null) text = e.text.replaceFirst("\\)" + c, ")" + partner);
        }
        assertNotNull(text);
        assertEquals(Kck.bitsToHex(k), decodeFirst(text));
        assertTrue(summary(), summary().contains("見間違い"));
        ((CheckBox) v(R.id.lookAlways)).performClick();
        assertEquals(Kck.bitsToHex(k), decodeFirst(text));
        assertTrue(summary(), summary().contains("入れ替えた読みも試しました"));
    }

    @Test public void legacyOutputsStillDecode() throws Exception {
        KckVectorsTest.load();
        int n = 0;
        for (Map<String, Object> v : KckVectorsTest.list("decode")) {
            String note = KckVectorsTest.s(v, "note");
            if (!note.startsWith("v1.0") && !note.startsWith("v1.1")) continue;
            if (n++ % 20 != 0) continue;
            @SuppressWarnings("unchecked") List<Map<String, Object>> c = (List<Map<String, Object>>) v.get("candidates");
            assertEquals(note, KckVectorsTest.s(c.get(0), "hex"), decodeFirst(KckVectorsTest.s(v, "in")));
        }
        assertTrue(n > 200);
    }

    // ---------- dark mode ----------
    @Test public void lightByDefaultWithTheLightPalette() {
        assertFalse(a.isNight());
        assertEquals(Kck.COLOR_ARGB, a.palette());
        assertEquals(0xFFFAFAF7, a.getColor(R.color.bg));
    }

    @Config(qualifiers = "+night")
    @Test public void followsTheSystemDarkMode() {
        assertTrue(a.isNight());
        assertEquals(Kck.COLOR_ARGB_DARK, a.palette());
        assertEquals(0xFF15171C, a.getColor(R.color.bg));
        assertEquals(0xFFF1EEE4, a.palette()[0]);                    // 黒 drawn light, as in the icon
        StrokeView sv = findStrokeView(v(R.id.cards));
        assertNotNull(sv);
        for (int i = 0; i < sv.strokeCount(); i++) {
            int c = sv.strokeColor(i);
            boolean known = false;
            for (int p : Kck.COLOR_ARGB_DARK) known |= p == c;
            assertTrue(known);
            assertNotEquals(0xFF111111, c);
        }
    }

    @Config(qualifiers = "+night")
    @Test public void lightThemeSettingOverridesTheSystem() {
        new Settings(a).setTheme(Settings.THEME_LIGHT);
        ctl.pause().stop().destroy();
        start();
        assertFalse(a.isNight());
        assertEquals(0xFFFAFAF7, a.getColor(R.color.bg));
    }

    @Test public void darkThemeSettingOverridesTheSystem() {
        new Settings(a).setTheme(Settings.THEME_DARK);
        ctl.pause().stop().destroy();
        start();
        assertTrue(a.isNight());
        assertEquals(Kck.COLOR_ARGB_DARK, a.palette());
        assertEquals(0xFF15171C, a.getColor(R.color.bg));
    }

    @Test public void themeChangeKeepsKeyAndInputs() {
        ((EditText) v(R.id.hex)).setText(WebModel.DEMO_HEX);
        click(R.id.bHex);
        tap(2);
        ((EditText) v(R.id.dec)).setText("M(8bit)A1 B2");
        String before = text(R.id.keyInfo);
        Map<Integer, Kck.Type> types = a.symbolTypes();
        new Settings(a).setTheme(Settings.THEME_DARK);
        MainActivity.Carry.pending = a.carryState();
        ctl.recreate();
        a = ctl.get();
        ShadowLooper.idleMainLooper();
        assertTrue(a.isNight());
        assertEquals(before, text(R.id.keyInfo));
        assertEquals(types, a.symbolTypes());
        assertEquals(WebModel.DEMO_HEX, text(R.id.hex));
        assertEquals("M(8bit)A1 B2", text(R.id.dec));
        assertEquals(null, MainActivity.Carry.pending);
    }

    @Test public void themeRadioInTheSettings() {
        click(R.id.bSettings);
        List<RadioButton> radios = new ArrayList<>();
        collect(a.shownDialog().getWindow().getDecorView(), RadioButton.class, radios);
        radios.get(2).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(Settings.THEME_DARK, new Settings(a).theme());
        MainActivity.Carry.pending = null;
    }

    static StrokeView findStrokeView(View v) {
        if (v instanceof StrokeView) return (StrokeView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            StrokeView s = findStrokeView(((ViewGroup) v).getChildAt(i));
            if (s != null) return s;
        }
        return null;
    }

    @Test public void explainOpens() {
        assertEquals(View.GONE, v(R.id.explain).getVisibility());
        click(R.id.explainToggle);
        assertEquals(View.VISIBLE, v(R.id.explain).getVisibility());
        assertTrue(text(R.id.explain).contains("Grok Bot"));
        assertTrue(text(R.id.footer).contains("KanjiVG"));
        assertTrue(text(R.id.footer).contains("アイコンの絵はプロジェクトのオーナーが提供"));
        assertTrue(text(R.id.explain).contains("1色かな"));
        assertTrue(text(R.id.version).contains("v1.2.0"));
    }

    // ---------- screenshots ----------
    static final int W = 1233, H = 2673;

    Bitmap shot() {
        View d = a.getWindow().getDecorView();
        d.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        d.layout(0, 0, W, H);
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(a.getColor(R.color.bar));
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

    @Test public void screenshots() throws Exception { screens(""); }

    @Config(qualifiers = "+night")
    @Test public void screenshotsDark() throws Exception { screens("dark-"); }

    void screens(String p) throws Exception {
        // fixed key so the pictures are stable
        ((EditText) v(R.id.hex)).setText(WebModel.DEMO_HEX);
        click(R.id.bHex);
        ((EditText) v(R.id.hex)).setText("");
        shot(); save(shot(), p + "01-top.png");
        scrollToView(R.id.seqBox, 20); save(shot(), p + "02-encode.png");
        tap(3); tap(5); tap(5);                                   // 3: alpha, 5: kana
        shot(); scrollToView(R.id.seqBox, 20); save(shot(), p + "03-encode-alpha-kana.png");
        click(R.id.bAllKana);
        shot(); scrollToView(R.id.seqBox, 20); save(shot(), p + "04-all-kana.png");
        ((CheckBox) v(R.id.tFixed)).performClick(); ShadowLooper.idleMainLooper();
        shot(); scrollToView(R.id.seqBox, 20); save(shot(), p + "05-kana-fixed.png");
        ((CheckBox) v(R.id.tFixed)).performClick();
        click(R.id.bDemo);
        LinearLayout out = v(R.id.decOut);
        waitFor(() -> out.getChildCount() > 1);
        shot(); scrollToView(R.id.dec, 400); save(shot(), p + "06-demo-decode.png");
        click(R.id.explainToggle);
        shot(); scrollToView(R.id.explainToggle, 20); save(shot(), p + "07-explain.png");
        click(R.id.bSettings);
        AlertDialog dlg = a.shownDialog();
        assertNotNull(dlg);
        save(withDialog(dlg), p + "08-settings.png");
        dlg.dismiss();
        click(R.id.bLicenses);
        save(withDialog(a.shownDialog()), p + "09-licenses.png");
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
