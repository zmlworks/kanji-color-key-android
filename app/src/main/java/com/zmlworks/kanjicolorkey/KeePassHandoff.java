package com.zmlworks.kanjicolorkey;

import android.net.Uri;

import java.util.Arrays;

/**
 * Process-local, memory-only handoff of .kdbx bytes / composite hex between KeePass and Restore.
 * Avoids relying on SAF URI permissions across activities (a common cause of "won't open").
 * Caller must wipe; take() clears the slot.
 */
public final class KeePassHandoff {
    private static byte[] kdbxBytes;
    private static String kdbxName;
    private static Uri kdbxUri;
    private static String compositeHex;

    private KeePassHandoff() {}

    public static synchronized void putKdbx(byte[] bytes, String name, Uri uri) {
        wipe(kdbxBytes);
        kdbxBytes = bytes == null ? null : bytes.clone();
        kdbxName = name;
        kdbxUri = uri;
    }

    public static synchronized void putCompositeHex(String hex) {
        compositeHex = hex;
    }

    /** Returns a copy of kdbx bytes and clears the slot. */
    public static synchronized byte[] takeKdbxBytes() {
        byte[] b = kdbxBytes;
        kdbxBytes = null;
        return b;
    }

    public static synchronized byte[] peekKdbxBytes() {
        return kdbxBytes == null ? null : kdbxBytes.clone();
    }

    public static synchronized String takeCompositeHex() {
        String h = compositeHex;
        compositeHex = null;
        return h;
    }

    public static synchronized String kdbxName() { return kdbxName; }
    public static synchronized Uri kdbxUri() { return kdbxUri; }

    public static synchronized void clear() {
        wipe(kdbxBytes);
        kdbxBytes = null;
        kdbxName = null;
        kdbxUri = null;
        compositeHex = null;
    }

    private static void wipe(byte[] b) {
        if (b != null) Arrays.fill(b, (byte) 0);
    }
}
