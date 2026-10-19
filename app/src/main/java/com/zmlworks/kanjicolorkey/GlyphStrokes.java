package com.zmlworks.kanjicolorkey;

/**
 * Resolves stroke SVG paths / color slot counts for a typed character, matching the encoder.
 * Kanji/kana: KanjiVG paths; counts == encoder pool/kana counts (same JSON source).
 * Alpha: a single color slot (encoder has no stroke paths for alphanumerics).
 */
public final class GlyphStrokes {
    public enum Kind { KANJI, KANA, ALPHA, UNKNOWN }

    public final Kind kind;
    public final String ch;
    public final int index;
    public final String[] paths;  // null for ALPHA
    public final int colorSlots;
    public final int strokeCount;

    private GlyphStrokes(Kind kind, String ch, int index, String[] paths, int colorSlots, int strokeCount) {
        this.kind = kind; this.ch = ch; this.index = index; this.paths = paths;
        this.colorSlots = colorSlots; this.strokeCount = strokeCount;
    }

    public static GlyphStrokes resolve(PoolData pool, KanaData kana, String ch, boolean kanaFixed) {
        if (ch == null || ch.isEmpty()) return unknown("?");
        Integer ki = indexOf(pool.pool.chars, ch);
        if (ki != null) {
            String[] p = pool.strokes[ki];
            return new GlyphStrokes(Kind.KANJI, ch, ki, p, p.length, p.length);
        }
        int ai = Kck.ALPHA.indexOf(ch);
        if (ai >= 0) return new GlyphStrokes(Kind.ALPHA, ch, ai, null, 1, 1);
        Integer ni = kana.set.indexOf(ch);
        if (ni != null) {
            String[] p = kana.strokes[ni];
            // KF encodes exactly 3 colors per kana; SVG may have 4 strokes (4th always black in the text form).
            int slots = kanaFixed ? 3 : p.length;
            return new GlyphStrokes(Kind.KANA, ch, ni, p, slots, p.length);
        }
        return unknown(ch);
    }

    private static Integer indexOf(String[] chars, String ch) {
        for (int i = 0; i < chars.length; i++) if (chars[i].equals(ch)) return i;
        return null;
    }

    private static GlyphStrokes unknown(String ch) {
        return new GlyphStrokes(Kind.UNKNOWN, ch, -1, null, 0, 0);
    }

    /** Encoder counts vs SVG path lengths. Must be 0 for a healthy build. */
    public static int countMismatches(PoolData pool, KanaData kana) {
        int n = 0;
        for (int i = 0; i < pool.pool.counts.length; i++)
            if (pool.pool.counts[i] != pool.strokes[i].length) n++;
        for (int i = 0; i < kana.set.counts.length; i++)
            if (kana.set.counts[i] != kana.strokes[i].length) n++;
        return n;
    }
}
