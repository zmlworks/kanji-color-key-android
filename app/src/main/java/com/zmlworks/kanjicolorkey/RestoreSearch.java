package com.zmlworks.kanjicolorkey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * One search pass of the paper restore at an exact mismatch depth (0 = as typed, 1 or 2 = that many strokes
 * with a different color). Pure Java so the flow (exact → auto 1 → ask for 2) is unit-testable.
 */
public final class RestoreSearch {
    private RestoreSearch() {}

    /** Accept or reject one decoded key (e.g. by trying to open the .kdbx). Null verifier = accept all. */
    public interface Verifier { boolean accept(Kck.Candidate c); }

    public interface Progress { void on(int depth, int variant, int variants, long verified, int matched); }

    /** Without a verifier, stop collecting after this many distinct keys. */
    public static final int MAX_UNVERIFIED = 200;

    public static final class Result {
        public final List<Kck.Candidate> matched = new ArrayList<>();
        public int depth;
        public int variants;
        public boolean truncated;   // variant list was capped at ColorMismatch.MAX_VARIANTS
        public boolean cancelled;
        public long decodeNanos, verifyNanos, verifications, candidatesSeen;
        public long tried; public double total;
        public long dataBits, checkBits; public String lookalike; public boolean fixed;

        public Kck.Decoded toDecoded() {
            return new Kck.Decoded(matched, tried, total, dataBits, checkBits, lookalike, fixed);
        }
        double secPerVariant() { return variants == 0 ? 0 : decodeNanos / 1e9 / variants; }
        double secPerVerify() { return verifications == 0 ? 0 : verifyNanos / 1e9 / verifications; }
        double candidatesPerVariant() { return variants == 0 ? 0 : (double) candidatesSeen / variants; }
    }

    /**
     * Runs depth {@code depth} only. Depth 0 errors (bad header, unknown char, too many '?') are thrown as
     * {@link Kck.KckException}; at depth ≥1 a variant that fails to decode is skipped.
     */
    public static Result run(Kck.Pool pool, String text, Kck.Options opts, int depth,
                             Verifier verifier, BooleanSupplier cancel, Progress progress) {
        Result r = new Result();
        r.depth = depth;
        List<String> texts;
        if (depth <= 0) { texts = new ArrayList<>(); texts.add(text); }
        else {
            texts = ColorMismatch.variants(text, depth, ColorMismatch.MAX_VARIANTS);
            r.truncated = ColorMismatch.estimateVariantCount(text, depth) > texts.size();
        }
        Set<String> seen = new HashSet<>();
        int vi = 0;
        for (String t : texts) {
            if (cancel != null && cancel.getAsBoolean()) { r.cancelled = true; break; }
            vi++;
            r.variants = vi;
            if (progress != null) progress.on(depth, vi, texts.size(), r.verifications, r.matched.size());
            long t0 = System.nanoTime();
            Kck.Decoded d;
            try {
                d = Kck.decodeAny(pool, t, opts, cancel == null ? null : cancel::getAsBoolean);
            } catch (Kck.KckException e) {
                r.decodeNanos += System.nanoTime() - t0;
                if ("cancelled".equals(e.getMessage())) { r.cancelled = true; break; }
                if (depth <= 0) throw e;
                continue;
            }
            r.decodeNanos += System.nanoTime() - t0;
            r.tried += d.tried; r.total += d.total;
            if (r.lookalike == null) r.lookalike = d.lookalike;
            r.dataBits = d.dataBits; r.checkBits = d.checkBits; r.fixed = d.fixed;
            for (Kck.Candidate c : d.candidates) {
                if (cancel != null && cancel.getAsBoolean()) { r.cancelled = true; break; }
                if (!seen.add(c.hex)) continue;
                r.candidatesSeen++;
                if (verifier == null) {
                    r.matched.add(c);
                    if (r.matched.size() >= MAX_UNVERIFIED) return r;
                    continue;
                }
                long v0 = System.nanoTime();
                boolean ok = verifier.accept(c);
                r.verifyNanos += System.nanoTime() - v0;
                r.verifications++;
                if (progress != null) progress.on(depth, vi, texts.size(), r.verifications, r.matched.size());
                if (ok) { r.matched.add(c); return r; }
            }
            if (r.cancelled) break;
        }
        return r;
    }

    /** Estimated cost of a depth-2 pass, from the measured depth-1 pass. */
    public static final class Estimate {
        public long variants;      // capped at MAX_VARIANTS
        public long variantsUncapped;
        public long verifications; // ≈ KDF runs when a .kdbx is attached
        public double seconds;
    }

    public static Estimate estimateDepth2(String text, Result depth1, boolean withVerifier) {
        Estimate e = new Estimate();
        e.variantsUncapped = ColorMismatch.estimateVariantCount(text, 2);
        e.variants = Math.min(e.variantsUncapped, ColorMismatch.MAX_VARIANTS);
        double perVar = depth1 != null ? depth1.secPerVariant() : 0.001;
        double cands = depth1 != null ? depth1.candidatesPerVariant() : 1;
        e.verifications = withVerifier ? Math.max(1, Math.round(e.variants * cands)) : 0;
        double perVerify = depth1 != null ? depth1.secPerVerify() : 1;
        e.seconds = e.variants * perVar + e.verifications * perVerify;
        return e;
    }

    /** "約 12 秒" / "約 3 分" / "約 2.5 時間". */
    public static String humanDuration(double s) {
        if (s < 1) return "1 秒未満";
        if (s < 90) return Math.round(s) + " 秒";
        if (s < 90 * 60) return Math.round(s / 60) + " 分";
        return String.format(java.util.Locale.ROOT, "%.1f 時間", s / 3600);
    }
}
