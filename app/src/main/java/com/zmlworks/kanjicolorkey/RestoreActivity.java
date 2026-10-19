package com.zmlworks.kanjicolorkey;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.zmlworks.kanjicolorkey.keepass.KeePassFacade;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Paper restore v2: vertical character blocks (char → KanjiVG sample → flick → color-name list).
 * Draft persisted in {@link RamKeyStore} (RAM only).
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

    private LinearLayout blockList, resultsBox;
    private View blockScroll, panelResults;
    private EditText dataBits, checkBits;
    private CheckBox cbKf;
    private RadioGroup mismatchDepth;
    private TextView status;
    private Button bFinish;

    private final List<BlockHolder> blocks = new ArrayList<>();
    private byte[] kdbxBytes;
    private boolean fromKeepass;
    private boolean suppress;

    static final class BlockHolder {
        View root;
        EditText charIn;
        TextView blockErr, colorNames;
        FrameLayout sampleBox;
        View sampleRow;
        FlickColorView flick;
        Button bDelete;
        GlyphStrokes glyph;
        int[] colors = new int[0]; // 0..3 or -1; -2 = empty
        int si;
        TextWatcher watcher;
    }

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
        super.onCreate(null); // never restore secrets from saved state
        setContentView(R.layout.activity_restore);
        RamStoreUi.bind(this);

        blockList = findViewById(R.id.blockList);
        blockScroll = findViewById(R.id.blockScroll);
        panelResults = findViewById(R.id.panelResults);
        resultsBox = findViewById(R.id.resultsBox);
        dataBits = findViewById(R.id.dataBits);
        checkBits = findViewById(R.id.checkBits);
        cbKf = findViewById(R.id.cbKf);
        mismatchDepth = findViewById(R.id.mismatchDepth);
        status = findViewById(R.id.status);
        bFinish = findViewById(R.id.bFinish);

        findViewById(R.id.bBack).setOnClickListener(v -> {
            if (panelResults.getVisibility() == View.VISIBLE) {
                showResults(false);
                return;
            }
            saveDraft();
            finish();
        });
        findViewById(R.id.bAddChar).setOnClickListener(v -> addBlock(""));
        bFinish.setOnClickListener(v -> finishAndDecode());

        cbKf.setOnCheckedChangeListener((b, on) -> {
            if (suppress) return;
            for (BlockHolder h : blocks) onCharChanged(h, false);
            updateFinishEnabled();
            saveDraft();
        });

        fromKeepass = getIntent().getBooleanExtra(EXTRA_FROM_KEEPASS, false);
        kdbxBytes = RamKeyStore.get().peekKdbx();
        if (kdbxBytes != null) status.setText(R.string.restore_kdbx_loaded);
        String uri = getIntent().getStringExtra(EXTRA_KDBX_URI);
        if (kdbxBytes == null && uri != null) {
            exec.execute(() -> {
                try {
                    java.io.InputStream in = getContentResolver().openInputStream(android.net.Uri.parse(uri));
                    if (in == null) return;
                    byte[] all = in.readAllBytes();
                    in.close();
                    main.post(() -> {
                        kdbxBytes = all;
                        RamKeyStore.get().setKdbx(all, null, android.net.Uri.parse(uri));
                        status.setText(R.string.restore_kdbx_loaded);
                        RamStoreUi.refresh(this);
                    });
                } catch (Exception e) {
                    main.post(() -> status.setText(getString(R.string.kp_err_unreadable, e.getMessage())));
                }
            });
        }
        if (fromKeepass || kdbxBytes != null) {
            if ("0".equals(checkBits.getText().toString().trim())) checkBits.setText("16");
        }

        exec.execute(() -> {
            PoolData p = PoolStore.get(this);
            KanaData k = PoolStore.kana(this);
            main.post(() -> {
                poolData = p;
                kanaData = k;
                restoreDraftOrDefault();
            });
        });
    }

    @Override protected void onPause() {
        saveDraft();
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && settings.wipeOnBackground()) {
            RamKeyStore.get().clear();
        }
    }

    private void restoreDraftOrDefault() {
        List<RamKeyStore.RestoreBlock> draft = RamKeyStore.get().peekRestoreDraft();
        suppress = true;
        if (RamKeyStore.get().hasRestoreDraft()) {
            dataBits.setText(String.valueOf(RamKeyStore.get().restoreDataBits()));
            checkBits.setText(String.valueOf(RamKeyStore.get().restoreCheckBits()));
            cbKf.setChecked(RamKeyStore.get().restoreKf());
            int d = RamKeyStore.get().restoreMismatchDepth();
            if (d == 1) mismatchDepth.check(R.id.mismatch1);
            else if (d == 2) mismatchDepth.check(R.id.mismatch2);
            else mismatchDepth.check(R.id.mismatch0);
        }
        suppress = false;
        blockList.removeAllViews();
        blocks.clear();
        if (draft.isEmpty()) addBlock("");
        else for (RamKeyStore.RestoreBlock b : draft) {
            BlockHolder h = addBlock(b.ch);
            if (b.colors != null && b.colors.length > 0 && h.glyph != null
                    && b.colors.length == h.glyph.colorSlots) {
                h.colors = b.colors.clone();
                h.si = firstEmpty(h.colors);
                refreshSample(h);
                refreshColorNames(h);
            }
        }
        updateFinishEnabled();
        RamStoreUi.refresh(this);
    }

    private BlockHolder addBlock(String initialChar) {
        View root = LayoutInflater.from(this).inflate(R.layout.item_restore_char, blockList, false);
        BlockHolder h = new BlockHolder();
        h.root = root;
        h.charIn = root.findViewById(R.id.charIn);
        h.blockErr = root.findViewById(R.id.blockErr);
        h.sampleBox = root.findViewById(R.id.sampleBox);
        h.sampleRow = root.findViewById(R.id.sampleRow);
        h.colorNames = root.findViewById(R.id.colorNames);
        h.flick = root.findViewById(R.id.flick);
        h.bDelete = root.findViewById(R.id.bDeleteBlock);
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        h.flick.setPalette(night ? Kck.COLOR_ARGB_DARK : Kck.COLOR_ARGB, Kck.COLORS);
        h.flick.setListener(color -> onFlick(h, color));
        h.bDelete.setOnClickListener(v -> {
            if (blocks.size() <= 1) {
                h.charIn.setText("");
                clearGlyph(h);
                return;
            }
            blockList.removeView(h.root);
            blocks.remove(h);
            updateFinishEnabled();
            saveDraft();
        });
        h.watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (suppress) return;
                // keep first code point only
                String t = s.toString();
                if (t.codePointCount(0, t.length()) > 1) {
                    int cp = t.codePointAt(0);
                    String one = new String(Character.toChars(cp));
                    suppress = true;
                    h.charIn.setText(one);
                    h.charIn.setSelection(one.length());
                    suppress = false;
                    onCharChanged(h, true);
                    return;
                }
                onCharChanged(h, true);
            }
        };
        h.charIn.addTextChangedListener(h.watcher);
        blockList.addView(root);
        blocks.add(h);
        if (initialChar != null && !initialChar.isEmpty()) {
            suppress = true;
            h.charIn.setText(initialChar);
            suppress = false;
            onCharChanged(h, false);
        }
        updateFinishEnabled();
        return h;
    }

    private void clearGlyph(BlockHolder h) {
        h.glyph = null;
        h.colors = new int[0];
        h.si = 0;
        h.sampleRow.setVisibility(View.GONE);
        h.flick.setVisibility(View.GONE);
        h.blockErr.setText("");
        h.colorNames.setText("");
        updateFinishEnabled();
        saveDraft();
    }

    private void onCharChanged(BlockHolder h, boolean resetIfStrokeCountChanges) {
        if (poolData == null) return;
        String raw = h.charIn.getText().toString();
        if (raw.isEmpty()) { clearGlyph(h); return; }
        String ch = new String(Character.toChars(raw.codePointAt(0)));
        GlyphStrokes g = GlyphStrokes.resolve(poolData, kanaData, ch, cbKf.isChecked());
        if (g.kind == GlyphStrokes.Kind.UNKNOWN) {
            h.blockErr.setText(getString(R.string.restore_unknown_char, ch));
            clearGlyph(h);
            h.blockErr.setText(getString(R.string.restore_unknown_char, ch));
            return;
        }
        h.blockErr.setText("");
        boolean sameSlots = h.glyph != null && h.glyph.colorSlots == g.colorSlots
                && h.glyph.ch.equals(g.ch);
        h.glyph = g;
        if (!sameSlots || resetIfStrokeCountChanges && (h.colors.length != g.colorSlots)) {
            // Reset colors only when stroke/slot count changes (or new char with different slots)
            if (!sameSlots) {
                h.colors = new int[g.colorSlots];
                Arrays.fill(h.colors, -2);
                h.si = 0;
            }
        }
        if (h.colors.length != g.colorSlots) {
            h.colors = new int[g.colorSlots];
            Arrays.fill(h.colors, -2);
            h.si = 0;
        }
        h.sampleRow.setVisibility(View.VISIBLE);
        h.flick.setVisibility(View.VISIBLE);
        refreshSample(h);
        refreshColorNames(h);
        updateFinishEnabled();
        saveDraft();
    }

    private void onFlick(BlockHolder h, int color) {
        if (h.glyph == null || h.colors.length == 0) return;
        if (h.si < 0 || h.si >= h.colors.length) h.si = firstEmpty(h.colors);
        if (h.si >= h.colors.length) h.si = h.colors.length - 1;
        h.colors[h.si] = color;
        h.si = firstEmpty(h.colors);
        if (h.si >= h.colors.length) h.si = h.colors.length - 1;
        refreshSample(h);
        refreshColorNames(h);
        updateFinishEnabled();
        saveDraft();
    }

    private static int firstEmpty(int[] colors) {
        for (int i = 0; i < colors.length; i++) if (colors[i] == -2) return i;
        return colors.length;
    }

    private void refreshSample(BlockHolder h) {
        h.sampleBox.removeAllViews();
        if (h.glyph == null) return;
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int[] palette = night ? Kck.COLOR_ARGB_DARK : Kck.COLOR_ARGB;
        GlyphStrokes g = h.glyph;
        if (g.kind == GlyphStrokes.Kind.ALPHA) {
            TextView big = new TextView(this);
            big.setText(g.ch);
            big.setTextSize(56);
            big.setGravity(android.view.Gravity.CENTER);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            int c = h.colors.length > 0 && h.colors[0] >= 0 ? palette[h.colors[0]] : getColor(R.color.text);
            big.setTextColor(c);
            h.sampleBox.addView(big, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            int[] draw = new int[g.strokeCount];
            Arrays.fill(draw, 0);
            for (int i = 0; i < g.colorSlots && i < h.colors.length; i++) {
                if (h.colors[i] >= 0) draw[i] = h.colors[i];
            }
            for (int i = g.colorSlots; i < g.strokeCount; i++) draw[i] = 0;
            StrokeView sv = new StrokeView(this, g.paths, draw, palette);
            int hi = Math.min(Math.max(h.si, 0), g.strokeCount - 1);
            sv.setHighlightIndex(hi);
            h.sampleBox.addView(sv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void refreshColorNames(BlockHolder h) {
        if (h.glyph == null) { h.colorNames.setText(""); return; }
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int[] palette = night ? Kck.COLOR_ARGB_DARK : Kck.COLOR_ARGB;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int perLine = 5;
        for (int i = 0; i < h.colors.length; i++) {
            if (i > 0 && i % perLine == 0) sb.append('\n');
            else if (i > 0) sb.append(' ');
            int start = sb.length();
            String label;
            int color;
            if (h.colors[i] == -2) { label = "·"; color = getColor(R.color.dim); }
            else if (h.colors[i] < 0) { label = "?"; color = getColor(R.color.dim); }
            else {
                label = Kck.COLORS[h.colors[i]];
                color = palette[h.colors[i]];
            }
            sb.append(label);
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            final int idx = i;
            sb.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) {
                    h.si = idx;
                    refreshSample(h);
                    refreshColorNames(h);
                }
                @Override public void updateDrawState(android.text.TextPaint ds) {
                    ds.setUnderlineText(idx == h.si);
                    ds.setFakeBoldText(idx == h.si);
                }
            }, start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        h.colorNames.setText(sb);
        h.colorNames.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private boolean allBlocksReady() {
        if (blocks.isEmpty()) return false;
        for (BlockHolder h : blocks) {
            if (h.glyph == null) return false;
            for (int c : h.colors) if (c == -2) return false;
        }
        return true;
    }

    private void updateFinishEnabled() {
        bFinish.setEnabled(allBlocksReady() && poolData != null);
    }

    private void saveDraft() {
        List<RamKeyStore.RestoreBlock> list = new ArrayList<>();
        for (BlockHolder h : blocks) {
            RamKeyStore.RestoreBlock b = new RamKeyStore.RestoreBlock();
            b.ch = h.charIn.getText().toString();
            if (!b.ch.isEmpty()) b.ch = new String(Character.toChars(b.ch.codePointAt(0)));
            b.colors = h.colors != null ? h.colors.clone() : new int[0];
            b.kf = cbKf.isChecked();
            list.add(b);
        }
        int depth = 0;
        if (mismatchDepth.getCheckedRadioButtonId() == R.id.mismatch1) depth = 1;
        else if (mismatchDepth.getCheckedRadioButtonId() == R.id.mismatch2) depth = 2;
        RamKeyStore.get().setRestoreDraft(list, parseInt(dataBits.getText().toString(), 256),
                parseInt(checkBits.getText().toString(), 0), cbKf.isChecked(), depth);
        RamStoreUi.refresh(this);
    }

    private String buildDecodeText() {
        int db = parseInt(dataBits.getText().toString(), 256);
        int cb = parseInt(checkBits.getText().toString(), 0);
        boolean allAlpha = true, allKana = true;
        for (BlockHolder h : blocks) {
            if (h.glyph.kind != GlyphStrokes.Kind.ALPHA) allAlpha = false;
            if (h.glyph.kind != GlyphStrokes.Kind.KANA) allKana = false;
        }
        boolean kf = allKana && cbKf.isChecked();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            BlockHolder h = blocks.get(i);
            GlyphStrokes g = h.glyph;
            if (kf && i > 0) body.append(' ');
            body.append(g.ch);
            for (int c : h.colors) body.append(c < 0 ? '?' : (char) ('0' + c));
            if (kf) for (int pad = g.colorSlots; pad < g.strokeCount; pad++) body.append('0');
        }
        String chk = cb != 0 ? "+" + cb : "";
        if (allAlpha) return "A(" + db + chk + "BIT)" + body;
        if (kf) return "KF(" + db + chk + "bit)" + body;
        return "M(" + db + chk + "bit)" + body;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private int selectedMismatchDepth() {
        int id = mismatchDepth.getCheckedRadioButtonId();
        if (id == R.id.mismatch1) return 1;
        if (id == R.id.mismatch2) return 2;
        return 0;
    }

    private void showResults(boolean show) {
        panelResults.setVisibility(show ? View.VISIBLE : View.GONE);
        blockScroll.setVisibility(show ? View.GONE : View.VISIBLE);
        findViewById(R.id.bAddChar).setVisibility(show ? View.GONE : View.VISIBLE);
        bFinish.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void finishAndDecode() {
        if (!allBlocksReady()) {
            Toast.makeText(this, R.string.restore_need_fill, Toast.LENGTH_SHORT).show();
            return;
        }
        saveDraft();
        final String text = buildDecodeText();
        showResults(true);
        resultsBox.removeAllViews();
        TextView spec = new TextView(this);
        spec.setTypeface(Typeface.MONOSPACE);
        spec.setTextColor(getColor(R.color.text));
        spec.setText(text);
        spec.setTextIsSelectable(true);
        resultsBox.addView(spec);
        Button go = new Button(this);
        go.setAllCaps(false);
        go.setText(kdbxBytes != null ? R.string.restore_decode_kdbx : R.string.restore_decode);
        go.setOnClickListener(v -> runDecode(text, kdbxBytes != null));
        resultsBox.addView(go);
        if (selectedMismatchDepth() > 0) {
            TextView mi = new TextView(this);
            mi.setTextColor(getColor(R.color.dim));
            mi.setText(getString(R.string.restore_mismatch_estimate,
                    ColorMismatch.estimateVariantCount(text, selectedMismatchDepth()),
                    selectedMismatchDepth()));
            resultsBox.addView(mi);
        }
        runDecode(text, kdbxBytes != null);
    }

    private void runDecode(String text, boolean withKdbx) {
        cancelDecode.set(false);
        status.setText(withKdbx ? R.string.restore_kdbx_trying : R.string.decoding);
        Button cancel = new Button(this);
        cancel.setAllCaps(false);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(v -> cancelDecode.set(true));
        resultsBox.addView(cancel);
        TextView prog = new TextView(this);
        prog.setTextColor(getColor(R.color.dim));
        resultsBox.addView(prog);
        final Kck.Options opts = new Kck.Options(kanaData.set);
        final byte[] db = withKdbx ? kdbxBytes : null;
        final int depth = selectedMismatchDepth();
        exec.execute(() -> {
            try {
                List<Kck.Candidate> matched = new ArrayList<>();
                long tried = 0; double total = 0;
                long dataB = 0, checkB = 0; String lookalike = null; boolean fixed = false;
                long t0 = System.currentTimeMillis();
                Kck.Decoded d0 = Kck.decodeAny(poolData.pool, text, opts, cancelDecode::get);
                tried += d0.tried; total += d0.total;
                dataB = d0.dataBits; checkB = d0.checkBits; lookalike = d0.lookalike; fixed = d0.fixed;
                List<Kck.Candidate> pool = new ArrayList<>(d0.candidates);
                if (db != null) filterByKdbx(db, pool, matched, prog, t0);
                else matched.addAll(pool);
                if (matched.isEmpty() && depth > 0 && !cancelDecode.get()) {
                    for (int dep = 1; dep <= depth && matched.isEmpty() && !cancelDecode.get(); dep++) {
                        List<String> variants = ColorMismatch.variants(text, dep, ColorMismatch.MAX_VARIANTS);
                        int vi = 0;
                        for (String v : variants) {
                            if (cancelDecode.get()) break;
                            vi++;
                            int viF = vi, depF = dep, nVar = variants.size();
                            main.post(() -> prog.setText(getString(R.string.restore_mismatch_progress,
                                    depF, viF, nVar, matched.size())));
                            try {
                                Kck.Decoded d = Kck.decodeAny(poolData.pool, v, opts, cancelDecode::get);
                                tried += d.tried; total += d.total;
                                List<Kck.Candidate> batch = new ArrayList<>(d.candidates);
                                if (db != null) {
                                    filterByKdbx(db, batch, matched, prog, t0);
                                    if (!matched.isEmpty()) break;
                                } else {
                                    for (Kck.Candidate c : batch) {
                                        boolean dup = false;
                                        for (Kck.Candidate e : matched)
                                            if (e.hex.equals(c.hex)) { dup = true; break; }
                                        if (!dup) matched.add(c);
                                    }
                                    if (!matched.isEmpty()) break;
                                }
                            } catch (Kck.KckException ex) {
                                if ("cancelled".equals(ex.getMessage())) break;
                            }
                        }
                    }
                }
                Kck.Decoded out = new Kck.Decoded(matched, tried, total, dataB, checkB, lookalike, fixed);
                final String noteFinal = (db != null && matched.isEmpty())
                        ? getString(R.string.restore_kdbx_none) : null;
                main.post(() -> showCandidates(out, noteFinal));
            } catch (Exception e) {
                main.post(() -> status.setText(e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        });
    }

    private void filterByKdbx(byte[] db, List<Kck.Candidate> batch, List<Kck.Candidate> matched,
                              TextView prog, long t0) {
        int i = 0;
        for (Kck.Candidate c : batch) {
            if (cancelDecode.get()) break;
            i++;
            if (c.bits.length < 256) continue;
            byte[] keyBits = Arrays.copyOf(c.bits, 256);
            byte[] composite = Kck.bitsToBytes(keyBits);
            try {
                KeePassFacade.openBytes(db, KeePassFacade.credentialsFromComposite(composite));
                matched.add(c);
            } catch (Exception ignored) {
            } finally {
                Arrays.fill(composite, (byte) 0);
                Arrays.fill(keyBits, (byte) 0);
            }
            int done = i;
            long elapsed = System.currentTimeMillis() - t0;
            main.post(() -> prog.setText(getString(R.string.restore_kdbx_progress,
                    done, batch.size(), matched.size(), elapsed / 1000.0)));
            if (!matched.isEmpty()) break;
        }
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
            Button show = new Button(this);
            show.setAllCaps(false);
            show.setText(R.string.kp_show_as_kanji);
            show.setOnClickListener(v -> {
                String hx = c.hex.length() >= 64 ? c.hex.substring(0, 64) : c.hex;
                RamKeyStore.get().setDecodedKeyHex(hx);
                try { RamKeyStore.get().setCompositeHex(hx); } catch (Exception ignored) { }
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra(MainActivity.EXTRA_USE_STORE_KEY, true);
                intent.putExtra(MainActivity.EXTRA_KEY_LOCKED, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            });
            row.addView(show);
            if (fromKeepass || kdbxBytes != null) {
                Button openKp = new Button(this);
                openKp.setAllCaps(false);
                openKp.setText(R.string.restore_open_keepass);
                openKp.setOnClickListener(v -> {
                    String hx = c.hex.length() >= 64 ? c.hex.substring(0, 64) : c.hex;
                    RamKeyStore.get().setDecodedKeyHex(hx);
                    try { RamKeyStore.get().setCompositeHex(hx); } catch (Exception ignored) { }
                    if (kdbxBytes != null)
                        RamKeyStore.get().setKdbx(kdbxBytes, RamKeyStore.get().kdbxName(), RamKeyStore.get().kdbxUri());
                    Intent intent = new Intent(this, KeePassActivity.class);
                    intent.putExtra(KeePassActivity.EXTRA_USE_STORE, true);
                    startActivity(intent);
                });
                row.addView(openKp);
            }
            resultsBox.addView(row);
        }
        if (d.candidates.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.restore_no_candidates);
            empty.setTextColor(getColor(R.color.err));
            resultsBox.addView(empty);
        }
        Button backEdit = new Button(this);
        backEdit.setAllCaps(false);
        backEdit.setText(R.string.restore_back_edit);
        backEdit.setOnClickListener(v -> showResults(false));
        resultsBox.addView(backEdit);
        RamStoreUi.refresh(this);
    }

    @Override protected void onDestroy() {
        cancelDecode.set(true);
        exec.shutdownNow();
        super.onDestroy();
    }
}
