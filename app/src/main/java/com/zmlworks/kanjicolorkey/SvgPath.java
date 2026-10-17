package com.zmlworks.kanjicolorkey;

import android.graphics.Path;

/** SVG path data (KanjiVG uses M m C c S s; L l H h V v Z z are handled too) to android.graphics.Path. */
final class SvgPath {
    private SvgPath() {}

    /** Start point of the stroke ("M x,y"), where the web app puts the stroke number. */
    static float[] start(String d) {
        Tok t = new Tok(d);
        t.cmd();
        return new float[]{t.num(), t.num()};
    }

    static Path parse(String d) {
        Path p = new Path();
        Tok t = new Tok(d);
        float x = 0, y = 0, cx = 0, cy = 0, sx = 0, sy = 0;
        char cmd = 0, prev = 0;
        while (t.more()) {
            if (t.isCmd()) cmd = t.cmd();
            else if (cmd == 'M') cmd = 'L';
            else if (cmd == 'm') cmd = 'l';
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': x = ox + t.num(); y = oy + t.num(); p.moveTo(x, y); sx = x; sy = y; cx = x; cy = y; break;
                case 'L': x = ox + t.num(); y = oy + t.num(); p.lineTo(x, y); cx = x; cy = y; break;
                case 'H': x = ox + t.num(); p.lineTo(x, y); cx = x; cy = y; break;
                case 'V': y = oy + t.num(); p.lineTo(x, y); cx = x; cy = y; break;
                case 'C': {
                    float x1 = ox + t.num(), y1 = oy + t.num(), x2 = ox + t.num(), y2 = oy + t.num();
                    x = ox + t.num(); y = oy + t.num();
                    p.cubicTo(x1, y1, x2, y2, x, y); cx = x2; cy = y2; break;
                }
                case 'S': {
                    char pu = Character.toUpperCase(prev);
                    float x1 = (pu == 'C' || pu == 'S') ? 2 * x - cx : x, y1 = (pu == 'C' || pu == 'S') ? 2 * y - cy : y;
                    float x2 = ox + t.num(), y2 = oy + t.num();
                    x = ox + t.num(); y = oy + t.num();
                    p.cubicTo(x1, y1, x2, y2, x, y); cx = x2; cy = y2; break;
                }
                case 'Z': p.close(); x = sx; y = sy; cx = x; cy = y; break;
                default: return p; // unknown command: stop drawing this stroke
            }
            prev = cmd;
        }
        return p;
    }

    private static final class Tok {
        final String s; int i;
        Tok(String s) { this.s = s; }
        void skip() { while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\n' || s.charAt(i) == '\t')) i++; }
        boolean more() { skip(); return i < s.length(); }
        boolean isCmd() { skip(); return i < s.length() && Character.isLetter(s.charAt(i)) && s.charAt(i) != 'e' && s.charAt(i) != 'E'; }
        char cmd() { skip(); return s.charAt(i++); }
        float num() {
            skip();
            int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') i++;
                else if (c == '.' && !dot) { dot = true; i++; }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length()) { i++; if (s.charAt(i) == '-' || s.charAt(i) == '+') i++; }
                else break;
            }
            if (st == i) { i++; return 0; }
            return Float.parseFloat(s.substring(st, i));
        }
    }
}
