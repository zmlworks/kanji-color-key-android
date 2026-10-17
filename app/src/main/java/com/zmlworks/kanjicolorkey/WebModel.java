package com.zmlworks.kanjicolorkey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The web app's UI logic (web/app.html render(), click handler, buttons, decode summary and #demo; src/kck.js
 * webEncode / nextType / clickType / keepEnabled / allOf / webInfo / webHead), kept free of Android APIs so it is
 * unit-tested against the JS-generated vectors.
 */
public final class WebModel {
    private WebModel() {}

    public static final int MAX_SHOWN = 50;
    public static final int ALL_POSITIONS = 80;
    public static final String DEMO_HEX = "c2fb9f70272dae3adee45198b576b4873f5ad57ac41696bdfaa92e6de9923e14";

    /** Which symbol types the user switched on (kanji are always on). */
    public static final class Enabled {
        public final boolean alpha, kana;
        public Enabled(boolean alpha, boolean kana) { this.alpha = alpha; this.kana = kana; }
    }

    // ---- symbol types per position: {1-based position: ALPHA | KANA}, positions not listed are kanji
    /** Rotation kanji → alpha → kana → kanji, skipping disabled types. */
    public static Kck.Type nextType(Kck.Type cur, Enabled en) {
        List<Kck.Type> on = new ArrayList<>();
        on.add(Kck.Type.KANJI);
        if (en.alpha) on.add(Kck.Type.ALPHA);
        if (en.kana) on.add(Kck.Type.KANA);
        return on.get((on.indexOf(cur) + 1) % on.size());
    }

    /** Click on symbol k: rotate its type; later positions go back to kanji (the boundaries move), earlier ones stay. */
    public static Map<Integer, Kck.Type> clickType(Map<Integer, Kck.Type> types, int k, Enabled en) {
        Map<Integer, Kck.Type> next = new TreeMap<>();
        for (Map.Entry<Integer, Kck.Type> e : types.entrySet()) if (e.getKey() < k) next.put(e.getKey(), e.getValue());
        Kck.Type cur = types.get(k);
        Kck.Type n = nextType(cur == null ? Kck.Type.KANJI : cur, en);
        if (n != Kck.Type.KANJI) next.put(k, n);
        return next;
    }

    /** Drop positions whose type was switched off. */
    public static Map<Integer, Kck.Type> keepEnabled(Map<Integer, Kck.Type> types, Enabled en) {
        Map<Integer, Kck.Type> next = new TreeMap<>();
        for (Map.Entry<Integer, Kck.Type> e : types.entrySet())
            if (e.getValue() == Kck.Type.ALPHA ? en.alpha : en.kana) next.put(e.getKey(), e.getValue());
        return next;
    }

    /** 全部漢字 / 全部英数字 / 全部かな: positions 1..80. */
    public static Map<Integer, Kck.Type> allOf(Kck.Type type) {
        Map<Integer, Kck.Type> m = new TreeMap<>();
        if (type != Kck.Type.KANJI) for (int i = 1; i <= ALL_POSITIONS; i++) m.put(i, type);
        return m;
    }

    public static boolean isAllOf(Map<Integer, Kck.Type> types, Kck.Type type) {
        for (int i = 1; i <= ALL_POSITIONS; i++) if (types.get(i) != type) return false;
        return true;
    }

    static Set<Integer> positions(Map<Integer, Kck.Type> types, Kck.Type t) {
        Set<Integer> s = new TreeSet<>();
        for (Map.Entry<Integer, Kck.Type> e : types.entrySet()) if (e.getValue() == t) s.add(e.getKey());
        return s;
    }

    // ---- encoding as shown
    public static final class Variant {
        /** null for the fixed-length kana (KF) result. */
        public final Kck.Tail tail; public final Kck.Encoded enc;
        Variant(Kck.Tail tail, Kck.Encoded enc) { this.tail = tail; this.enc = enc; }
        public String tailName() { return tail == null ? "fixed" : tail.name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static final class Result {
        public final List<Variant> variants;
        public final int common;       // shared leading symbols (clickable)
        public final boolean fixed;    // KF
        Result(List<Variant> variants, int common, boolean fixed) { this.variants = variants; this.common = common; this.fixed = fixed; }
        public Kck.Encoded first() { return variants.get(0).enc; }
        public boolean hasVariants() {
            if (fixed) return false;
            for (Variant v : variants) if (v.enc.tokens.size() > common) return true;
            return false;
        }
    }

    /**
     * The endings shown: always tail KANJI (keep the types, pad the last symbol), plus ALPHA / KANA when enabled;
     * identical endings are shown once. fixed (KF) applies only when kana are enabled and every position is kana.
     */
    public static Result render(Kck.Pool pool, Kck.KanaSet kana, byte[] bits, int checkBits, Map<Integer, Kck.Type> types, Enabled en, boolean fixed) {
        if (en.kana && fixed && isAllOf(types, Kck.Type.KANA)) {
            Kck.Encoded e = Kck.encodeKanaFixed(kana, bits, checkBits);
            return new Result(Collections.singletonList(new Variant(null, e)), e.tokens.size(), true);
        }
        Set<Integer> ap = positions(types, Kck.Type.ALPHA), kp = positions(types, Kck.Type.KANA);
        List<Kck.Tail> tails = new ArrayList<>();
        tails.add(Kck.Tail.KANJI);
        if (en.alpha) tails.add(Kck.Tail.ALPHA);
        if (en.kana) tails.add(Kck.Tail.KANA);
        List<Variant> vs = new ArrayList<>();
        outer:
        for (Kck.Tail t : tails) {
            Kck.Encoded e = Kck.encodeMixed(pool, bits, checkBits, ap, kp, t, kana);
            for (Variant v : vs) if (v.enc.text.equals(e.text)) continue outer;
            vs.add(new Variant(t, e));
        }
        List<Kck.Token> f = vs.get(0).enc.tokens;
        int n = 0;
        for (boolean same = true; same; ) {
            for (Variant v : vs) {
                List<Kck.Token> t = v.enc.tokens;
                if (n >= t.size() || t.get(n).type != f.get(n).type || !t.get(n).ch.equals(f.get(n).ch)) { same = false; break; }
            }
            if (same) n++;
        }
        return new Result(vs, n, false);
    }

    private static int count(Kck.Encoded r, Kck.Type... ts) {
        int n = 0;
        for (Kck.Type t : ts) n += r.count(t);
        return n;
    }

    /** webInfo(): "漢字8・英数字0・かな0・書く量290bit". */
    public static String info(Kck.Encoded r) {
        return "漢字" + r.count(Kck.Type.KANJI) + "・英数字" + r.count(Kck.Type.ALPHA) + "・かな" + count(r, Kck.Type.KANA, Kck.Type.KANA_GLYPH)
                + "・書く量" + r.writtenBits + "bit";
    }

    /** webHead(): the line above the cards. */
    public static String head(Result r) {
        Kck.Encoded a = r.first();
        return a.totalBits + "bit (データ" + a.dataBits + "+チェック" + a.checkBits + ") ／ 共通部分 " + r.common + "記号";
    }

    /** Title of an ending, as tailTitle() in web/app.html. */
    public static String tailTitle(Variant v) {
        if (v.tail == null) return "固定長（KF）";
        if (v.tail == Kck.Tail.ALPHA) return "最後を英数字で";
        if (v.tail == Kck.Tail.KANA) return "最後を1色かなで";
        List<Kck.Token> t = v.enc.tokens;
        Kck.Type last = t.isEmpty() ? null : t.get(t.size() - 1).type;
        return last == Kck.Type.KANA ? "最後をかな（全画）で" : last == Kck.Type.ALPHA ? "最後を英数字で" : "最後を漢字で";
    }

    public static String keyInfo(byte[] bits) { return "鍵 (" + bits.length + "bit) " + Kck.bitsToHex(bits); }

    /** Summary of a decode, as in the web app (the look-alike note on a second line). */
    public static String decodeSummary(Kck.Decoded r) {
        int n = r.candidates.size();
        String note = r.checkBits == 0
                ? (n > 1 ? "（チェックビット0のため候補を絞り込めません。チェックを付けるか、各候補を実際のパスワードマネージャ等で試してください）" : "（チェックビット0：照合なし）")
                : (n > 1 ? "（" + r.checkBits + "bitチェックでも複数残りました。各候補を試してください）" : "");
        return r.tried + "通り試行 → 候補 " + n + "件" + note + (n > MAX_SHOWN ? "（先頭50件のみ表示）" : "") + lookalikeNote(r.lookalike);
    }

    static String lookalikeNote(String l) {
        if ("fallback".equals(l)) return "\n（そのままでは候補がなかったため、かなの見間違い（お/オ など）を入れ替えた読みも試しました。候補の文字列で入れ替わった字を確かめてください）";
        if ("always".equals(l)) return "\n（かなの見間違いを入れ替えた読みも試しました）";
        if ("skipped".equals(l)) return "\n（かなの見間違いの入れ替えは、組み合わせが多すぎるため試していません）";
        return "";
    }

    public static final class Demo {
        public final byte[] key; public final Map<Integer, Kck.Type> types; public final Result result; public final String decodeInput;
        Demo(byte[] key, Map<Integer, Kck.Type> types, Result result, String decodeInput) { this.key = key; this.types = types; this.result = result; this.decodeInput = decodeInput; }
    }

    /** #demo: fixed key, alpha at 3, kana at 5 and 8; decode the first ending with the first color of the first kanji unknown. */
    public static Demo demo(Kck.Pool pool, Kck.KanaSet kana) {
        byte[] key = Kck.hexToBits(DEMO_HEX + DEMO_HEX);               // 512 bits, the slider (256) takes the first half
        byte[] bits = java.util.Arrays.copyOf(key, 256);
        Map<Integer, Kck.Type> types = new TreeMap<>();
        types.put(3, Kck.Type.ALPHA); types.put(5, Kck.Type.KANA); types.put(8, Kck.Type.KANA);
        Result r = render(pool, kana, bits, 0, types, new Enabled(true, true), false);
        String input = r.first().text.replaceFirst("^(M\\([0-9+]+bit\\)\\S)[0-9]", "$1?");
        return new Demo(key, types, r, input);
    }
}
