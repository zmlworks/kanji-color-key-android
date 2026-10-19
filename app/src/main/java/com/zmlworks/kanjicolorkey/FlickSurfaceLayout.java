package com.zmlworks.kanjicolorkey;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.FrameLayout;

/**
 * Wraps the KanjiVG sample and turns it into the flick surface: touching it shows the four color petals
 * around the finger (↑黒 →赤 ←青 ↓緑, center = ?); the release direction picks the color. The petals are drawn
 * over the sample only while touching, clamped to stay inside the box (the direction is measured from the
 * real touch point).
 */
public class FlickSurfaceLayout extends FrameLayout {
    private final Paint petal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint();
    private int[] palette = Kck.COLOR_ARGB;
    private String[] names = Kck.COLORS;
    private FlickColorView.Listener listener;
    private float downX, downY;
    private boolean touching;
    private int hover = -1;
    private final float thresholdPx, petalR, petalDist;

    public FlickSurfaceLayout(Context c) { this(c, null); }
    public FlickSurfaceLayout(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;
        thresholdPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30, c.getResources().getDisplayMetrics());
        petalR = 22 * d;
        petalDist = 48 * d;
        petal.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * d);
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
        veil.setColor(0x55000000);
        setWillNotDraw(false);
        setClickable(true);
    }

    public void setPalette(int[] palette, String[] names) { this.palette = palette; this.names = names; invalidate(); }
    public void setListener(FlickColorView.Listener l) { listener = l; }
    FlickColorView.Listener getListenerForTest() { return listener; }
    boolean isShowingPetalsForTest() { return touching; }

    /** Test/screenshot hook: show the petals as if touched at (x, y) with the given hover. */
    void showPetalsForTest(float x, float y, int hoverColor) {
        downX = x; downY = y; touching = true; hover = hoverColor; invalidate();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) { return true; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX(); downY = e.getY();
                touching = true; hover = -1;
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true); // keep the ScrollView from stealing
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (!touching) return false;
                hover = FlickColorView.directionToColor(e.getX() - downX, e.getY() - downY, thresholdPx);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!touching) return false;
                int result = FlickColorView.directionToColor(e.getX() - downX, e.getY() - downY, thresholdPx);
                touching = false; hover = -1;
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(false);
                invalidate();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    performClick();
                    if (listener != null) listener.onColor(result);
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!touching) return;
        canvas.drawRect(0, 0, getWidth(), getHeight(), veil);
        float m = petalDist + petalR;
        float cx = clamp(downX, m, getWidth() - m);
        float cy = clamp(downY, m, getHeight() - m);
        drawPetal(canvas, cx, cy - petalDist, 0);
        drawPetal(canvas, cx + petalDist, cy, 1);
        drawPetal(canvas, cx - petalDist, cy, 2);
        drawPetal(canvas, cx, cy + petalDist, 3);
        boolean hiQ = hover == -1;
        petal.setColor(hiQ ? 0xFF777777 : 0xCC444444);
        float qr = petalR * (hiQ ? 0.9f : 0.75f);
        canvas.drawCircle(cx, cy, qr, petal);
        ring.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, qr, ring);
        label.setTextSize(petalR * 0.8f);
        label.setColor(0xFFFFFFFF);
        canvas.drawText("?", cx, cy + petalR * 0.28f, label);
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) return (lo + hi) / 2f;
        return Math.max(lo, Math.min(hi, v));
    }

    private void drawPetal(Canvas canvas, float x, float y, int idx) {
        boolean hi = hover == idx;
        int argb = palette[idx];
        float r = petalR * (hi ? 1.2f : 1f);
        petal.setColor(hi ? argb : (argb & 0x00FFFFFF) | 0xDD000000);
        canvas.drawCircle(x, y, r, petal);
        ring.setColor(hi ? 0xFFFFFFFF : 0x88FFFFFF);
        canvas.drawCircle(x, y, r, ring);
        label.setTextSize(petalR * 0.8f);
        boolean light = ((argb >> 16) & 0xFF) > 0xA0 && ((argb >> 8) & 0xFF) > 0xA0 && (argb & 0xFF) > 0xA0;
        label.setColor(light ? 0xFF111111 : 0xFFFFFFFF);
        canvas.drawText(names[idx], x, y + petalR * 0.28f, label);
    }
}
