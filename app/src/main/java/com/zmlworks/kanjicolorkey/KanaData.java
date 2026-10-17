package com.zmlworks.kanjicolorkey;

import java.io.IOException;
import java.io.InputStream;

/**
 * The bundled kana (assets/kana.json, byte-identical to data/kana.json of the web app; derived from KanjiVG,
 * CC BY-SA 3.0): the 32 kana of kana mode with their stroke paths in stroke order (also kept for stroke-order samples).
 */
public final class KanaData {
    public final String kana;
    public final String[][] strokes;
    public final Kck.KanaSet set;

    private KanaData(String kana, String[][] strokes) {
        this.kana = kana;
        this.strokes = strokes;
        int[] counts = new int[strokes.length];
        for (int i = 0; i < counts.length; i++) counts[i] = strokes[i].length;
        this.set = new Kck.KanaSet(kana, counts);
    }

    public static KanaData read(InputStream in) throws IOException {
        String json = PoolData.readAll(in);
        return new KanaData(PoolData.readString(json, "kana"), PoolData.readStrokes(json));
    }
}
