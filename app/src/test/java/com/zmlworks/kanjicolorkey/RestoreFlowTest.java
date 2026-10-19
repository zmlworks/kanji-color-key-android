package com.zmlworks.kanjicolorkey;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zmlworks.kanjicolorkey.keepass.KeePassFacade;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Flick mapping, KanjiVG↔encoder stroke audit, encode→flick→decode (+ '?' brute force),
 * and KeePass composite verify with an in-test fast-KDF .kdbx (never committed).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RestoreFlowTest {
    static PoolData poolData;
    static KanaData kanaData;
    static Kck.Pool pool;
    static Kck.KanaSet kana;

    @BeforeClass public static void load() throws Exception {
        try (FileInputStream in = new FileInputStream(asset("pool.json"))) {
            poolData = PoolData.read(in);
        }
        try (FileInputStream in = new FileInputStream(asset("kana.json"))) {
            kanaData = KanaData.read(in);
        }
        pool = poolData.pool;
        kana = kanaData.set;
    }

    static File asset(String name) {
        File f = new File("app/src/main/assets/" + name);
        return f.exists() ? f : new File("src/main/assets/" + name);
    }

    @Test public void flickDirectionToColor_diamond() {
        float t = 36f;
        assertEquals(-1, FlickColorView.directionToColor(0, 0, t));
        assertEquals(-1, FlickColorView.directionToColor(10, 10, t));
        assertEquals(0, FlickColorView.directionToColor(0, -50, t));   // up 黒
        assertEquals(1, FlickColorView.directionToColor(50, 0, t));    // right 赤
        assertEquals(2, FlickColorView.directionToColor(-50, 0, t));   // left 青
        assertEquals(3, FlickColorView.directionToColor(0, 50, t));    // down 緑
        // dominant axis
        assertEquals(1, FlickColorView.directionToColor(60, 20, t));
        assertEquals(0, FlickColorView.directionToColor(20, -60, t));
    }

    @Test public void encoderStrokeCountsMatchKanjiVgPaths_allChars() {
        assertEquals(0, GlyphStrokes.countMismatches(poolData, kanaData));
        assertEquals(4096, pool.chars.length);
        assertEquals(32, kana.chars.length);
        for (int i = 0; i < pool.chars.length; i++) {
            GlyphStrokes g = GlyphStrokes.resolve(poolData, kanaData, pool.chars[i], false);
            assertEquals(GlyphStrokes.Kind.KANJI, g.kind);
            assertEquals(pool.counts[i], g.colorSlots);
            assertEquals(pool.counts[i], g.strokeCount);
        }
        for (int i = 0; i < kana.chars.length; i++) {
            GlyphStrokes g = GlyphStrokes.resolve(poolData, kanaData, kana.chars[i], false);
            assertEquals(GlyphStrokes.Kind.KANA, g.kind);
            assertEquals(kana.counts[i], g.colorSlots);
            GlyphStrokes kf = GlyphStrokes.resolve(poolData, kanaData, kana.chars[i], true);
            assertEquals(3, kf.colorSlots);
            assertEquals(kana.counts[i], kf.strokeCount);
        }
        for (int i = 0; i < Kck.ALPHA.length(); i++) {
            String ch = Kck.ALPHA.substring(i, i + 1);
            GlyphStrokes g = GlyphStrokes.resolve(poolData, kanaData, ch, false);
            assertEquals(GlyphStrokes.Kind.ALPHA, g.kind);
            assertEquals(1, g.colorSlots);
        }
    }

    @Test public void encodeFlickDecode_roundTrip_mixedAndQuestion() {
        byte[] bits = Kck.hexToBits("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        Kck.Encoded enc = Kck.encodeMixed(pool, bits, 0,
                Collections.emptySet(), Collections.emptySet(), Kck.Tail.KANJI, kana);
        // Build decode text from tokens (simulates perfect flick input)
        String perfect = enc.text;
        // Also build via "flick colors" list (simulates RestoreActivity body)
        StringBuilder body = new StringBuilder();
        for (Kck.Token tok : enc.tokens) {
            GlyphStrokes g = GlyphStrokes.resolve(poolData, kanaData, tok.ch, false);
            assertEquals(tok.colors.length, g.colorSlots);
            body.append(tok.ch);
            for (int c : tok.colors) body.append(c);
        }
        String rebuilt = "M(256bit)" + body;
        Kck.Options opts = new Kck.Options(kana);
        Kck.Decoded d1 = Kck.decodeAny(pool, perfect, opts, () -> false);
        Kck.Decoded d2 = Kck.decodeAny(pool, rebuilt, opts, () -> false);
        assertFalse(d1.candidates.isEmpty());
        assertEquals(d1.candidates.get(0).hex, d2.candidates.get(0).hex);
        assertTrue(d1.candidates.get(0).hex.startsWith("0123456789abcdef0123456789abcdef"));

        // Replace two stroke colors with '?' and brute-force
        String withQ = introduceQuestions(rebuilt, 2);
        assertTrue(withQ.contains("?"));
        Kck.Decoded d3 = Kck.decodeAny(pool, withQ, opts, () -> false);
        boolean found = false;
        for (Kck.Candidate c : d3.candidates) {
            if (c.hex.equals(d1.candidates.get(0).hex)) { found = true; break; }
        }
        assertTrue("exact key among ? expansions", found);
    }

    /** Flip the first `n` color digits after the header into '?'. */
    static String introduceQuestions(String text, int n) {
        int start = text.indexOf(')');
        if (start < 0) start = 0; else start++;
        StringBuilder sb = new StringBuilder(text);
        int flipped = 0;
        for (int i = start; i < sb.length() && flipped < n; i++) {
            char ch = sb.charAt(i);
            if (ch >= '0' && ch <= '3') {
                sb.setCharAt(i, '?');
                flipped++;
            }
        }
        return sb.toString();
    }

    @Test public void keepassComposite_fastKdf_verifyCandidate() throws Exception {
        // Fast Argon2 for unit tests (not the production 64MiB default).
        KeePassFacade.OpenResult created = KeePassFacade.createDatabase(
                "RestoreTest", "unit-test-pw", null, KeePassFacade.CipherChoice.AES,
                1024L * 1024, 1, 1);
        byte[] db = KeePassFacade.encodeVerified(created.getDatabase());
        byte[] composite = created.getCompositeKey().clone();
        String hex = KeePassFacade.bytesToHex(composite);

        // Encode composite as kanji-color-key
        byte[] bits = Kck.hexToBits(hex);
        Kck.Encoded enc = Kck.encodeMixed(pool, bits, 0,
                Collections.emptySet(), Collections.emptySet(), Kck.Tail.KANJI, kana);
        String withQ = introduceQuestions(enc.text, 1);
        Kck.Decoded d = Kck.decodeAny(pool, withQ, new Kck.Options(kana), () -> false);
        assertFalse(d.candidates.isEmpty());

        int matched = 0;
        for (Kck.Candidate c : d.candidates) {
            if (c.bits.length < 256) continue;
            byte[] keyBits = Arrays.copyOf(c.bits, 256);
            byte[] tryComp = Kck.bitsToBytes(keyBits);
            try {
                KeePassFacade.openBytes(db, KeePassFacade.credentialsFromComposite(tryComp));
                matched++;
                assertArrayEquals(composite, tryComp);
            } catch (Exception ignored) {
            } finally {
                Arrays.fill(tryComp, (byte) 0);
                Arrays.fill(keyBits, (byte) 0);
            }
        }
        assertEquals(1, matched);
        Arrays.fill(composite, (byte) 0);
        Arrays.fill(db, (byte) 0);
    }
}
