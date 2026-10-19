package com.zmlworks.kanjicolorkey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.zmlworks.kanjicolorkey.keepass.KeePassFacade;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Collections;

/** The 0.4.3 restore search flow: exact → auto 1 stroke → (asked) 2 strokes, with estimates. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RestoreSearchTest {
    static Kck.Pool pool;
    static Kck.KanaSet kana;
    static final String HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @BeforeClass public static void load() throws Exception {
        try (FileInputStream in = new FileInputStream(RestoreFlowTest.asset("pool.json"))) { pool = PoolData.read(in).pool; }
        try (FileInputStream in = new FileInputStream(RestoreFlowTest.asset("kana.json"))) { kana = KanaData.read(in).set; }
    }

    static String encode(String hex, int check) {
        return Kck.encodeMixed(pool, Kck.hexToBits(hex), check,
                Collections.emptySet(), Collections.emptySet(), Kck.Tail.KANJI, kana).text;
    }

    /** Change the n-th color digit after the header (0-based) to a different color. */
    static String flip(String text, int nth) {
        StringBuilder sb = new StringBuilder(text);
        int k = 0;
        for (int i = text.indexOf(')') + 1; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c >= '0' && c <= '3') {
                if (k++ == nth) { sb.setCharAt(i, (char) ('0' + ((c - '0' + 1) % 4))); return sb.toString(); }
            }
        }
        throw new AssertionError("not enough digits");
    }

    static RestoreSearch.Verifier is(String hex) { return c -> c.hex.startsWith(hex); }

    @Test public void exact_findsKey() {
        RestoreSearch.Result r = RestoreSearch.run(pool, encode(HEX, 16), new Kck.Options(kana), 0, is(HEX), () -> false, null);
        assertEquals(1, r.matched.size());
        assertEquals(0, r.depth);
    }

    @Test public void oneWrongStroke_exactFails_depth1Finds() {
        String bad = flip(encode(HEX, 16), 3);
        Kck.Options o = new Kck.Options(kana);
        RestoreSearch.Result r0 = RestoreSearch.run(pool, bad, o, 0, is(HEX), () -> false, null);
        assertTrue(r0.matched.isEmpty());
        RestoreSearch.Result r1 = RestoreSearch.run(pool, bad, o, 1, is(HEX), () -> false, null);
        assertEquals(1, r1.matched.size());
        assertTrue(r1.matched.get(0).hex.startsWith(HEX));
    }

    @Test public void twoWrongStrokes_depth1Fails_estimate_depth2Finds() {
        String bad = flip(flip(encode(HEX, 16), 0), 2);
        Kck.Options o = new Kck.Options(kana);
        RestoreSearch.Result r1 = RestoreSearch.run(pool, bad, o, 1, is(HEX), () -> false, null);
        assertTrue(r1.matched.isEmpty());
        assertEquals(ColorMismatch.estimateVariantCount(bad, 1), r1.variants);
        RestoreSearch.Estimate e = RestoreSearch.estimateDepth2(bad, r1, true);
        assertTrue(e.variants > 0 && e.variants <= ColorMismatch.MAX_VARIANTS);
        assertTrue(e.variantsUncapped >= e.variants);
        assertTrue(e.seconds >= 0);
        assertFalse(RestoreSearch.humanDuration(e.seconds).isEmpty());
        RestoreSearch.Result r2 = RestoreSearch.run(pool, bad, o, 2, is(HEX), () -> false, null);
        assertEquals(1, r2.matched.size());
        assertEquals(2, r2.depth);
    }

    @Test public void cancel_stopsWithoutCrash() {
        String bad = flip(flip(encode(HEX, 16), 40), 41);
        RestoreSearch.Result r = RestoreSearch.run(pool, bad, new Kck.Options(kana), 2, is(HEX), () -> true, null);
        assertTrue(r.cancelled);
        assertTrue(r.matched.isEmpty());
    }

    @Test(expected = Kck.KckException.class)
    public void depth0_badText_throwsCleanly() {
        RestoreSearch.run(pool, "M(256bit)山0", new Kck.Options(kana), 0, null, () -> false, null);
    }

    @Test public void kdbxVerifier_depth1_fastKdf() throws Exception {
        KeePassFacade.OpenResult created = KeePassFacade.createDatabase(
                "SearchTest", "unit-test-pw", null, KeePassFacade.CipherChoice.AES, 1024L * 1024, 1, 1);
        byte[] db = KeePassFacade.encodeVerified(created.getDatabase());
        String hex = KeePassFacade.bytesToHex(created.getCompositeKey());
        String bad = flip(encode(hex, 16), 5);
        RestoreSearch.Verifier v = c -> {
            byte[] comp = Kck.bitsToBytes(Arrays.copyOf(c.bits, 256));
            try { KeePassFacade.openBytes(db, KeePassFacade.credentialsFromComposite(comp)); return true; }
            catch (Exception e) { return false; }
            finally { Arrays.fill(comp, (byte) 0); }
        };
        Kck.Options o = new Kck.Options(kana);
        assertTrue(RestoreSearch.run(pool, bad, o, 0, v, () -> false, null).matched.isEmpty());
        RestoreSearch.Result r1 = RestoreSearch.run(pool, bad, o, 1, v, () -> false, null);
        assertEquals(1, r1.matched.size());
        assertEquals(hex, r1.matched.get(0).hex.substring(0, 64));
        assertTrue(r1.verifications >= 1);
    }
}
