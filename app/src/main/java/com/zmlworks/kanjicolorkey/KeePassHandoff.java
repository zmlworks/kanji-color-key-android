package com.zmlworks.kanjicolorkey;

import android.net.Uri;

/** @deprecated Use {@link RamKeyStore}; kept as thin wrappers for older call sites. */
public final class KeePassHandoff {
    private KeePassHandoff() {}

    public static void putKdbx(byte[] bytes, String name, Uri uri) {
        RamKeyStore.get().setKdbx(bytes, name, uri);
    }

    public static void putCompositeHex(String hex) {
        try { RamKeyStore.get().setCompositeHex(hex); } catch (Exception ignored) {
            // keep old behaviour of storing even if invalid — store as decoded hex only
            RamKeyStore.get().setDecodedKeyHex(hex);
        }
    }

    public static byte[] takeKdbxBytes() {
        byte[] b = RamKeyStore.get().peekKdbx();
        // peek only — store keeps until Clear (RAM reuse across pages)
        return b;
    }

    public static byte[] peekKdbxBytes() { return RamKeyStore.get().peekKdbx(); }

    public static String takeCompositeHex() {
        return RamKeyStore.get().peekCompositeHex();
    }

    public static String kdbxName() { return RamKeyStore.get().kdbxName(); }
    public static Uri kdbxUri() { return RamKeyStore.get().kdbxUri(); }

    public static void clear() { RamKeyStore.get().clear(); }
}
