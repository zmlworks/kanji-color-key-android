package com.zmlworks.kanjicolorkey;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
    private TextView status;
    private Button bFinish;

    static final int DEFAULT_BLOCKS = 5;
    private final List<BlockHolder> blocks = new ArrayList<>();
    private boolean shownNight;
    private AlertDialog askDialog;
    private byte[] kdbxBytes;
    private boolean fromKeepass;
    private boolean suppress;

    static final class BlockHolder {
        View root;
        EditText charIn;
        TextView blockErr, colorNames, blockNo;
        FlickSurfaceLayout sampleBox;
        View sampleRow, fallbackRow;
        Button bDelete, bFallback;
        GlyphStrokes glyph;
        int[] colors = new int[0]; // 0..3 or -1 (?); -2 = not entered yet
        int si;
        TextWatcher watcher;
        SpanWatcher composingWatcher;
    }

    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        Themes.override(this, base);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (Themes.systemNightChanged(this, c, shownNight)) { saveDraft(); recreate(); }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        settings = new Settings(this);
        Themes.applyWindowFlags(this);
        super.onCreate(null); // never restore secrets from saved state
        setContentView(R.layout.activity_restore);
        shownNight = Themes.isNight(this);
        RamStoreUi.bind(this);

        blockList = findViewById(R.id.blockList);
        blockScroll = findViewById(R.id.blockScroll);
        panelResults = findViewById(R.id.panelResults);
        resultsBox = findViewById(R.id.resultsBox);
        dataBits = findViewById(R.id.dataBits);
        checkBits = findViewById(R.id.checkBits);
        cbKf = findViewById(R.id.cbKf);
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
        findViewById(R.id.bAddChar).setOnClickListener(v -> {
            BlockHolder h = addBlock("", -1);
            h.charIn.requestFocus();
        });
        findViewById(R.id.bSettings).setOnClickListener(v -> askDialog = SettingsDialog.show(this, () -> {
            saveDraft();
            recreate();
        }));
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
        }
        suppress = false;
        blockList.removeAllViews();
        blocks.clear();
        for (RamKeyStore.RestoreBlock b : draft) {
            BlockHolder h = addBlock(b.ch, -1);
            if (b.colors != null && b.colors.length > 0 && h.glyph != null
                    && b.colors.length == h.glyph.colorSlots) {
                h.colors = b.colors.clone();
                h.si = clampSi(h, firstEmpty(h.colors));
                refreshSample(h);
                refreshColorNames(h);
            }
        }
        while (blocks.size() < DEFAULT_BLOCKS) addBlock("", -1);
        updateFinishEnabled();
        RamStoreUi.refresh(this);
    }

    /** Adds a block at {@code index} (-1 = end). */
    private BlockHolder addBlock(String initialChar, int index) {
        View root = LayoutInflater.from(this).inflate(R.layout.item_restore_char, blockList, false);
        BlockHolder h = new BlockHolder();
        h.root = root;
        h.charIn = root.findViewById(R.id.charIn);
        h.blockErr = root.findViewById(R.id.blockErr);
        h.blockNo = root.findViewById(R.id.blockNo);
        h.sampleBox = root.findViewById(R.id.sampleBox);
        h.sampleRow = root.findViewById(R.id.sampleRow);
        h.fallbackRow = root.findViewById(R.id.fallbackRow);
        h.colorNames = root.findViewById(R.id.colorNames);
        h.bDelete = root.findViewById(R.id.bDeleteBlock);
        h.bFallback = root.findViewById(R.id.bFallback);
        int[] palette = Themes.palette(this);
        h.sampleBox.setPalette(palette, Kck.COLORS);
        h.sampleBox.setListener(color -> onFlick(h, color));
        int[] fb = {R.id.fb0, R.id.fb1, R.id.fb2, R.id.fb3, R.id.fbQ};
        for (int i = 0; i < fb.length; i++) {
            final int color = i < 4 ? i : -1;
            Button b = root.findViewById(fb[i]);
            if (i < 4) b.setTextColor(palette[i]);
            b.setOnClickListener(v -> onFlick(h, color));
        }
        h.bFallback.setOnClickListener(v -> h.fallbackRow.setVisibility(
                h.fallbackRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        h.bDelete.setOnClickListener(v -> {
            if (blocks.size() <= 1) {
                setCharSilently(h, "");
                clearGlyph(h);
                return;
            }
            blockList.removeView(h.root);
            blocks.remove(h);
            renumber();
            updateFinishEnabled();
            saveDraft();
        });
        h.composingWatcher = new SpanWatcher() {
            @Override public void onSpanAdded(Spannable text, Object what, int start, int end) { }
            @Override public void onSpanChanged(Spannable text, Object what, int os, int oe, int ns, int ne) { }
            @Override public void onSpanRemoved(Spannable text, Object what, int start, int end) {
                // composition finished without a text change (e.g. the IME just "confirmed" it)
                main.post(() -> { if (!isComposing(h.charIn.getText())) commitChars(h); });
            }
        };
        h.watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (suppress) return;
                if (isComposing(s)) {
                    // IME is still converting (e.g. あさ → 朝): do not take the reading as the character
                    if (s.getSpans(0, s.length(), SpanWatcher.class).length == 0
                            || !Arrays.asList(s.getSpans(0, s.length(), SpanWatcher.class)).contains(h.composingWatcher))
                        s.setSpan(h.composingWatcher, 0, s.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                    return;
                }
                commitChars(h);
            }
        };
        h.charIn.addTextChangedListener(h.watcher);
        h.charIn.setOnFocusChangeListener((v, has) -> { if (!has) commitChars(h); });
        h.charIn.setOnEditorActionListener((v, actionId, ev) -> {
            commitChars(h);
            int i = blocks.indexOf(h);
            if (i >= 0 && i + 1 < blocks.size()) { blocks.get(i + 1).charIn.requestFocus(); return true; }
            return false;
        });
        if (index < 0 || index > blocks.size()) index = blocks.size();
        blockList.addView(root, index);
        blocks.add(index, h);
        renumber();
        if (initialChar != null && !initialChar.isEmpty()) {
            setCharSilently(h, initialChar);
            onCharChanged(h, false);
        }
        updateFinishEnabled();
        return h;
    }

    private void renumber() {
        for (int i = 0; i < blocks.size(); i++) blocks.get(i).blockNo.setText(String.valueOf(i + 1));
    }

    static boolean isComposing(CharSequence s) {
        if (!(s instanceof Spannable)) return false;
        Spannable sp = (Spannable) s;
        return BaseInputConnection.getComposingSpanStart(sp) != -1
                || BaseInputConnection.getComposingSpanEnd(sp) != -1;
    }

    private void setCharSilently(BlockHolder h, String s) {
        boolean old = suppress;
        suppress = true;
        h.charIn.setText(s);
        h.charIn.setSelection(h.charIn.getText().length());
        suppress = old;
    }

    /**
     * Takes the committed text of a block: the first character stays here, any further characters
     * (pasted, or several committed at once by the IME) go into the following blocks — into the next
     * empty block, else a new block is inserted.
     */
    void commitChars(BlockHolder h) {
        if (suppress || poolData == null || !blocks.contains(h)) return;
        Editable e = h.charIn.getText();
        if (isComposing(e)) return;
        String raw = e.toString().replaceAll("\\s+", "");
        if (raw.isEmpty()) {
            if (e.length() > 0) setCharSilently(h, "");
            if (h.glyph != null) clearGlyph(h);
            else h.blockErr.setText("");
            return;
        }
        int[] cps = raw.codePoints().toArray();
        String first = new String(Character.toChars(cps[0]));
        if (!first.equals(e.toString())) setCharSilently(h, first);
        onCharChanged(h, true);
        int at = blocks.indexOf(h);
        for (int k = 1; k < cps.length; k++) {
            String ch = new String(Character.toChars(cps[k]));
            int next = at + 1;
            if (next < blocks.size() && blocks.get(next).charIn.getText().length() == 0) {
                BlockHolder n = blocks.get(next);
                setCharSilently(n, ch);
                onCharChanged(n, true);
            } else {
                addBlock(ch, next);
            }
            at = next;
        }
        if (cps.length > 1 && at + 1 < blocks.size()) blocks.get(at + 1).charIn.requestFocus();
    }

    private void clearGlyph(BlockHolder h) {
        h.glyph = null;
        h.colors = new int[0];
        h.si = 0;
        h.sampleRow.setVisibility(View.GONE);
        h.fallbackRow.setVisibility(View.GONE);
        h.bFallback.setVisibility(View.GONE);
        h.blockErr.setText("");
        h.colorNames.setText("");
        updateFinishEnabled();
        saveDraft();
    }

    private void onCharChanged(BlockHolder h, boolean resetIfStrokeCountChanges) {
        if (poolData == null) return;
        String raw = h.charIn.getText().toString().trim();
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
        h.bFallback.setVisibility(View.VISIBLE);
        refreshSample(h);
        refreshColorNames(h);
        updateFinishEnabled();
        saveDraft();
    }

    private void onFlick(BlockHolder h, int color) {
        if (h.glyph == null || h.colors.length == 0) return;
        h.si = clampSi(h, h.si);
        h.colors[h.si] = color;
        int nx = firstEmpty(h.colors);
        // after the last empty stroke, stay on the last one (re-flick to correct)
        h.si = nx < h.colors.length ? nx : Math.min(h.si + 1, h.colors.length - 1);
        refreshSample(h);
        refreshColorNames(h);
        updateFinishEnabled();
        saveDraft();
    }

    private static int clampSi(BlockHolder h, int si) {
        if (h.colors.length == 0) return 0;
        return Math.max(0, Math.min(si, h.colors.length - 1));
    }

    private static int firstEmpty(int[] colors) {
        for (int i = 0; i < colors.length; i++) if (colors[i] == -2) return i;
        return colors.length;
    }

    private void refreshSample(BlockHolder h) {
        h.sampleBox.removeAllViews();
        if (h.glyph == null) return;
        int[] palette = Themes.palette(this);
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
        int[] palette = Themes.palette(this);
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

    private boolean anyCharEntered() {
        for (BlockHolder h : blocks) if (h.charIn.getText().toString().trim().length() > 0) return true;
        return false;
    }

    private void updateFinishEnabled() {
        bFinish.setEnabled(poolData != null && anyCharEntered());
    }

    private void saveDraft() {
        if (dataBits == null) return;
        List<RamKeyStore.RestoreBlock> list = new ArrayList<>();
        for (BlockHolder h : blocks) {
            RamKeyStore.RestoreBlock b = new RamKeyStore.RestoreBlock();
            b.ch = h.charIn.getText().toString().trim();
            if (!b.ch.isEmpty()) b.ch = new String(Character.toChars(b.ch.codePointAt(0)));
            b.colors = h.colors != null ? h.colors.clone() : new int[0];
            b.kf = cbKf.isChecked();
            list.add(b);
        }
        RamKeyStore.get().setRestoreDraft(list, parseInt(dataBits.getText().toString(), 256),
                parseInt(checkBits.getText().toString(), 0), cbKf.isChecked(), 0);
        RamStoreUi.refresh(this);
    }

    /** What "完了" will decode, or why it can't. Never throws. */
    static final class Plan {
        String text;           // null when not decodable
        String error;          // user-facing reason when text == null
        int skippedEmpty;      // empty blocks ignored
        int unfilledAsQ;       // strokes without a color, sent as '?'
    }

    Plan buildPlan() {
        Plan p = new Plan();
        List<BlockHolder> used = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (BlockHolder h : blocks) {
            String raw = h.charIn.getText().toString().trim();
            if (raw.isEmpty()) { p.skippedEmpty++; continue; }
            if (h.glyph == null) onCharChanged(h, false); // e.g. text that never got committed
            if (h.glyph == null) { unknown.add(raw); continue; }
            used.add(h);
        }
        // trailing empties are expected (5 blocks by default) – only count the ones in between
        int lastUsed = -1;
        for (int i = 0; i < blocks.size(); i++)
            if (blocks.get(i).charIn.getText().toString().trim().length() > 0) lastUsed = i;
        p.skippedEmpty = 0;
        for (int i = 0; i < lastUsed; i++)
            if (blocks.get(i).charIn.getText().toString().trim().isEmpty()) p.skippedEmpty++;
        if (!unknown.isEmpty()) {
            p.error = getString(R.string.restore_unknown_abort, String.join(" ", unknown));
            return p;
        }
        if (used.isEmpty()) { p.error = getString(R.string.restore_need_one); return p; }
        for (BlockHolder h : used) for (int c : h.colors) if (c == -2) p.unfilledAsQ++;
        p.text = buildDecodeText(used);
        return p;
    }

    private String buildDecodeText(List<BlockHolder> used) {
        int db = parseInt(dataBits.getText().toString(), 256);
        int cb = parseInt(checkBits.getText().toString(), 0);
        boolean allAlpha = true, allKana = true;
        for (BlockHolder h : used) {
            if (h.glyph.kind != GlyphStrokes.Kind.ALPHA) allAlpha = false;
            if (h.glyph.kind != GlyphStrokes.Kind.KANA) allKana = false;
        }
        boolean kf = allKana && cbKf.isChecked();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < used.size(); i++) {
            BlockHolder h = used.get(i);
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

    private void showResults(boolean show) {
        panelResults.setVisibility(show ? View.VISIBLE : View.GONE);
        blockScroll.setVisibility(show ? View.GONE : View.VISIBLE);
        findViewById(R.id.editButtons).setVisibility(show ? View.GONE : View.VISIBLE);
    }

    void finishAndDecode() {
        for (BlockHolder h : blocks) if (!isComposing(h.charIn.getText())) commitChars(h);
        Plan plan;
        try { plan = buildPlan(); }
        catch (Exception e) { plan = new Plan(); plan.error = String.valueOf(e.getMessage()); }
        if (plan.text == null) {
            status.setText(plan.error);
            Toast.makeText(this, plan.error, Toast.LENGTH_LONG).show();
            return;
        }
        saveDraft();
        final String text = plan.text;
        showResults(true);
        resultsBox.removeAllViews();
        StringBuilder notes = new StringBuilder();
        if (plan.skippedEmpty > 0) notes.append(getString(R.string.restore_skipped_empty, plan.skippedEmpty)).append('\n');
        if (plan.unfilledAsQ > 0) notes.append(getString(R.string.restore_unfilled_as_q, plan.unfilledAsQ)).append('\n');
        notes.append(kdbxBytes != null
                ? getString(R.string.restore_kdbx_attach, RamKeyStore.get().kdbxName() != null
                        ? RamKeyStore.get().kdbxName() : getString(R.string.kp_file_handoff))
                : getString(R.string.restore_kdbx_none_held));
        lastNotes = notes.toString();
        lastText = text;
        startPass(text, 0, null);
    }

    private String lastNotes = "", lastText;
    private RestoreSearch.Result lastDepth1;

    private TextView addLine(CharSequence s, int colorRes, boolean mono) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(getColor(colorRes));
        if (mono) { v.setTypeface(Typeface.MONOSPACE); v.setTextIsSelectable(true); }
        v.setPadding(0, 4, 0, 4);
        resultsBox.addView(v);
        return v;
    }

    /** Runs one depth; exact (0) → automatically 1 → ask before 2. */
    private void startPass(String text, int depth, String banner) {
        cancelDecode.set(false);
        resultsBox.removeAllViews();
        addLine(text, R.color.text, true);
        addLine(lastNotes, R.color.dim, false);
        if (banner != null) addLine(banner, R.color.text, false);
        TextView prog = addLine("", R.color.dim, false);
        Button cancel = new Button(this);
        cancel.setAllCaps(false);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(v -> cancelDecode.set(true));
        resultsBox.addView(cancel);
        status.setText(kdbxBytes != null ? R.string.restore_kdbx_trying : R.string.decoding);
        final Kck.Options opts = new Kck.Options(kanaData.set);
        final byte[] db = kdbxBytes;
        final RestoreSearch.Verifier verifier = db == null ? null : c -> verifyKdbx(db, c);
        exec.execute(() -> {
            RestoreSearch.Result r;
            try {
                long[] lastPost = {0};
                r = RestoreSearch.run(poolData.pool, text, opts, depth, verifier, cancelDecode::get,
                        (dep, vi, nv, verified, matched) -> {
                            long now = System.currentTimeMillis();
                            if (now - lastPost[0] < 150 && vi < nv) return;
                            lastPost[0] = now;
                            main.post(() -> prog.setText(dep == 0
                                    ? getString(R.string.restore_progress_exact, verified, matched)
                                    : getString(R.string.restore_mismatch_progress, dep, vi, nv, verified, matched)));
                        });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> showError(msg));
                return;
            }
            final RestoreSearch.Result fr = r;
            main.post(() -> onPassDone(text, fr));
        });
    }

    private void showError(String msg) {
        resultsBox.removeAllViews();
        addLine(lastText != null ? lastText : "", R.color.text, true);
        addLine(lastNotes, R.color.dim, false);
        String full = getString(R.string.restore_decode_failed, msg);
        addLine(full, R.color.err, false);
        status.setText(full);
        Button backEdit = new Button(this);
        backEdit.setAllCaps(false);
        backEdit.setText(R.string.restore_back_edit);
        backEdit.setOnClickListener(v -> showResults(false));
        resultsBox.addView(backEdit);
    }

    private void onPassDone(String text, RestoreSearch.Result r) {
        if (isFinishing() || isDestroyed()) return;
        if (!r.matched.isEmpty() || r.cancelled) {
            String note = r.depth > 0 && !r.matched.isEmpty()
                    ? getString(R.string.restore_found_mismatch, r.depth) : null;
            showCandidates(r.toDecoded(), note);
            return;
        }
        if (r.depth == 0) {
            startPass(text, 1, getString(R.string.restore_auto_mismatch1));
            return;
        }
        if (r.depth == 1) {
            lastDepth1 = r;
            RestoreSearch.Estimate est = RestoreSearch.estimateDepth2(text, r, kdbxBytes != null);
            if (est.variants == 0) { showCandidates(r.toDecoded(), kdbxBytes != null ? getString(R.string.restore_kdbx_none) : null); return; }
            String msg = kdbxBytes != null
                    ? getString(R.string.restore_ask_mismatch2_msg, est.variants, est.verifications,
                            RestoreSearch.humanDuration(est.seconds))
                    : getString(R.string.restore_ask_mismatch2_msg_nokdbx, est.variants,
                            RestoreSearch.humanDuration(est.seconds));
            if (est.variantsUncapped > est.variants)
                msg = msg + "\n" + getString(R.string.restore_ask_cap, est.variantsUncapped, est.variants);
            showCandidates(r.toDecoded(), kdbxBytes != null ? getString(R.string.restore_kdbx_none) : null);
            askDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.restore_ask_mismatch2_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.restore_try2, (d, w) -> startPass(text, 2, null))
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            askDialog.show();
            return;
        }
        showCandidates(r.toDecoded(), kdbxBytes != null ? getString(R.string.restore_kdbx_none) : null);
    }

    AlertDialog shownDialogForTest() { return askDialog; }
    List<BlockHolder> blocksForTest() { return blocks; }

    private static boolean verifyKdbx(byte[] db, Kck.Candidate c) {
        if (c.bits.length < 256) return false;
        byte[] keyBits = Arrays.copyOf(c.bits, 256);
        byte[] composite = Kck.bitsToBytes(keyBits);
        try {
            KeePassFacade.openBytes(db, KeePassFacade.credentialsFromComposite(composite));
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            Arrays.fill(composite, (byte) 0);
            Arrays.fill(keyBits, (byte) 0);
        }
    }

    private void showCandidates(Kck.Decoded d, String note) {
        resultsBox.removeAllViews();
        status.setText(note != null ? note : WebModel.decodeSummary(d));
        if (lastText != null) addLine(lastText, R.color.dim, true);
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
