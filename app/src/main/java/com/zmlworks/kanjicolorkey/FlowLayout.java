package com.zmlworks.kanjicolorkey;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** Wraps its children into rows (the web app's flex-wrap card row). */
public class FlowLayout extends ViewGroup {
    private final int gap;

    public FlowLayout(Context c) { this(c, null); }
    public FlowLayout(Context c, AttributeSet a) { super(c, a); gap = Math.round(6 * c.getResources().getDisplayMetrics().density); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
        boolean unbounded = MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED;
        int x = 0, y = 0, rowH = 0, usedW = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            c.measure(MeasureSpec.makeMeasureSpec(Math.max(0, maxW), unbounded ? MeasureSpec.UNSPECIFIED : MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            if (x > 0 && !unbounded && x + cw > maxW) { x = 0; y += rowH + gap; rowH = 0; }
            x += cw + gap; rowH = Math.max(rowH, ch); usedW = Math.max(usedW, x - gap);
        }
        int h = y + rowH + getPaddingTop() + getPaddingBottom();
        int w = unbounded ? usedW + getPaddingLeft() + getPaddingRight() : MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, resolveSize(h, hSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            if (x > 0 && x + cw > maxW) { x = 0; y += rowH + gap; rowH = 0; }
            c.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + cw, getPaddingTop() + y + ch);
            x += cw + gap; rowH = Math.max(rowH, ch);
        }
    }
}
