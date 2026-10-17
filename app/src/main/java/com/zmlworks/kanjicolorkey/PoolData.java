package com.zmlworks.kanjicolorkey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The bundled kanji pool (assets/pool.json, byte-identical to data/pool.json of the web app; derived from KanjiVG,
 * CC BY-SA 3.0): 4096 kanji with their stroke paths in stroke order. A tiny dedicated JSON reader keeps this
 * free of Android APIs so plain JVM tests can load the same file.
 */
public final class PoolData {
    public final String kanji;
    public final String[][] strokes;
    public final Kck.Pool pool;

    private PoolData(String kanji, String[][] strokes) {
        this.kanji = kanji;
        this.strokes = strokes;
        int[] counts = new int[strokes.length];
        for (int i = 0; i < counts.length; i++) counts[i] = strokes[i].length;
        this.pool = new Kck.Pool(kanji, counts);
    }

    public static PoolData read(InputStream in) throws IOException {
        return parse(readAll(in));
    }

    static PoolData parse(String json) {
        return new PoolData(readString(json, "kanji"), readStrokes(json));
    }

    /** The string value of a top-level key (the glyphs: "kanji" in pool.json, "kana" in kana.json). */
    static String readString(String json, String key) {
        Reader r = new Reader(json);
        r.at = keyPos(json, key);
        return r.string();
    }

    /** The "strokes" array: one array of SVG path strings per glyph, in stroke order. */
    static String[][] readStrokes(String json) {
        Reader r = new Reader(json);
        r.at = keyPos(json, "strokes");
        List<String[]> all = new ArrayList<>(4096);
        r.expect('[');
        if (!r.peekIs(']')) {
            do {
                r.expect('[');
                List<String> one = new ArrayList<>();
                if (!r.peekIs(']')) { do { one.add(r.string()); } while (r.comma()); }
                r.expect(']');
                all.add(one.toArray(new String[0]));
            } while (r.comma());
        }
        r.expect(']');
        return all.toArray(new String[0][]);
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
        return bo.toString(StandardCharsets.UTF_8.name());
    }

    private static int keyPos(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) throw new IllegalArgumentException("json: no " + key);
        k = json.indexOf(':', k);
        return k + 1;
    }

    private static final class Reader {
        final String s; int at;
        Reader(String s) { this.s = s; }
        void ws() { while (at < s.length() && Character.isWhitespace(s.charAt(at))) at++; }
        boolean peekIs(char c) { ws(); return at < s.length() && s.charAt(at) == c; }
        void expect(char c) { ws(); if (at >= s.length() || s.charAt(at) != c) throw new IllegalArgumentException("json: expected " + c + " at " + at); at++; }
        boolean comma() { ws(); if (at < s.length() && s.charAt(at) == ',') { at++; return true; } return false; }
        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            for (;;) {
                char c = s.charAt(at++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(at++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(at, at + 4), 16)); at += 4; break;
                    default: sb.append(e);
                }
            }
        }
    }
}
