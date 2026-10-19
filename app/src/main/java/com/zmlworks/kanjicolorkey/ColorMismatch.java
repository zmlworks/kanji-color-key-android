package com.zmlworks.kanjicolorkey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Error-tolerant color search: after an exact decode fails (or kdbx rejects all),
 * try variants that differ in 1 then 2 stroke colors. Combines with existing '?' expansion
 * inside {@link Kck#decodeAny}. Caps the number of variant strings tried.
 */
public final class ColorMismatch {
    /** Hard cap on variant strings (each may expand '?' further). */
    public static final int MAX_VARIANTS = 20_000;
    /** Prefer flipping these alternate colors first (uniform otherwise). Index = from-color. */
    // Mild preference: 黒↔青 (cool), 赤↔緑 (warm) as first try — unvalidated heuristic.
    private static final int[][] ALT_ORDER = {
            {2, 1, 3}, // from 黒: try 青, 赤, 緑
            {3, 0, 2}, // from 赤: try 緑, 黒, 青
            {0, 3, 1}, // from 青: try 黒, 緑, 赤
            {1, 2, 0}, // from 緑: try 赤, 青, 黒
    };

    public interface Progress {
        void onVariant(int tried, int total, int depth, int candidatesSoFar);
    }

    private ColorMismatch() {}

    /**
     * Decode with exact text first; if empty (or always if forceMismatch), try depths 1..maxDepth.
     * Stops early when {@code stopWhen} returns true (e.g. kdbx matched).
     */
    public static Kck.Decoded decode(
            Kck.Pool pool, String text, Kck.Options opts, int maxDepth,
            BooleanSupplier cancel, Progress progress, BooleanSupplier stopWhen) {
        if (maxDepth < 0) maxDepth = 0;
        if (maxDepth > 2) maxDepth = 2;
        Kck.Decoded best = Kck.decodeAny(pool, text, opts, cancel == null ? null : cancel::getAsBoolean);
        if (stopWhen != null && stopWhen.getAsBoolean()) return best;
        if (maxDepth == 0) return best;
        if (!best.candidates.isEmpty() && stopWhen == null) {
            // Exact produced candidates; caller may still want mismatches if kdbx rejects them —
            // those callers pass stopWhen tied to external match state. Here keep exact.
            return best;
        }
        List<Kck.Candidate> all = new ArrayList<>(best.candidates);
        long tried = best.tried;
        double total = best.total;
        String lookalike = best.lookalike;
        boolean fixed = best.fixed;
        long dataBits = best.dataBits, checkBits = best.checkBits;

        for (int depth = 1; depth <= maxDepth; depth++) {
            List<String> variants = variants(text, depth, MAX_VARIANTS);
            int vi = 0;
            for (String v : variants) {
                if (cancel != null && cancel.getAsBoolean()) break;
                vi++;
                if (progress != null) progress.onVariant(vi, variants.size(), depth, all.size());
                try {
                    Kck.Decoded d = Kck.decodeAny(pool, v, opts, cancel == null ? null : cancel::getAsBoolean);
                    tried += d.tried;
                    total += d.total;
                    for (Kck.Candidate c : d.candidates) {
                        boolean dup = false;
                        for (Kck.Candidate e : all) if (e.hex.equals(c.hex)) { dup = true; break; }
                        if (!dup) all.add(c);
                    }
                    if (lookalike == null) lookalike = d.lookalike;
                } catch (Kck.KckException ex) {
                    // too many combos / cancel — skip this variant
                    if ("cancelled".equals(ex.getMessage())) throw ex;
                }
                if (stopWhen != null && stopWhen.getAsBoolean()) {
                    return new Kck.Decoded(all, tried, total, dataBits, checkBits, lookalike, fixed);
                }
            }
            // If we found any at this depth and no stopWhen, still continue? Prefer return after first depth with hits
            if (!all.isEmpty() && stopWhen == null) {
                return new Kck.Decoded(all, tried, total, dataBits, checkBits, lookalike, fixed);
            }
        }
        return new Kck.Decoded(all, tried, total, dataBits, checkBits, lookalike, fixed);
    }

    /** Decode exact; if no candidates, search mismatches up to maxDepth. */
    public static Kck.Decoded decodeOrMismatch(
            Kck.Pool pool, String text, Kck.Options opts, int maxDepth, BooleanSupplier cancel) {
        Kck.Decoded exact = Kck.decodeAny(pool, text, opts, cancel == null ? null : cancel::getAsBoolean);
        if (!exact.candidates.isEmpty() || maxDepth <= 0) return exact;
        return decode(pool, text, opts, maxDepth, cancel, null, null);
    }

    /**
     * All strings at Hamming distance {@code depth} over color digits (0-3), leaving '?' alone.
     * Header is preserved. Caps at {@code limit}.
     */
    public static List<String> variants(String text, int depth, int limit) {
        List<String> out = new ArrayList<>();
        if (text == null || depth <= 0) return out;
        int bodyStart = text.indexOf(')');
        if (bodyStart < 0) bodyStart = 0; else bodyStart++;
        char[] chars = text.toCharArray();
        List<Integer> pos = new ArrayList<>();
        for (int i = bodyStart; i < chars.length; i++) {
            char c = chars[i];
            if (c >= '0' && c <= '3') pos.add(i);
        }
        int n = pos.size();
        if (depth == 1) {
            for (int pi = 0; pi < n && out.size() < limit; pi++) {
                int i = pos.get(pi);
                int cur = chars[i] - '0';
                for (int alt : ALT_ORDER[cur]) {
                    chars[i] = (char) ('0' + alt);
                    out.add(new String(chars));
                    if (out.size() >= limit) break;
                }
                chars[i] = (char) ('0' + cur);
            }
        } else if (depth == 2) {
            for (int a = 0; a < n && out.size() < limit; a++) {
                for (int b = a + 1; b < n && out.size() < limit; b++) {
                    int i = pos.get(a), j = pos.get(b);
                    int ci = chars[i] - '0', cj = chars[j] - '0';
                    for (int ai : ALT_ORDER[ci]) {
                        for (int aj : ALT_ORDER[cj]) {
                            chars[i] = (char) ('0' + ai);
                            chars[j] = (char) ('0' + aj);
                            out.add(new String(chars));
                            if (out.size() >= limit) break;
                        }
                        if (out.size() >= limit) break;
                    }
                    chars[i] = (char) ('0' + ci);
                    chars[j] = (char) ('0' + cj);
                }
            }
        }
        return out;
    }

    public static long estimateVariantCount(String text, int depth) {
        int bodyStart = text.indexOf(')');
        if (bodyStart < 0) bodyStart = 0; else bodyStart++;
        int n = 0;
        for (int i = bodyStart; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '3') n++;
        }
        if (depth == 1) return n * 3L;
        if (depth == 2) return n < 2 ? 0 : (n * (n - 1L) / 2L) * 9L;
        return 0;
    }
}
