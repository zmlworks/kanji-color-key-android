package com.zmlworks.kanjicolorkey;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zmlworks.kanjicolorkey.keepass.KeePassFacade;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Paper restore: type characters → KanjiVG stroke-order sample → flick colors (4-way + center=?).
 * Optional KeePass composite verification against a chosen .kdbx (slow KDF; cancellable).
 */
public class RestoreActivity extends Activity {
    public static final String EXTRA_KDBX_URI = "kdbx_uri";
    public static final String EXTRA_FROM_KEEPASS = "from_keepass";

    private Settings settings;
    private PoolData poolData;
    private KanaData kanaData;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelDecode = new AtomicBoolean(false);

    private View panelChars, panelStrokes, panelResults;
    private EditText charsIn, dataBits, checkBits;
    private CheckBox cbKf;
    private TextView charsErr, strokeProgress, strokeGuide, colorsSoFar, status, alphaHint;
    private FrameLayout sampleBox;
    private FlickColorView flick;
    private LinearLayout resultsBox;

    private final List<GlyphStrokes> glyphs = new ArrayList<>();
    /** Per glyph: color slots filled with 0..3 or -1 (?). */
    private final List<int[]> colors = new ArrayList<>();
    private int gi, si; // glyph index, stroke/slot index
    private byte[] kdbxBytes;
    private boolean fromKeepass;

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        String th = new Settings(base).theme();
        if (Settings.THEME_SYSTEM.equals(th)) return;
        android.content.res.Configuration c = new android.content.res.Configuration();
        c.fontScale = 0;
        int night = Settings.THEME_DARK.equals(th)
                ? android.content.res.Configuration.UI_MODE_NIGHT_YES
                : android.content.res.Configuration.UI_MODE_NIGHT_NO;
        c.uiMode = (base.getResources().getConfiguration().uiMode
                & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | night;
        applyOverrideConfiguration(c);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        settings = new Settings(this);
        if (settings.blockScreenshots()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (settings.keepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_restore);

        panelChars = findViewById(R.id.panelChars);
        panelStrokes = findViewById(R.id.panelStrokes);
        panelResults = findViewById(R.id.panelResults);
        charsIn = findViewById(R.id.charsIn);
        dataBits = findViewById(R.id.dataBits);
        checkBits = findViewById(R.id.checkBits);
        cbKf = findViewById(R.id.cbKf);
        charsErr = findViewById(R.id.charsErr);
        strokeProgress = findViewById(R.id.strokeProgress);
        strokeGuide = findViewById(R.id.strokeGuide);
        colorsSoFar = findViewById(R.id.colorsSoFar);
        status = findViewById(R.id.status);
        alphaHint = findViewById(R.id.alphaHint);
        sampleBox = findViewById(R.id.sampleBox);
        flick = findViewById(R.id.flick);
        resultsBox = findViewById(R.id.resultsBox);

        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        flick.setPalette(night ? Kck.COLOR_ARGB_DARK : Kck.COLOR_ARGB, Kck.COLORS);

        findViewById(R.id.bBack).setOnClickListener(v -> {
            if (panelStrokes.getVisibility() == View.VISIBLE) showPanel(0);
            else if (panelResults.getVisibility() == View.VISIBLE) showPanel(1);
            else finish();
        });
        findViewById(R.id.bStartStrokes).setOnClickListener(v -> startStrokes());
        findViewById(R.id.bUndo).setOnClickListener(v -> undo());
        findViewById(R.id.bSkipChar).setOnClickListener(v -> backChar());
        flick.setListener(this::onFlickColor);

        fromKeepass = getIntent().getBooleanExtra(EXTRA_FROM_KEEPASS, false);
        String uri = getIntent().getStringExtra(EXTRA_KDBX_URI);
        if (uri != null) loadKdbx(android.net.Uri.parse(uri));

        exec.execute(() -> {
            PoolData p = PoolStore.get(this);
            KanaData k = PoolStore.kana(this);
            main.post(() -> { poolData = p; kanaData = k; });
        });
    }

    private void loadKdbx(android.net.Uri uri) {
        exec.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) return;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
                kdbxBytes = bos.toByteArray();
                main.post(() -> status.setText(R.string.restore_kdbx_loaded));
            } catch (Exception e) {
                main.post(() -> status.setText(getString(R.string.kp_err_generic, e.getMessage())));
            }
        });
    }

    private void showPanel(int which) {
        panelChars.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        panelStrokes.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        panelResults.setVisibility(which == 2 ? View.VISIBLE : View.GONE);
    }

    private void startStrokes() {
        if (poolData == null) { charsErr.setText(R.string.loading); return; }
        String raw = charsIn.getText().toString().replaceAll("\\s+", "");
        if (raw.isEmpty()) { charsErr.setText(R.string.restore_need_chars); return; }
        glyphs.clear(); colors.clear();
        List<String> chars = new ArrayList<>();
        raw.codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
        StringBuilder err = new StringBuilder();
        boolean kf = cbKf.isChecked();
        for (String ch : chars) {
            GlyphStrokes g = GlyphStrokes.resolve(poolData, kanaData, ch, kf);
            if (g.kind == GlyphStrokes.Kind.UNKNOWN) {
                err.append(getString(R.string.restore_unknown_char, ch)).append('\n');
                continue;
            }
            glyphs.add(g);
            int[] c = new int[g.colorSlots];
            Arrays.fill(c, -2); // -2 = not yet entered
            colors.add(c);
        }
        if (!err.toString().isEmpty()) { charsErr.setText(err.toString()); return; }
        if (glyphs.isEmpty()) { charsErr.setText(R.string.restore_need_chars); return; }
        charsErr.setText("");
        gi = 0; si = 0;
        showPanel(1);
        refreshStrokeUi();
    }

    private void refreshStrokeUi() {
        if (gi >= glyphs.size()) { finishColors(); return; }
        GlyphStrokes g = glyphs.get(gi);
        int[] col = colors.get(gi);
        strokeProgress.setText(getString(R.string.restore_progress, gi + 1, glyphs.size(), g.ch, si + 1, g.colorSlots));
        sampleBox.removeAllViews();
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int[] palette = night ? Kck.COLOR_ARGB_DARK : Kck.COLOR_ARGB;

        if (g.kind == GlyphStrokes.Kind.ALPHA) {
            alphaHint.setVisibility(View.VISIBLE);
            alphaHint.setText(getString(R.string.restore_alpha_hint, g.ch));
            TextView big = new TextView(this);
            big.setText(g.ch);
            big.setTextSize(72);
            big.setGravity(Gravity.CENTER);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            int c = col[0] >= 0 ? palette[col[0]] : getColor(R.color.text);
            big.setTextColor(c);
            sampleBox.addView(big, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        } else {
            alphaHint.setVisibility(View.GONE);
            int[] drawColors = new int[g.strokeCount];
            Arrays.fill(drawColors, 0);
            for (int i = 0; i < g.colorSlots && i < col.length; i++) {
                if (col[i] >= 0) drawColors[i] = col[i];
                else if (col[i] == -1) drawColors[i] = 0; // ? shown as 黒 dimmed via highlight
            }
            // KF: remaining strokes black
            for (int i = g.colorSlots; i < g.strokeCount; i++) drawColors[i] = 0;
            StrokeView sv = new StrokeView(this, g.paths, drawColors, palette);
            sv.setHighlightIndex(Math.min(si, g.strokeCount - 1));
            sampleBox.addView(sv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        colorsSoFar.setText(buildPartialSpec());
        // Tap sample to jump stroke (cycle) — set click on sampleBox
        sampleBox.setOnClickListener(v -> {
            // advance highlight only within current glyph for fix: open picker by focusing stroke
            // User requested tap stroke to fix — simple: go to that slot if already colored
            if (g.kind != GlyphStrokes.Kind.ALPHA && g.colorSlots > 1) {
                si = (si + 1) % g.colorSlots;
                refreshStrokeUi();
            }
        });
    }

    private void onFlickColor(int color) {
        if (gi >= glyphs.size()) return;
        GlyphStrokes g = glyphs.get(gi);
        int[] col = colors.get(gi);
        col[si] = color;
        si++;
        if (si >= g.colorSlots) {
            gi++;
            si = 0;
        }
        refreshStrokeUi();
    }

    private void undo() {
        if (gi == 0 && si == 0) return;
        if (si == 0) {
            gi--;
            si = glyphs.get(gi).colorSlots - 1;
        } else si--;
        colors.get(gi)[si] = -2;
        refreshStrokeUi();
    }

    private void backChar() {
        if (gi == 0) { showPanel(0); return; }
        // reset current glyph colors and go to previous
        if (si > 0 || colors.get(gi)[0] != -2) {
            Arrays.fill(colors.get(gi), -2);
            si = 0;
        } else {
            gi--;
            Arrays.fill(colors.get(gi), -2);
            si = 0;
        }
        refreshStrokeUi();
    }

    private String buildPartialSpec() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glyphs.size(); i++) {
            GlyphStrokes g = glyphs.get(i);
            sb.append(g.ch);
            int[] col = colors.get(i);
            for (int c : col) {
                if (c == -2) sb.append('_');
                else if (c < 0) sb.append('?');
                else sb.append(c);
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    private String buildDecodeText() {
        int db = parseInt(dataBits.getText().toString(), 256);
        int cb = parseInt(checkBits.getText().toString(), 0);
        boolean allAlpha = true, allKana = true;
        for (GlyphStrokes g : glyphs) {
            if (g.kind != GlyphStrokes.Kind.ALPHA) allAlpha = false;
            if (g.kind != GlyphStrokes.Kind.KANA) allKana = false;
        }
        StringBuilder body = new StringBuilder();
        boolean kf = allKana && cbKf.isChecked();
        for (int i = 0; i < glyphs.size(); i++) {
            GlyphStrokes g = glyphs.get(i);
            if (kf && i > 0) body.append(' ');
            body.append(g.ch);
            for (int c : colors.get(i)) body.append(c < 0 ? '?' : (char) ('0' + c));
            // KF text form includes every SVG stroke; colors beyond the 3 encoded slots are black (0).
            if (kf) {
                for (int pad = g.colorSlots; pad < g.strokeCount; pad++) body.append('0');
            }
        }
        if (allAlpha) return "A(" + db + (cb != 0 ? "+" + cb : "") + "BIT)" + body;
        if (kf) return "KF(" + db + (cb != 0 ? "+" + cb : "") + "bit)" + body;
        return "M(" + db + (cb != 0 ? "+" + cb : "") + "bit)" + body;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private void finishColors() {
        showPanel(2);
        resultsBox.removeAllViews();
        final String text = buildDecodeText();
        TextView spec = new TextView(this);
        spec.setTypeface(Typeface.MONOSPACE);
        spec.setTextColor(getColor(R.color.text));
        spec.setText(text);
        spec.setTextIsSelectable(true);
        resultsBox.addView(spec);

        int unknowns = 0;
        for (int[] col : colors) for (int c : col) if (c < 0) unknowns++;
        // Cap: 4^8 = 65536 color expansions; beyond that decodeAny will refuse via EXPAND_LIMIT anyway.
        final int MAX_UNKNOWN = 8;
        long combos = 1;
        for (int i = 0; i < unknowns; i++) {
            if (combos > Long.MAX_VALUE / 4) { combos = Long.MAX_VALUE; break; }
            combos *= 4;
        }
        TextView info = new TextView(this);
        info.setTextColor(getColor(R.color.dim));
        info.setText(getString(R.string.restore_combos, unknowns, combos));
        resultsBox.addView(info);
        boolean tooMany = unknowns > MAX_UNKNOWN;
        if (tooMany) {
            TextView warn = new TextView(this);
            warn.setTextColor(getColor(R.color.err));
            warn.setText(getString(R.string.restore_too_many_unknowns, MAX_UNKNOWN));
            resultsBox.addView(warn);
        }

        Button go = new Button(this);
        go.setAllCaps(false);
        go.setText(R.string.restore_decode);
        go.setEnabled(!tooMany);
        go.setOnClickListener(v -> runDecode(text));
        resultsBox.addView(go);

        if (kdbxBytes != null) {
            Button both = new Button(this);
            both.setAllCaps(false);
            both.setText(R.string.restore_decode_kdbx);
            both.setEnabled(!tooMany);
            both.setOnClickListener(v -> runDecodeWithKdbx(text));
            resultsBox.addView(both);
            TextView est = new TextView(this);
            est.setTextColor(getColor(R.color.dim));
            est.setText(R.string.restore_kdbx_estimate);
            resultsBox.addView(est);
        }
        status.setText("");
    }

    private void runDecode(String text) {
        cancelDecode.set(false);
        status.setText(R.string.decoding);
        resultsBox.removeAllViews();
        TextView wait = new TextView(this);
        wait.setText(R.string.decoding);
        wait.setTextColor(getColor(R.color.dim));
        resultsBox.addView(wait);
        final Kck.Options opts = new Kck.Options(kanaData.set);
        exec.execute(() -> {
            try {
                Kck.Decoded d = Kck.decodeAny(poolData.pool, text, opts, cancelDecode::get);
                main.post(() -> showCandidates(d, null));
            } catch (Exception e) {
                main.post(() -> {
                    status.setText(e.getMessage());
                    resultsBox.removeAllViews();
                });
            }
        });
    }

    private void runDecodeWithKdbx(String text) {
        cancelDecode.set(false);
        status.setText(R.string.restore_kdbx_trying);
        resultsBox.removeAllViews();
        Button cancel = new Button(this);
        cancel.setAllCaps(false);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(v -> cancelDecode.set(true));
        resultsBox.addView(cancel);
        TextView prog = new TextView(this);
        prog.setTextColor(getColor(R.color.dim));
        resultsBox.addView(prog);
        final Kck.Options opts = new Kck.Options(kanaData.set);
        final byte[] db = kdbxBytes;
        exec.execute(() -> {
            try {
                Kck.Decoded d = Kck.decodeAny(poolData.pool, text, opts, cancelDecode::get);
                List<Kck.Candidate> ok = new ArrayList<>();
                int i = 0;
                long t0 = System.currentTimeMillis();
                for (Kck.Candidate c : d.candidates) {
                    if (cancelDecode.get()) break;
                    i++;
                    byte[] bits = c.bits;
                    if (bits.length < 256) continue;
                    byte[] keyBits = Arrays.copyOf(bits, 256);
                    byte[] composite = Kck.bitsToBytes(keyBits);
                    try {
                        KeePassFacade.openBytes(db, KeePassFacade.credentialsFromComposite(composite));
                        ok.add(c);
                    } catch (Exception ignored) {
                    } finally {
                        Arrays.fill(composite, (byte) 0);
                        Arrays.fill(keyBits, (byte) 0);
                    }
                    int done = i;
                    long elapsed = System.currentTimeMillis() - t0;
                    main.post(() -> prog.setText(getString(R.string.restore_kdbx_progress, done, d.candidates.size(), ok.size(), elapsed / 1000.0)));
                }
                Kck.Decoded filtered = new Kck.Decoded(ok, d.tried, d.total, d.dataBits, d.checkBits, d.lookalike, d.fixed);
                main.post(() -> showCandidates(filtered, ok.isEmpty() ? getString(R.string.restore_kdbx_none) : null));
            } catch (Exception e) {
                main.post(() -> status.setText(e.getMessage()));
            }
        });
    }

    private void showCandidates(Kck.Decoded d, String note) {
        resultsBox.removeAllViews();
        status.setText(note != null ? note : WebModel.decodeSummary(d));
        int n = Math.min(50, d.candidates.size());
        for (int i = 0; i < n; i++) {
            Kck.Candidate c = d.candidates.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, 12, 0, 0);
            TextView hex = new TextView(this);
            hex.setTypeface(Typeface.MONOSPACE);
            hex.setTextColor(getColor(R.color.text));
            hex.setText(c.hex);
            row.addView(hex);
            TextView t = new TextView(this);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(12);
            t.setTextColor(getColor(R.color.dim));
            t.setText(c.text);
            row.addView(t);
            LinearLayout btns = new LinearLayout(this);
            Button copy = new Button(this);
            copy.setAllCaps(false);
            copy.setText(R.string.copy_hex);
            copy.setOnClickListener(v -> {
                SecureClip.copy(this, "key", c.hex, settings.clipClear());
                Toast.makeText(this, settings.clipClear() ? R.string.copied_clear : R.string.copied, Toast.LENGTH_SHORT).show();
            });
            btns.addView(copy);
            Button show = new Button(this);
            show.setAllCaps(false);
            show.setText(R.string.kp_show_as_kanji);
            show.setOnClickListener(v -> {
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra(MainActivity.EXTRA_KEY_HEX, c.hex.length() >= 64 ? c.hex.substring(0, 64) : c.hex);
                intent.putExtra(MainActivity.EXTRA_KEY_LOCKED, fromKeepass || kdbxBytes != null);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            });
            btns.addView(show);
            if (fromKeepass || kdbxBytes != null) {
                Button openKp = new Button(this);
                openKp.setAllCaps(false);
                openKp.setText(R.string.restore_open_keepass);
                openKp.setOnClickListener(v -> {
                    Intent intent = new Intent(this, KeePassActivity.class);
                    intent.putExtra(KeePassActivity.EXTRA_COMPOSITE_HEX,
                            c.hex.length() >= 64 ? c.hex.substring(0, 64) : c.hex);
                    startActivity(intent);
                });
                btns.addView(openKp);
            }
            row.addView(btns);
            resultsBox.addView(row);
        }
        if (d.candidates.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.restore_no_candidates);
            empty.setTextColor(getColor(R.color.err));
            resultsBox.addView(empty);
        }
    }

    @Override protected void onDestroy() {
        cancelDecode.set(true);
        exec.shutdownNow();
        if (kdbxBytes != null) Arrays.fill(kdbxBytes, (byte) 0);
        super.onDestroy();
    }
}
