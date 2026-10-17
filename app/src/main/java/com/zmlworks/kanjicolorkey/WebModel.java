package com.zmlworks.kanjicolorkey;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The web app's UI logic (web/app.html render(), click handler, buttons, decode summary and #demo), kept free of
 * Android APIs so it is unit-tested against the JS-generated vectors.
 */
public final class WebModel {
    private WebModel() {}

    public static final int MAX_SHOWN = 50;
    public static final String DEMO_HEX = "c2fb9f70272dae3adee45198b576b4873f5ad57ac41696bdfaa92e6de9923e14";

    public static final class Result {
        public final Kck.Encoded a, b; // a = 最後を漢字で (tail kanji), b = 最後を英数字で (tail alpha)
        public final int common;       // shared leading symbols (clickable)
        Result(Kck.Encoded a, Kck.Encoded b, int common) { this.a = a; this.b = b; this.common = common; }
        public boolean hasVariants() { return a.tokens.size() > common || b.tokens.size() > common; }
    }

    public static Result render(Kck.Pool pool, byte[] bits, int checkBits, Set<Integer> alphaPos) {
        Kck.Encoded a = Kck.encodeMixed(pool, bits, checkBits, alphaPos, false);
        Kck.Encoded b = Kck.encodeMixed(pool, bits, checkBits, alphaPos, true);
        int n = 0;
        while (n < a.tokens.size() && n < b.tokens.size() && a.tokens.get(n).type == b.tokens.get(n).type
                && a.tokens.get(n).ch.equals(b.tokens.get(n).ch)) n++;
        return new Result(a, b, n);
    }

    /** fmtInfo(): "漢字8・英数字0・書く量290bit". */
    public static String info(Kck.Encoded r) {
        return "漢字" + r.count(Kck.Type.KANJI) + "・英数字" + r.count(Kck.Type.ALPHA) + "・書く量" + r.writtenBits + "bit";
    }

    /** The #info line above the cards. */
    public static String head(Result r) {
        return r.a.totalBits + "bit (データ" + r.a.dataBits + "+チェック" + r.a.checkBits + ") ／ 共通部分 " + r.common + "記号";
    }

    /** Click on symbol k (1-based): toggles it kanji ⇄ glyph; later positions go back to kanji, earlier ones stay. */
    public static Set<Integer> toggle(Set<Integer> alphaPos, int k) {
        boolean was = alphaPos.contains(k);
        Set<Integer> next = new TreeSet<>();
        for (int p : alphaPos) if (p < k) next.add(p);
        if (!was) next.add(k);
        return next;
    }

    /** 全部英数字: positions 1..80. */
    public static Set<Integer> allAlpha() {
        Set<Integer> s = new TreeSet<>();
        for (int i = 1; i <= 80; i++) s.add(i);
        return s;
    }

    public static String keyInfo(byte[] bits) { return "鍵 (" + bits.length + "bit) " + Kck.bitsToHex(bits); }

    /** Summary line of a decode, as in the web app. */
    public static String decodeSummary(Kck.Decoded r) {
        int n = r.candidates.size();
        String note = r.checkBits == 0
                ? (n > 1 ? "（チェックビット0のため候補を絞り込めません。チェックを付けるか、各候補を実際のパスワードマネージャ等で試してください）" : "（チェックビット0：照合なし）")
                : (n > 1 ? "（" + r.checkBits + "bitチェックでも複数残りました。各候補を試してください）" : "");
        return r.tried + "通り試行 → 候補 " + n + "件" + note + (n > MAX_SHOWN ? "（先頭50件のみ表示）" : "");
    }

    public static final class Demo {
        public final byte[] key; public final Set<Integer> alphaPos; public final Result result; public final String decodeInput;
        Demo(byte[] key, Set<Integer> alphaPos, Result result, String decodeInput) { this.key = key; this.alphaPos = alphaPos; this.result = result; this.decodeInput = decodeInput; }
    }

    /** #demo: fixed key, glyphs at 3 and 6, decode the first ending with the first color of the first kanji unknown. */
    public static Demo demo(Kck.Pool pool) {
        byte[] key = Kck.hexToBits(DEMO_HEX + DEMO_HEX);               // 512 bits, the slider (256) takes the first half
        byte[] bits = java.util.Arrays.copyOf(key, 256);
        Set<Integer> pos = new HashSet<>(); pos.add(3); pos.add(6);
        Result r = render(pool, bits, 0, pos);
        String input = r.a.text.replaceFirst("^(M\\([0-9+]+bit\\)\\S)[0-9]", "$1?");
        return new Demo(key, pos, r, input);
    }
}
