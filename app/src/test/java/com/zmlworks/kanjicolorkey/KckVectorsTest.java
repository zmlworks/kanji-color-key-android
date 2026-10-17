package com.zmlworks.kanjicolorkey;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parity of the Java core with the web app's JS core (src/kck.js v1.1.0): every vector in vectors.json was produced
 * by node from the JS (tools/gen-vectors.mjs), from random keys in every mode, plus v1.0 zero-padded outputs.
 */
@SuppressWarnings("unchecked")
public class KckVectorsTest {
    static Map<String, Object> V;
    static PoolData P;
    static Kck.Pool pool;

    @BeforeClass public static void load() throws Exception {
        try (InputStream in = KckVectorsTest.class.getResourceAsStream("/vectors.json")) {
            V = (Map<String, Object>) MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = new FileInputStream(poolFile())) { P = PoolData.read(in); }
        pool = P.pool;
    }

    static File poolFile() {
        File f = new File("src/main/assets/pool.json");
        return f.exists() ? f : new File("app/src/main/assets/pool.json");
    }

    static List<Map<String, Object>> list(String k) { return (List<Map<String, Object>>) V.get(k); }
    static String s(Map<String, Object> m, String k) { return (String) m.get(k); }
    static int i(Map<String, Object> m, String k) { return (int) Math.round((Double) m.get(k)); }
    static double d(Map<String, Object> m, String k) { return (Double) m.get(k); }
    static byte[] bits(String s) { byte[] b = new byte[s.length()]; for (int k = 0; k < b.length; k++) b[k] = (byte) (s.charAt(k) - '0'); return b; }
    static String str(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(x); return sb.toString(); }
    static Set<Integer> posSet(Map<String, Object> m) {
        Set<Integer> s = new HashSet<>();
        for (Object o : (List<Object>) m.get("alphaPos")) s.add((int) Math.round((Double) o));
        return s;
    }

    @Test public void poolAssetIsTheWebAppsPoolJson() throws Exception {
        byte[] raw = Files.readAllBytes(poolFile().toPath());
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(raw)) hex.append(String.format("%02x", b));
        Map<String, Object> meta = (Map<String, Object>) V.get("meta");
        assertEquals(meta.get("poolSha256"), hex.toString());
        assertEquals("1.1.0", meta.get("webVersion"));
        assertEquals(4096, P.strokes.length);
        assertEquals(10, pool.counts[pool.indexOf("従")]);
    }

    @Test public void sha256KnownVector() {
        StringBuilder hex = new StringBuilder();
        for (byte b : Kck.sha256("abc".getBytes(StandardCharsets.US_ASCII))) hex.append(String.format("%02x", b));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex.toString());
    }

    @Test public void checkCodeAndSurplus() {
        for (Map<String, Object> v : list("hash")) {
            byte[] b = bits(s(v, "bits"));
            assertEquals(s(v, "check"), str(Kck.checkCode(b, i(v, "checkBits"))));
            assertEquals(s(v, "surplus"), str(Kck.surplusBits(b, 700)));
        }
    }

    @Test public void hexAndBitStrings() {
        for (Map<String, Object> v : list("hex")) {
            try { assertEquals(s(v, "in"), s(v, "bits"), str(Kck.hexToBits(s(v, "in")))); assertNull(v.get("error")); }
            catch (Kck.KckException e) { assertEquals(s(v, "in"), s(v, "error"), e.getMessage()); }
        }
        for (Map<String, Object> v : list("bitString")) {
            try { assertEquals(s(v, "bits"), str(Kck.parseBitString(s(v, "in")))); assertNull(v.get("error")); }
            catch (Kck.KckException e) { assertEquals(s(v, "error"), e.getMessage()); }
        }
    }

    @Test public void expandColorSpec() {
        for (Map<String, Object> v : list("spec")) {
            try {
                List<int[]> got = Kck.expandColorSpec(s(v, "in"));
                List<Object> want = (List<Object>) v.get("out");
                assertTrue(s(v, "in") + " threw in JS", want != null);
                assertEquals(s(v, "in"), want.size(), got.size());
                for (int k = 0; k < got.size(); k++) {
                    StringBuilder sb = new StringBuilder(); for (int c : got.get(k)) sb.append(c);
                    assertEquals(s(v, "in"), want.get(k), sb.toString());
                }
            } catch (Kck.KckException e) { assertEquals(s(v, "in"), s(v, "error"), e.getMessage()); }
        }
    }

    static Kck.Encoded encode(Map<String, Object> v) {
        byte[] b = bits(s(v, "bits"));
        int c = i(v, "checkBits");
        switch (s(v, "mode")) {
            case "kanji": return Kck.encodeBits(pool, b, c);
            case "alpha": return Kck.encodeAlpha(b, c);
            case "mixedAllAlpha": { Set<Integer> all = new HashSet<>(); for (int k = 1; k <= 80; k++) all.add(k); return Kck.encodeMixed(pool, b, c, all, false); }
            case "mixedA": return Kck.encodeMixed(pool, b, c, posSet(v), true);
            default: return Kck.encodeMixed(pool, b, c, posSet(v), false);
        }
    }

    @Test public void encodeEveryMode() {
        int n = 0;
        for (Map<String, Object> v : list("encode")) {
            Kck.Encoded e = encode(v);
            String id = s(v, "mode") + " " + s(v, "bits").length() + "+" + i(v, "checkBits");
            assertEquals(id, s(v, "text"), e.text);
            assertEquals(id, i(v, "totalBits"), e.totalBits);
            assertEquals(id, i(v, "writtenBits"), e.writtenBits);
            assertEquals(id, i(v, "surplus"), e.surplus);
            n++;
        }
        assertEquals(1200, n);
    }

    @Test public void webTwoEndingsAndCommonPrefix() {
        for (Map<String, Object> v : list("web")) {
            WebModel.Result r = WebModel.render(pool, bits(s(v, "bits")), i(v, "checkBits"), posSet(v));
            assertEquals(s(v, "a"), r.a.text);
            assertEquals(s(v, "b"), r.b.text);
            assertEquals(i(v, "common"), r.common);
            assertEquals(s(v, "infoA"), WebModel.info(r.a));
            assertEquals(s(v, "infoB"), WebModel.info(r.b));
            assertEquals(s(v, "head"), WebModel.head(r));
        }
    }

    @Test public void decodeMatchesJs() {
        int n = 0, multi = 0;
        for (Map<String, Object> v : list("decode")) {
            String in = s(v, "in");
            Kck.Decoded d;
            try { d = Kck.decodeAny(pool, in); } catch (Kck.KckException e) { fail(s(v, "note") + ": " + in + " -> " + e.getMessage()); return; }
            assertEquals(in, i(v, "tried"), d.tried);
            assertEquals(in, d(v, "total"), d.total, 0);
            assertEquals(in, i(v, "dataBits"), d.dataBits);
            assertEquals(in, i(v, "checkBits"), d.checkBits);
            assertEquals(in, i(v, "count"), d.candidates.size());
            List<Map<String, Object>> want = (List<Map<String, Object>>) v.get("candidates");
            for (int k = 0; k < want.size(); k++) {
                assertEquals(in, s(want.get(k), "hex"), d.candidates.get(k).hex);
                assertEquals(in, s(want.get(k), "text"), d.candidates.get(k).text);
                assertEquals(in, s(want.get(k), "padding"), d.candidates.get(k).padding);
            }
            if (d.candidates.size() > 1) multi++;
            n++;
        }
        assertTrue(n > 5000);
        assertTrue(multi > 0);
    }

    @Test public void errorsMatchJs() {
        for (Map<String, Object> v : list("errors")) {
            try { Kck.decodeAny(pool, s(v, "in")); fail("no error for " + s(v, "in")); }
            catch (Kck.KckException e) { assertEquals(s(v, "in"), s(v, "error"), e.getMessage()); }
        }
    }

    @Test public void passphraseKdf() {
        for (Map<String, Object> v : list("kdf")) {
            byte[] k = Kck.deriveKey(s(v, "pass"), s(v, "salt"), i(v, "iterations"));
            assertEquals(s(v, "pass"), s(v, "hex"), Kck.bitsToHex(Kck.bytesToBits(k)));
        }
    }

    @Test public void webDemo() {
        Map<String, Object> demo = (Map<String, Object>) V.get("demo");
        WebModel.Demo dm = WebModel.demo(pool);
        assertEquals(demo.get("a"), dm.result.a.text);
        assertEquals(demo.get("input"), dm.decodeInput);
        Kck.Decoded d = Kck.decodeAny(pool, dm.decodeInput);
        assertEquals(i(demo, "tried"), d.tried);
        List<Object> want = (List<Object>) demo.get("candidates");
        assertEquals(want.size(), d.candidates.size());
        for (int k = 0; k < want.size(); k++) assertEquals(want.get(k), d.candidates.get(k).hex);
    }

    @Test public void randomRoundTripsAndSecureRandom() {
        byte[] a = Kck.randomBits(512), b = Kck.randomBits(512);
        assertEquals(512, a.length);
        assertTrue(!java.util.Arrays.equals(a, b));
        for (int r = 0; r < 40; r++) {
            byte[] k = Kck.randomBits(256);
            Kck.Encoded e = Kck.encodeMixed(pool, k, 12, Set.of(2, 5), r % 2 == 0);
            assertArrayEquals(k, Kck.decodeAny(pool, e.text).candidates.get(0).bits);
        }
    }

    @Test public void hugeUncertaintyIsRefused() {
        try { Kck.decodeAny(pool, "A(256bit)" + "??".repeat(37)); fail(); }
        catch (Kck.KckException e) { assertTrue(e.getMessage().startsWith("too many combinations")); }
    }
}
