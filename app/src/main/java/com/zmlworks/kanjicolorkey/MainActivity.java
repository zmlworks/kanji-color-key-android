package com.zmlworks.kanjicolorkey;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The web app's single page (web/app.html) as native views: key (random / hex / binary / passphrase, data bits,
 * check bits), the mixed-mode encode result with tappable symbols and the two endings, decode with ? () [],
 * the AI explanation and the demo. All encoding/decoding is done by Kck (the Java port of src/kck.js).
 */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService exec;
    private Settings settings;
    private Kck.Pool pool;
    private PoolData poolData;

    // state (memory only)
    private byte[] key;             // 512 random bits; the slider takes a prefix
    private byte[] custom;          // bits from hex / binary / passphrase, or null
    private Set<Integer> alphaPos = new TreeSet<>();
    private boolean bitsTouched, suppress, wiped;
    private volatile int gen;       // bumps on wipe: late background results are dropped
    private AlertDialog dialog;

    // views
    private SeekBar bits;
    private TextView bitsV, keyInfo, info, err, explain, explainToggle;
    private EditText chk, hex, bin, pass, salt, dec;
    private FlowLayout cards;
    private LinearLayout variants, decOut;
    private Button bDec, bPass;
    private ScrollView scroll;
    WebModel.Result last;           // the rendered result (tests)

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(null); // never restore anything: no key material in saved state
        settings = new Settings(this);
        applyWindowFlags();
        setContentView(R.layout.activity_main);
        getWindow().getDecorView().setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        exec = Executors.newSingleThreadExecutor();
        bindViews();
        insets();
        poolData = PoolStore.cached();
        if (poolData != null) ready();
        else {
            keyInfo.setText(R.string.loading);
            exec.execute(() -> {
                PoolData d = PoolStore.get(this);
                main.post(() -> { if (!isDestroyed()) { poolData = d; ready(); } });
            });
        }
    }

    private void ready() {
        pool = poolData.pool;
        key = Kck.randomBits(512);
        render();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        // intentionally empty: the key, inputs and results are never put into the saved state
    }

    @Override protected void onStart() {
        super.onStart();
        if (wiped && pool != null) {
            wiped = false;
            key = Kck.randomBits(512);
            render();
            err.setText(R.string.wiped);
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && settings.wipeOnBackground()) wipe();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        gen++;
        exec.shutdownNow();
        if (key != null) Arrays.fill(key, (byte) 0);
    }

    /** Clears the key, every input and every result (used when the app goes to the background). */
    void wipe() {
        gen++;
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        for (EditText e : new EditText[]{hex, bin, pass, salt, dec}) e.getText().clear();
        if (key != null) Arrays.fill(key, (byte) 0);
        if (custom != null) Arrays.fill(custom, (byte) 0);
        key = null; custom = null; last = null;
        alphaPos = new TreeSet<>();
        keyInfo.setText(""); info.setText(""); err.setText("");
        cards.removeAllViews(); variants.removeAllViews(); decOut.removeAllViews();
        bDec.setEnabled(true); bPass.setEnabled(true);
        wiped = true;
    }

    boolean isWiped() { return wiped; }

    // ---------- window / security ----------
    void applyWindowFlags() {
        if (settings.allowScreenshots()) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(settings.allowScreenshots());
        if (settings.keepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void insets() {
        View root = findViewById(R.id.root);
        if (Build.VERSION.SDK_INT >= 35) { // edge-to-edge is enforced for targetSdk 35 on Android 15
            root.setOnApplyWindowInsetsListener((v, in) -> {
                Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = in.getInsets(WindowInsets.Type.ime());
                v.setPadding(s.left, s.top, s.right, Math.max(s.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        }
    }

    // ---------- views ----------
    private void bindViews() {
        scroll = findViewById(R.id.scroll);
        bits = findViewById(R.id.bits);
        bitsV = findViewById(R.id.bitsV);
        keyInfo = findViewById(R.id.keyInfo);
        info = findViewById(R.id.info);
        err = findViewById(R.id.err);
        chk = findViewById(R.id.chk);
        hex = findViewById(R.id.hex);
        bin = findViewById(R.id.bin);
        pass = findViewById(R.id.pass);
        salt = findViewById(R.id.salt);
        dec = findViewById(R.id.dec);
        cards = findViewById(R.id.cards);
        variants = findViewById(R.id.variants);
        decOut = findViewById(R.id.decOut);
        bDec = findViewById(R.id.bDec);
        bPass = findViewById(R.id.bPass);
        explain = findViewById(R.id.explain);
        explainToggle = findViewById(R.id.explainToggle);

        ((TextView) findViewById(R.id.intro)).setText(html(R.string.intro));
        ((TextView) findViewById(R.id.legend)).setText(html(R.string.legend));
        ((TextView) findViewById(R.id.decHint)).setText(html(R.string.decode_hint));
        explain.setText(html(R.string.explain));
        TextView footer = findViewById(R.id.footer);
        footer.setText(html(R.string.footer));
        footer.setMovementMethod(LinkMovementMethod.getInstance());
        ((TextView) findViewById(R.id.version)).setText(getString(R.string.version_line, versionName()));
        bitsV.setText(String.valueOf(sliderBits()));
        for (EditText e : new EditText[]{hex, bin, dec, salt}) noCopy(e);

        findViewById(R.id.bRand).setOnClickListener(v -> {
            if (pool == null) return;
            key = Kck.randomBits(512); custom = null;
            if (!bitsTouched) setSlider(256);
            alphaPos = new TreeSet<>();
            render();
        });
        bits.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (suppress) return;
                bitsTouched = true; custom = null; render();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        chk.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (suppress) return;
                int v = checkBits();
                if (!s.toString().isEmpty() && !String.valueOf(v).equals(s.toString())) { setCheck(v); return; }
                render();
            }
        });
        findViewById(R.id.chkMinus).setOnClickListener(v -> { setCheck(Math.max(0, checkBits() - 1)); render(); });
        findViewById(R.id.chkPlus).setOnClickListener(v -> { setCheck(Math.min(64, checkBits() + 1)); render(); });
        findViewById(R.id.bHex).setOnClickListener(v -> {
            if (pool == null) return;
            try { useBits(Kck.hexToBits(hex.getText().toString())); } catch (Kck.KckException e) { err.setText(e.getMessage()); }
        });
        findViewById(R.id.bBin).setOnClickListener(v -> {
            if (pool == null) return;
            try { useBits(Kck.parseBitString(bin.getText().toString())); } catch (Kck.KckException e) { err.setText(e.getMessage()); }
        });
        bPass.setOnClickListener(v -> derive());
        findViewById(R.id.bCopyKey).setOnClickListener(v -> { if (pool != null && keyBitsOrNull() != null) copy("key", Kck.bitsToHex(keyBits())); });
        findViewById(R.id.bAllK).setOnClickListener(v -> { alphaPos = new TreeSet<>(); render(); });
        findViewById(R.id.bAllA).setOnClickListener(v -> { alphaPos = WebModel.allAlpha(); render(); });
        bDec.setOnClickListener(v -> decode(false));
        findViewById(R.id.bDemo).setOnClickListener(v -> demo());
        findViewById(R.id.bSettings).setOnClickListener(v -> showSettings());
        findViewById(R.id.bLicenses).setOnClickListener(v -> showLicenses());
        explainToggle.setOnClickListener(v -> {
            boolean open = explain.getVisibility() != View.VISIBLE;
            explain.setVisibility(open ? View.VISIBLE : View.GONE);
            explainToggle.setText(open ? R.string.explain_open : R.string.explain_closed);
        });
    }

    /** No copy/cut from the text fields (it would bypass the sensitive, auto-cleared clipboard); paste still works. */
    private static void noCopy(EditText e) {
        e.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode m, Menu menu) { return true; }
            @Override public boolean onPrepareActionMode(ActionMode m, Menu menu) {
                menu.removeItem(android.R.id.copy); menu.removeItem(android.R.id.cut); menu.removeItem(android.R.id.shareText);
                return true;
            }
            @Override public boolean onActionItemClicked(ActionMode m, MenuItem item) { return false; }
            @Override public void onDestroyActionMode(ActionMode m) { }
        });
    }

    private Spanned html(int res) { return Html.fromHtml(getString(res), Html.FROM_HTML_MODE_COMPACT); }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    // ---------- key ----------
    int sliderBits() { return 64 + 8 * bits.getProgress(); }

    private void setSlider(int nBits) {
        suppress = true;
        bits.setProgress((nBits - 64) / 8);
        suppress = false;
    }

    int checkBits() {
        String s = chk.getText().toString().trim();
        if (s.isEmpty()) return 0;
        try { return Math.max(0, Math.min(64, Integer.parseInt(s))); } catch (NumberFormatException e) { return 64; }
    }

    private void setCheck(int v) {
        suppress = true;
        chk.setText(String.valueOf(v));
        chk.setSelection(chk.getText().length());
        suppress = false;
    }

    private byte[] keyBitsOrNull() { return custom != null ? custom : key; }

    byte[] keyBits() { return custom != null ? custom : Arrays.copyOf(key, sliderBits()); }

    private void useBits(byte[] b) { custom = b; alphaPos = new TreeSet<>(); render(); }

    private void derive() {
        if (pool == null) return;
        final String p = pass.getText().toString(), s = salt.getText().toString();
        final int g = gen;
        keyInfo.setText(R.string.deriving);
        bPass.setEnabled(false);
        exec.execute(() -> {
            byte[] k = Kck.deriveKey(p, s, Kck.KDF_ITERATIONS);
            main.post(() -> {
                if (g != gen || isDestroyed()) { Arrays.fill(k, (byte) 0); return; }
                bPass.setEnabled(true);
                byte[] b = Kck.bytesToBits(k);
                Arrays.fill(k, (byte) 0);
                hex.setText(Kck.bitsToHex(b));
                useBits(b);
            });
        });
    }

    // ---------- encode ----------
    void render() {
        if (pool == null || (key == null && custom == null)) return;
        err.setText("");
        try {
            byte[] b = keyBits();
            bitsV.setText(custom != null ? getString(R.string.bits_custom, b.length) : String.valueOf(sliderBits()));
            keyInfo.setText(WebModel.keyInfo(b));
            WebModel.Result r = WebModel.render(pool, b, checkBits(), alphaPos);
            last = r;
            info.setText(WebModel.head(r));
            cards.removeAllViews();
            for (int i = 0; i < r.common; i++) cards.addView(card(r.a.tokens.get(i), i + 1, true));
            variants.removeAllViews();
            if (r.hasVariants()) {
                variants.addView(variant(getString(R.string.tail_kanji), r.a, r.common));
                variants.addView(variant(getString(R.string.tail_alpha), r.b, r.common));
            } else {
                variants.addView(mono(r.a.text, 16));
                variants.addView(copyButton(R.string.copy_text, "text", r.a.text));
            }
        } catch (Kck.KckException e) { err.setText(e.getMessage()); }
    }

    private View variant(String title, Kck.Encoded e, int common) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.variant_bg);
        box.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.setLayoutParams(lp);
        TextView t = new TextView(this);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(title, new StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("  ");
        int st = sb.length();
        sb.append(WebModel.info(e));
        sb.setSpan(new ForegroundColorSpan(0xFF666666), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(13, true), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(sb);
        t.setTextColor(0xFF222222);
        box.addView(t);
        FlowLayout f = new FlowLayout(this);
        f.setPadding(0, dp(6), 0, dp(6));
        for (int i = common; i < e.tokens.size(); i++) f.addView(card(e.tokens.get(i), i + 1, false));
        box.addView(f);
        box.addView(mono(e.text, 15));
        box.addView(copyButton(R.string.copy_text, "text", e.text));
        return box;
    }

    private TextView mono(String s, int sp) {
        TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(sp);
        t.setTextColor(0xFF222222);
        t.setText(s);
        t.setPadding(0, dp(4), 0, dp(2));
        return t;
    }

    private Button copyButton(int label, String clipLabel, String text) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> copy(clipLabel, text));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private void copy(String label, String text) {
        SecureClip.copy(this, label, text);
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    /** A symbol card: "#pos · N画", the colored kanji (or glyph), and the color names. */
    View card(Kck.Token t, int pos, boolean clickable) {
        boolean kanji = t.type == Kck.Type.KANJI;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(4), dp(3), dp(4), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(2), t.type == Kck.Type.ALPHA ? 0xFFA5B4FC : 0xFFDDDDDD);
        if (clickable) {
            c.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x66F59E0B), bg, null));
            c.setOnClickListener(v -> { alphaPos = WebModel.toggle(alphaPos, pos); render(); });
        } else c.setBackground(bg);
        int box = dp(84);

        TextView head = new TextView(this);
        SpannableStringBuilder hs = new SpannableStringBuilder("#" + pos);
        hs.setSpan(new ForegroundColorSpan(0xFF999999), 0, hs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        hs.setSpan(new AbsoluteSizeSpan(10, true), 0, hs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (kanji) {
            int st = hs.length();
            hs.append(" · ").append(t.colors.length + "画");
            hs.setSpan(new ForegroundColorSpan(0xFF555555), st, hs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            hs.setSpan(new AbsoluteSizeSpan(11, true), st, hs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        head.setText(hs);
        c.addView(head);

        if (kanji) {
            StrokeView sv = new StrokeView(this, poolData.strokes[t.index], t.colors);
            c.addView(sv, new LinearLayout.LayoutParams(box, box));
        } else {
            TextView g = new TextView(this);
            g.setText(t.ch);
            g.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            g.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 50);
            g.setTextColor(Kck.COLOR_ARGB[t.color()]);
            g.setGravity(Gravity.CENTER);
            g.setIncludeFontPadding(false);
            c.addView(g, new LinearLayout.LayoutParams(dp(60), box));
        }

        TextView names = new TextView(this);
        SpannableStringBuilder ns = new SpannableStringBuilder();
        for (int col : t.colors) {
            int st = ns.length();
            ns.append(Kck.COLORS[col]);
            ns.setSpan(new ForegroundColorSpan(Kck.COLOR_ARGB[col]), st, ns.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        names.setText(ns);
        names.setTypeface(Typeface.DEFAULT_BOLD);
        names.setTextSize(13);
        names.setLetterSpacing(0.06f);
        names.setGravity(Gravity.CENTER_HORIZONTAL);
        names.setMaxWidth(kanji ? box : dp(60));
        c.addView(names);

        StringBuilder cd = new StringBuilder("#" + pos + " " + t.ch + (kanji ? " " + t.colors.length + "画 " : " "));
        for (int col : t.colors) cd.append(Kck.COLORS[col]);
        c.setContentDescription(cd);
        return c;
    }

    // ---------- decode ----------
    void decode(boolean scrollToResult) {
        if (pool == null) return;
        final String in = dec.getText().toString();
        final int g = gen;
        decOut.removeAllViews();
        TextView wait = mono(getString(R.string.decoding), 14);
        decOut.addView(wait);
        bDec.setEnabled(false);
        exec.execute(() -> {
            Kck.Decoded d = null; String error = null;
            try { d = Kck.decodeAny(pool, in, () -> g != gen); }
            catch (RuntimeException e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
            final Kck.Decoded fd = d; final String fe = error;
            main.post(() -> {
                if (g != gen || isDestroyed()) return;
                bDec.setEnabled(true);
                showDecoded(fd, fe);
                if (scrollToResult) scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, bottomOf(decOut) - scroll.getHeight() / 2)));
            });
        });
    }

    private int bottomOf(View v) {
        int y = v.getHeight();
        for (View p = v; p != null && p != scroll; p = (View) p.getParent()) y += p.getTop();
        return y;
    }

    private void showDecoded(Kck.Decoded d, String error) {
        decOut.removeAllViews();
        if (error != null) {
            TextView t = mono(error, 14);
            t.setTextColor(0xFFBB0000);
            decOut.addView(t);
            return;
        }
        decOut.addView(mono(WebModel.decodeSummary(d), 14));
        int n = Math.min(WebModel.MAX_SHOWN, d.candidates.size());
        for (int i = 0; i < n; i++) {
            Kck.Candidate c = d.candidates.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, 0);
            row.addView(mono(c.hex, 14));
            TextView small = mono(c.text, 12);
            small.setTextColor(0xFF666666);
            row.addView(small);
            row.addView(copyButton(R.string.copy_hex, "key", c.hex));
            decOut.addView(row);
        }
    }

    // ---------- demo (#demo) ----------
    void demo() {
        if (pool == null) return;
        WebModel.Demo dm = WebModel.demo(pool);
        key = dm.key; custom = null;
        setSlider(256); bitsTouched = false;
        setCheck(0);
        alphaPos = new TreeSet<>(dm.alphaPos);
        render();
        dec.setText(dm.decodeInput);
        decode(true);
    }

    // ---------- dialogs ----------
    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        int[] labels = {R.string.set_screenshots, R.string.set_wipe, R.string.set_keep_screen};
        boolean[] on = {settings.allowScreenshots(), settings.wipeOnBackground(), settings.keepScreenOn()};
        for (int i = 0; i < 3; i++) {
            final int which = i;
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(labels[i]);
            cb.setChecked(on[i]);
            cb.setTextSize(15);
            cb.setPadding(dp(8), dp(10), 0, dp(10));
            cb.setOnCheckedChangeListener((b, checked) -> {
                if (which == 0) settings.setAllowScreenshots(checked);
                else if (which == 1) settings.setWipeOnBackground(checked);
                else settings.setKeepScreenOn(checked);
                applyWindowFlags();
            });
            box.addView(cb);
        }
        TextView note = new TextView(this);
        note.setText(R.string.set_note);
        note.setTextSize(13);
        note.setTextColor(0xFF666666);
        note.setPadding(0, dp(8), 0, dp(4));
        box.addView(note);
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(box)
                .setPositiveButton(R.string.close, null)
                .create();
        dialog.show();
    }

    private void showLicenses() {
        String text;
        try (InputStream in = getAssets().open("THIRD_PARTY_NOTICES.txt")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
            text = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { text = e.toString(); }
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(0xFF222222);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        dialog = new AlertDialog.Builder(this).setTitle(R.string.licenses).setView(sv).setPositiveButton(R.string.close, null).create();
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
    }

    // ---------- test hooks ----------
    Set<Integer> alphaPositions() { return alphaPos; }
    AlertDialog shownDialog() { return dialog; }
}
