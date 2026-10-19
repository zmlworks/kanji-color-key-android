package com.zmlworks.kanjicolorkey;

import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

/**
 * Material-style end-icon show/hide for password fields (no Material dependency).
 * Call {@link #attach(EditText)} once after the view is inflated.
 */
public final class PasswordVisibility {
    private PasswordVisibility() {}

    public static void attach(EditText e) {
        if (e == null || e.getTag(R.id.tag_password_toggle) != null) return;
        e.setTag(R.id.tag_password_toggle, Boolean.TRUE);
        final boolean[] shown = {false};
        applyIcon(e, false);
        e.setOnTouchListener((v, ev) -> {
            if (ev.getAction() != MotionEvent.ACTION_UP) return false;
            Drawable[] d = e.getCompoundDrawablesRelative();
            Drawable end = d[2] != null ? d[2] : e.getCompoundDrawables()[2];
            if (end == null) return false;
            int x = (int) ev.getX();
            int w = e.getWidth();
            int pad = e.getPaddingEnd() > 0 ? e.getPaddingEnd() : e.getPaddingRight();
            if (x < w - pad - end.getIntrinsicWidth() - e.getCompoundDrawablePadding()) return false;
            shown[0] = !shown[0];
            int start = e.getSelectionStart(), endSel = e.getSelectionEnd();
            if (shown[0]) {
                e.setTransformationMethod(null);
                e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            } else {
                e.setTransformationMethod(PasswordTransformationMethod.getInstance());
                e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            }
            applyIcon(e, shown[0]);
            try { e.setSelection(start, endSel); } catch (Exception ignored) { }
            return true;
        });
    }

    private static void applyIcon(EditText e, boolean shown) {
        Drawable icon = e.getContext().getDrawable(shown ? R.drawable.ic_visibility_off : R.drawable.ic_visibility);
        if (icon != null) {
            int s = (int) (24 * e.getResources().getDisplayMetrics().density);
            icon.setBounds(0, 0, s, s);
        }
        e.setCompoundDrawablePadding((int) (8 * e.getResources().getDisplayMetrics().density));
        e.setCompoundDrawablesRelative(null, null, icon, null);
        // Extra end padding so text doesn't sit under the icon
        int extra = (int) (36 * e.getResources().getDisplayMetrics().density);
        e.setPaddingRelative(e.getPaddingStart(), e.getPaddingTop(),
                Math.max(e.getPaddingEnd(), extra), e.getPaddingBottom());
    }
}
