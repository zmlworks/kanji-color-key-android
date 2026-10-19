package com.zmlworks.kanjicolorkey;

import android.net.Uri;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Process-local RAM-only store for secrets and restore drafts.
 * Never written to disk / SharedPreferences / SavedInstanceState.
 * Cleared on explicit Clear, Quit, or wipe-on-background.
 */
public final class RamKeyStore {
    public enum Slot {
        MASTER_PASSWORD,
        KEYFILE,
        COMPOSITE,
        DECODED_KEY,
        KDBX,
        RESTORE_DRAFT
    }

    /** One character block in the paper-restore draft. */
    public static final class RestoreBlock {
        public String ch = "";
        /** 0..3 or -1 for ?; length = colorSlots when filled. */
        public int[] colors = new int[0];
        public boolean kf;

        public RestoreBlock copy() {
            RestoreBlock b = new RestoreBlock();
            b.ch = ch;
            b.colors = colors != null ? colors.clone() : new int[0];
            b.kf = kf;
            return b;
        }
    }

    private static final RamKeyStore I = new RamKeyStore();

    private char[] masterPassword;
    private byte[] keyfileBytes;
    private String keyfileName;
    private byte[] compositeKey;      // 32 bytes
    private byte[] decodedKeyBits;    // 256 bits as 0/1 bytes, or we store hex string
    private String decodedKeyHex;
    private byte[] kdbxBytes;
    private String kdbxName;
    private Uri kdbxUri;
    private final List<RestoreBlock> restoreDraft = new ArrayList<>();
    private int restoreDataBits = 256;
    private int restoreCheckBits = 0;
    private boolean restoreKf;
    private int restoreMismatchDepth;

    private RamKeyStore() {}

    public static RamKeyStore get() { return I; }

    // ----- password -----
    public synchronized void setMasterPassword(String pw) {
        wipeChars(masterPassword);
        masterPassword = pw == null || pw.isEmpty() ? null : pw.toCharArray();
    }

    public synchronized String peekMasterPassword() {
        return masterPassword == null ? null : new String(masterPassword);
    }

    public synchronized boolean hasMasterPassword() { return masterPassword != null && masterPassword.length > 0; }

    // ----- keyfile -----
    public synchronized void setKeyfile(byte[] bytes, String name) {
        wipe(keyfileBytes);
        keyfileBytes = bytes == null ? null : bytes.clone();
        keyfileName = name;
    }

    public synchronized byte[] peekKeyfile() { return keyfileBytes == null ? null : keyfileBytes.clone(); }
    public synchronized String keyfileName() { return keyfileName; }
    public synchronized boolean hasKeyfile() { return keyfileBytes != null; }

    // ----- composite -----
    public synchronized void setComposite(byte[] key32) {
        wipe(compositeKey);
        if (key32 == null) { compositeKey = null; return; }
        if (key32.length != 32) throw new IllegalArgumentException("composite must be 32 bytes");
        compositeKey = key32.clone();
    }

    public synchronized void setCompositeHex(String hex) {
        if (hex == null || hex.isEmpty()) { setComposite(null); return; }
        String h = hex.replaceAll("\\s+", "").toLowerCase();
        if (h.length() != 64) throw new IllegalArgumentException("need 64 hex chars");
        byte[] b = new byte[32];
        for (int i = 0; i < 32; i++)
            b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        setComposite(b);
        wipe(b);
    }

    public synchronized byte[] peekComposite() { return compositeKey == null ? null : compositeKey.clone(); }

    public synchronized String peekCompositeHex() {
        if (compositeKey == null) return null;
        StringBuilder sb = new StringBuilder(64);
        for (byte v : compositeKey) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    public synchronized boolean hasComposite() { return compositeKey != null; }

    // ----- decoded key from restore -----
    public synchronized void setDecodedKeyHex(String hex) {
        decodedKeyHex = hex;
        wipe(decodedKeyBits);
        decodedKeyBits = null;
        if (hex != null && hex.length() >= 64) {
            try { setCompositeHex(hex.substring(0, 64)); } catch (Exception ignored) { }
        }
    }

    public synchronized String peekDecodedKeyHex() { return decodedKeyHex; }
    public synchronized boolean hasDecodedKey() { return decodedKeyHex != null && !decodedKeyHex.isEmpty(); }

    // ----- kdbx -----
    public synchronized void setKdbx(byte[] bytes, String name, Uri uri) {
        wipe(kdbxBytes);
        kdbxBytes = bytes == null ? null : bytes.clone();
        kdbxName = name;
        kdbxUri = uri;
    }

    public synchronized byte[] peekKdbx() { return kdbxBytes == null ? null : kdbxBytes.clone(); }
    public synchronized String kdbxName() { return kdbxName; }
    public synchronized Uri kdbxUri() { return kdbxUri; }
    public synchronized boolean hasKdbx() { return kdbxBytes != null; }

    // ----- restore draft -----
    public synchronized void setRestoreDraft(List<RestoreBlock> blocks, int dataBits, int checkBits,
                                             boolean kf, int mismatchDepth) {
        restoreDraft.clear();
        if (blocks != null) for (RestoreBlock b : blocks) restoreDraft.add(b.copy());
        restoreDataBits = dataBits;
        restoreCheckBits = checkBits;
        restoreKf = kf;
        restoreMismatchDepth = mismatchDepth;
    }

    public synchronized List<RestoreBlock> peekRestoreDraft() {
        List<RestoreBlock> out = new ArrayList<>();
        for (RestoreBlock b : restoreDraft) out.add(b.copy());
        return out;
    }

    public synchronized boolean hasRestoreDraft() { return !restoreDraft.isEmpty(); }
    public synchronized int restoreDataBits() { return restoreDataBits; }
    public synchronized int restoreCheckBits() { return restoreCheckBits; }
    public synchronized boolean restoreKf() { return restoreKf; }
    public synchronized int restoreMismatchDepth() { return restoreMismatchDepth; }

    public synchronized void clearRestoreDraft() { restoreDraft.clear(); }

    // ----- status / clear -----
    public synchronized List<Slot> heldSlots() {
        List<Slot> s = new ArrayList<>();
        if (hasMasterPassword()) s.add(Slot.MASTER_PASSWORD);
        if (hasKeyfile()) s.add(Slot.KEYFILE);
        if (hasComposite()) s.add(Slot.COMPOSITE);
        if (hasDecodedKey()) s.add(Slot.DECODED_KEY);
        if (hasKdbx()) s.add(Slot.KDBX);
        if (hasRestoreDraft()) s.add(Slot.RESTORE_DRAFT);
        return Collections.unmodifiableList(s);
    }

    public synchronized String statusSummary() {
        List<Slot> s = heldSlots();
        if (s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Slot slot : s) {
            if (sb.length() > 0) sb.append(" · ");
            switch (slot) {
                case MASTER_PASSWORD: sb.append("暗証"); break;
                case KEYFILE: sb.append("キーファイル"); break;
                case COMPOSITE: sb.append("合成鍵"); break;
                case DECODED_KEY: sb.append("復元鍵"); break;
                case KDBX: sb.append("kdbx"); break;
                case RESTORE_DRAFT: sb.append("紙入力"); break;
            }
        }
        return sb.toString();
    }

    public synchronized void clear() {
        wipeChars(masterPassword); masterPassword = null;
        wipe(keyfileBytes); keyfileBytes = null; keyfileName = null;
        wipe(compositeKey); compositeKey = null;
        wipe(decodedKeyBits); decodedKeyBits = null; decodedKeyHex = null;
        wipe(kdbxBytes); kdbxBytes = null; kdbxName = null; kdbxUri = null;
        restoreDraft.clear();
    }

    private static void wipe(byte[] b) { if (b != null) Arrays.fill(b, (byte) 0); }
    private static void wipeChars(char[] c) { if (c != null) Arrays.fill(c, '\0'); }
}
