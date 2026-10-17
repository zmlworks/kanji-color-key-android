package com.zmlworks.kanjicolorkey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader for the test vectors (plain JVM tests cannot use the android.jar org.json stubs). */
final class MiniJson {
    private final String s; private int at;
    private MiniJson(String s) { this.s = s; }
    static Object parse(String s) { MiniJson j = new MiniJson(s); Object v = j.value(); j.ws(); return v; }

    private void ws() { while (at < s.length() && " \t\r\n".indexOf(s.charAt(at)) >= 0) at++; }
    private Object value() {
        ws();
        char c = s.charAt(at);
        if (c == '{') {
            at++; Map<String, Object> m = new LinkedHashMap<>(); ws();
            if (s.charAt(at) == '}') { at++; return m; }
            for (;;) { ws(); String k = str(); ws(); at++; m.put(k, value()); ws(); if (s.charAt(at++) == '}') return m; }
        }
        if (c == '[') {
            at++; List<Object> l = new ArrayList<>(); ws();
            if (s.charAt(at) == ']') { at++; return l; }
            for (;;) { l.add(value()); ws(); if (s.charAt(at++) == ']') return l; }
        }
        if (c == '"') return str();
        if (s.startsWith("true", at)) { at += 4; return true; }
        if (s.startsWith("false", at)) { at += 5; return false; }
        if (s.startsWith("null", at)) { at += 4; return null; }
        int st = at;
        while (at < s.length() && "+-0123456789.eE".indexOf(s.charAt(at)) >= 0) at++;
        return Double.parseDouble(s.substring(st, at));
    }
    private String str() {
        at++; StringBuilder sb = new StringBuilder();
        for (;;) {
            char c = s.charAt(at++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(at++);
            switch (e) {
                case 'n': sb.append('\n'); break; case 't': sb.append('\t'); break; case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break; case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(at, at + 4), 16)); at += 4; break;
                default: sb.append(e);
            }
        }
    }
}
