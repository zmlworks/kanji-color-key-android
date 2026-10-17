package com.zmlworks.kanjicolorkey;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

/** Loads assets/pool.json and assets/kana.json once per process. */
public final class PoolStore {
    private PoolStore() {}
    private static volatile PoolData data;
    private static volatile KanaData kana;

    public static PoolData cached() { return data; }
    public static KanaData cachedKana() { return kana; }

    public static PoolData get(Context c) {
        PoolData d = data;
        if (d != null) return d;
        synchronized (PoolStore.class) {
            if (data == null) {
                try (InputStream in = c.getApplicationContext().getAssets().open("pool.json")) {
                    data = PoolData.read(in);
                } catch (IOException e) {
                    throw new IllegalStateException("pool.json", e);
                }
            }
            return data;
        }
    }

    public static KanaData kana(Context c) {
        KanaData d = kana;
        if (d != null) return d;
        synchronized (PoolStore.class) {
            if (kana == null) {
                try (InputStream in = c.getApplicationContext().getAssets().open("kana.json")) {
                    kana = KanaData.read(in);
                } catch (IOException e) {
                    throw new IllegalStateException("kana.json", e);
                }
            }
            return kana;
        }
    }
}
