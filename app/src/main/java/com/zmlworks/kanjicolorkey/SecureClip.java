package com.zmlworks.kanjicolorkey;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Clipboard copies of key material: marked sensitive (hidden from the clipboard preview / keyboard suggestions on
 * Android 13+) and cleared after CLEAR_MS.
 * Android 10+ lets only the focused app read the clipboard. When the timer fires while this app is in the
 * foreground, the clip is cleared only if it is still ours (compared by SHA-256). In the background it cannot
 * be read, so it is cleared anyway; this can also wipe something copied in another app within the 60 s.
 */
public final class SecureClip {
    private SecureClip() {}

    public static final long CLEAR_MS = 60_000;
    /** Same value as ClipDescription.EXTRA_IS_SENSITIVE (API 33); older systems ignore it. */
    static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static byte[] lastHash;
    private static Runnable pending;

    public static void copy(Context ctx, String label, String text) {
        Context app = ctx.getApplicationContext();
        ClipboardManager cm = app.getSystemService(ClipboardManager.class);
        ClipData clip = ClipData.newPlainText(label, text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(Build.VERSION.SDK_INT >= 33 ? ClipDescription.EXTRA_IS_SENSITIVE : EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        cm.setPrimaryClip(clip);
        lastHash = hash(text);
        if (pending != null) H.removeCallbacks(pending);
        pending = () -> clearIfOurs(app);
        H.postDelayed(pending, CLEAR_MS);
    }

    static void clearIfOurs(Context app) {
        pending = null;
        ClipboardManager cm = app.getSystemService(ClipboardManager.class);
        ClipData now = null;
        try { now = cm.getPrimaryClip(); } catch (SecurityException ignored) { }
        if (now != null && now.getItemCount() > 0) {
            CharSequence t = now.getItemAt(0).getText();
            if (t == null || lastHash == null || !Arrays.equals(hash(t.toString()), lastHash)) { lastHash = null; return; } // not ours any more
        }
        lastHash = null;
        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip();
        else cm.setPrimaryClip(ClipData.newPlainText("", ""));
    }

    /** For tests: whether a clear is scheduled. */
    static boolean isPending() { return pending != null; }

    private static byte[] hash(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { return new byte[0]; }
    }
}
