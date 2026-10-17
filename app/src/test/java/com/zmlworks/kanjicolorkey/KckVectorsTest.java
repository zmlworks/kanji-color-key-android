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
import java.util.TreeMap;

/**
 * Parity of the Java core with the web app's JS core (src/kck.js v1.2.0): every vector in vectors.json was produced
 * by node from the JS (tools/gen-vectors.mjs), from random keys in every mode (incl. kana, all-kana and KF), the web
 * UI model (endings, type rotation), look-alike retries, plus v1.0 zero-padded and v1.1 outputs.
 */
@SuppressWarnings("unchecked")
public class KckVectorsTest {
    static Map<String, Object> V;
    static PoolData P;
    static Kck.Pool pool;
    static KanaData KD;
    static Kck.KanaSet kana;

    @BeforeClass public static void load() throws Exception {
        try (InputStream in = KckVectorsTest.class.getResourceAsStream("/vectors.json")) {
            V = (Map<String, Object>) MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (InputStream in = new FileInputStream(poolFile())) { P = PoolData.read(in); }
        pool = P.pool;
        try (InputStream in = new FileInputStream(asset("kana.json"))) { KD = KanaData.read(in); }
        kana = KD.set;
    }

    static File poolFile() { return asset("pool.json"); }

    static File asset(String name) {
        File f = new File("src/main/assets/" + name);
        return f.exists() ? f : new File("app/src/main/assets/" + name);
    }

    static String sha256Hex(byte[] raw) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(raw)) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    static Set<Integer> ints(Map<String, Object> m, String k) {
        Set<Integer> s = new HashSet<>();
        Object o = m.get(k);
        if (o != null) for (Object x : (List<Object>) o) s.add((int) Math.round((Double) x));
        return s;
    }

    static Map<Integer, Kck.Type> types(Object o) {
        Map<Integer, Kck.Type> t = new TreeMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet())
            t.put(Integer.parseInt(e.getKey()), "alpha".equals(e.getValue()) ? Kck.Type.ALPHA : Kck.Type.KANA);
        return t;
    }

    static WebModel.Enabled enabled(Object o) {
        Map<String, Object> m = (Map<String, Object>) o;
        return new WebModel.Enabled((Boolean) m.get("alpha"), (Boolean) m.get("kana"));
    }

    static Kck.Options opts(Map<String, Object> v) {
        Kck.Options o = new Kck.Options(kana);
        Map<String, Object> m = (Map<String, Object>) v.get("opts");
        if (m == null) return o;
        if (m.get("swapLookalikes") != null) o.swapLookalikes = (Boolean) m.get("swapLookalikes");
        if (m.get("lookalikeFallback") != null) o.lookalikeFallback = (Boolean) m.get("lookalikeFallback");
        if (m.get("maxCandidates") != null) o.maxCandidates = i(m, "maxCandidates");
        return o;
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
        Map<String, Object> meta = (Map<String, Object>) V.get("meta");
        assertEquals(meta.get("poolSha256"), sha256Hex(Files.readAllBytes(poolFile().toPath())));
        assertEquals("1.2.0", meta.get("webVersion"));
        assertEquals(4096, P.strokes.length);
        assertEquals(10, pool.counts[pool.indexOf("従")]);
    }

    @Test public void kanaAssetIsTheWebAppsKanaJson() throws Exception {
        Map<String, Object> meta = (Map<String, Object>) V.get("meta");
        assertEquals(meta.get("kanaSha256"), sha256Hex(Files.readAllBytes(asset("kana.json").toPath())));
        assertEquals(Kck.KANA, KD.kana);
        assertEquals(32, KD.strokes.length);
        assertEquals(32, Kck.KANA.codePointCount(0, Kck.KANA.length()));
        for (int k = 0; k < 32; k++) assertTrue(kana.counts[k] == 3 || kana.counts[k] == 4);
        assertEquals("ヰ", kana.chars[31]);
        assertEquals(4, kana.counts[kana.indexOf("ヰ")]);
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
            case "mixedKana": case "allKana":
                return Kck.encodeMixed(pool, b, c, ints(v, "alphaPos"), ints(v, "kanaPos"), Kck.Tail.valueOf(s(v, "tail").toUpperCase(java.util.Locale.ROOT)), kana);
            case "kanaFixed": return Kck.encodeKanaFixed(kana, b, c);
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
        assertEquals(1920, n);
    }

    @Test public void webEndingsCommonPrefixAndKf() {
        int n = 0, fixed = 0, three = 0;
        for (Map<String, Object> v : list("web")) {
            WebModel.Result r = WebModel.render(pool, kana, bits(s(v, "bits")), i(v, "checkBits"), types(v.get("types")), enabled(v.get("enabled")), (Boolean) v.get("fixed"));
            List<Map<String, Object>> want = (List<Map<String, Object>>) v.get("variants");
            assertEquals(s(v, "bits"), want.size(), r.variants.size());
            for (int k = 0; k < want.size(); k++) {
                WebModel.Variant got = r.variants.get(k);
                assertEquals(s(want.get(k), "tail"), got.tailName());
                assertEquals(s(want.get(k), "text"), got.enc.text);
                assertEquals(s(want.get(k), "info"), WebModel.info(got.enc));
                assertEquals(s(want.get(k), "title"), WebModel.tailTitle(got));
            }
            assertEquals(i(v, "common"), r.common);
            assertEquals(v.get("isFixed"), r.fixed);
            assertEquals(s(v, "head"), WebModel.head(r));
            if (r.fixed) fixed++;
            if (r.variants.size() == 3) three++;
            n++;
        }
        assertEquals(240, n);
        assertTrue(fixed > 0);
        assertTrue(three > 0);
    }

    @Test public void typeRotationAndToggles() {
        Kck.Type[] rot = {Kck.Type.KANJI, Kck.Type.ALPHA, Kck.Type.KANA};
        for (Map<String, Object> v : list("rotation")) {
            WebModel.Enabled en = enabled(v.get("enabled"));
            List<Object> next = (List<Object>) v.get("next");
            for (int k = 0; k < 3; k++) assertEquals(next.get(k), WebModel.nextType(rot[k], en).name().toLowerCase(java.util.Locale.ROOT));
            Map<Integer, Kck.Type> t = types(v.get("init"));
            for (Map<String, Object> st : (List<Map<String, Object>>) v.get("steps")) {
                if ("keep".equals(st.get("op"))) t = WebModel.keepEnabled(t, enabled(st.get("enabled")));
                else t = WebModel.clickType(t, i(st, "pos"), en);
                assertEquals(types(st.get("types")), t);
            }
            assertEquals(v.get("allKana"), WebModel.isAllOf(t, Kck.Type.KANA));
            assertEquals(v.get("allAlpha"), WebModel.isAllOf(t, Kck.Type.ALPHA));
        }
        assertTrue(WebModel.isAllOf(WebModel.allOf(Kck.Type.KANA), Kck.Type.KANA));
        assertTrue(WebModel.allOf(Kck.Type.KANJI).isEmpty());
    }

    @Test public void decodeMatchesJs() {
        int n = 0, multi = 0;
        Set<String> notes = new HashSet<>();
        for (Map<String, Object> v : list("decode")) {
            String in = s(v, "in");
            Kck.Decoded d;
            try { d = Kck.decodeAny(pool, in, opts(v), null); } catch (Kck.KckException e) { fail(s(v, "note") + ": " + in + " -> " + e.getMessage()); return; }
            assertEquals(in, i(v, "tried"), d.tried);
            assertEquals(in, d(v, "total"), d.total, 0);
            assertEquals(in, i(v, "dataBits"), d.dataBits);
            assertEquals(in, i(v, "checkBits"), d.checkBits);
            assertEquals(in, i(v, "count"), d.candidates.size());
            assertEquals(in, v.get("lookalike"), d.lookalike);
            String note = s(v, "note");
            notes.add(note.startsWith("v1.") ? note.substring(0, 4) : note.replaceAll("^\\S+ ", ""));
            List<Map<String, Object>> want = (List<Map<String, Object>>) v.get("candidates");
            for (int k = 0; k < want.size(); k++) {
                assertEquals(in, s(want.get(k), "hex"), d.candidates.get(k).hex);
                assertEquals(in, s(want.get(k), "text"), d.candidates.get(k).text);
                assertEquals(in, s(want.get(k), "padding"), d.candidates.get(k).padding);
            }
            if (d.candidates.size() > 1) multi++;
            n++;
        }
        assertTrue(n > 7000);
        assertTrue(multi > 0);
        for (String must : new String[]{"v1.0", "v1.1", "halfwidth", "glyph strokes", "lookalike fallback", "lookalike always", "lookalike off",
                "lookalike skipped", "K( alias", "kana ?"}) assertTrue(must, notes.contains(must));
    }

    @Test public void errorsMatchJs() {
        for (Map<String, Object> v : list("errors")) {
            try { Kck.decodeAny(pool, s(v, "in"), opts(v), null); fail("no error for " + s(v, "in")); }
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
        WebModel.Demo dm = WebModel.demo(pool, kana);
        assertEquals(demo.get("a"), dm.result.first().text);
        List<Object> vs = (List<Object>) demo.get("variants");
        assertEquals(vs.size(), dm.result.variants.size());
        for (int k = 0; k < vs.size(); k++) assertEquals(vs.get(k), dm.result.variants.get(k).enc.text);
        assertEquals(i(demo, "common"), dm.result.common);
        assertEquals(demo.get("input"), dm.decodeInput);
        Kck.Decoded d = Kck.decodeAny(pool, dm.decodeInput, new Kck.Options(kana), null);
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
            Kck.Tail tail = Kck.Tail.values()[r % 3];
            Kck.Encoded m = Kck.encodeMixed(pool, k, 12, Set.of(3), Set.of(1, 4, 6), tail, kana);
            assertArrayEquals(k, Kck.decodeAny(pool, m.text, new Kck.Options(kana), null).candidates.get(0).bits);
            Kck.Encoded f = Kck.encodeKanaFixed(kana, k, 12);
            assertEquals(25, f.tokens.size());
            assertArrayEquals(k, Kck.decodeAny(pool, f.text, new Kck.Options(kana), null).candidates.get(0).bits);
        }
        assertEquals(24, Kck.encodeKanaFixed(kana, Kck.randomBits(256), 0).tokens.size());
    }

    @Test public void kanaNeedsTheKanaSet() {
        try { Kck.encodeMixed(pool, Kck.randomBits(64), 0, Set.of(), Set.of(1), Kck.Tail.KANJI, null); fail(); }
        catch (Kck.KckException e) { assertEquals("kana set required", e.getMessage()); }
        try { Kck.decodeAny(pool, "KF(11bit)あ012"); fail(); }
        catch (Kck.KckException e) { assertEquals("kana set required", e.getMessage()); }
        try { Kck.decodeAny(pool, "M(11bit)あ012"); fail(); }   // without the kana set, as a v1.1 decoder
        catch (Kck.KckException e) { assertEquals("\"あ\" is not in the kanji pool", e.getMessage()); }
    }

    @Test public void hugeUncertaintyIsRefused() {
        try { Kck.decodeAny(pool, "A(256bit)" + "??".repeat(37)); fail(); }
        catch (Kck.KckException e) { assertTrue(e.getMessage().startsWith("too many combinations")); }
    }
}
