package com.zmlworks.kanjicolorkey;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * Japanese-style flick pad for the 4 encoding colors (黒赤青緑 = 0..3).
 * Layout (center tap = unknown "?"):
 * <pre>
 *         ↑ 黒 (0)
 *  ← 青(2)  ●?  → 赤(1)
 *         ↓ 緑 (3)
 * </pre>
 * Petals appear around the finger while dragging; dark-mode uses COLOR_ARGB_DARK.
 */
public class FlickColorView extends View {
    public interface Listener {
        void onColor(int colorOrMinus1); // 0..3 or -1 for ?
    }

    private final Paint petal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint center = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] palette = Kck.COLOR_ARGB;
    private String[] names = Kck.COLORS;
    private Listener listener;
    private float downX, downY;
    private boolean dragging;
    private int hover = -2; // -2 none, -1 ?, 0..3 color
    private float thresholdPx;

    public FlickColorView(Context c) { this(c, null); }
    public FlickColorView(Context c, AttributeSet a) {
        super(c, a);
        thresholdPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36, c.getResources().getDisplayMetrics());
        petal.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, c.getResources().getDisplayMetrics()));
        label.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
        center.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    public void setPalette(int[] palette, String[] names) {
        this.palette = palette;
        this.names = names;
        invalidate();
    }

    public void setListener(Listener l) { listener = l; }

    /** Test-only: drive color entry without MotionEvent. */
    Listener getListenerForTest() { return listener; }

    /** Direction → color index. Up=黒0, Right=赤1, Left=青2, Down=緑3. */
    static int directionToColor(float dx, float dy, float threshold) {
        float dist = (float) Math.hypot(dx, dy);
        if (dist < threshold) return -1; // center / tap = ?
        if (Math.abs(dx) >= Math.abs(dy)) return dx > 0 ? 1 : 2; // right=赤, left=青
        return dy > 0 ? 3 : 0; // down=緑, up=黒
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                dragging = true; hover = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                hover = directionToColor(e.getX() - downX, e.getY() - downY, thresholdPx);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                int result = directionToColor(e.getX() - downX, e.getY() - downY, thresholdPx);
                dragging = false; hover = -2;
                invalidate();
                if (e.getActionMasked() == MotionEvent.ACTION_UP && listener != null) listener.onColor(result);
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.42f;
        // Idle guide: four petals + center
        drawPetal(canvas, cx, cy - r * 0.55f, r * 0.38f, 0, hover == 0);
        drawPetal(canvas, cx + r * 0.55f, cy, r * 0.38f, 1, hover == 1);
        drawPetal(canvas, cx - r * 0.55f, cy, r * 0.38f, 2, hover == 2);
        drawPetal(canvas, cx, cy + r * 0.55f, r * 0.38f, 3, hover == 3);
        // Center = ?
        boolean hiQ = hover == -1;
        center.setColor(hiQ ? 0xFF666666 : 0xFF444444);
        canvas.drawCircle(cx, cy, r * 0.28f, center);
        ring.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, r * 0.28f, ring);
        label.setTextSize(r * 0.22f);
        label.setColor(0xFFFFFFFF);
        canvas.drawText("?", cx, cy + r * 0.08f, label);
        label.setTextSize(r * 0.11f);
        label.setColor(0xFFCCCCCC);
        canvas.drawText("unknown", cx, cy + r * 0.22f, label);

        if (dragging) {
            // Finger guide ring
            ring.setColor(0x88FFFFFF);
            canvas.drawCircle(downX, downY, thresholdPx, ring);
        }
    }

    private void drawPetal(Canvas canvas, float x, float y, float pr, int colorIdx, boolean hi) {
        int argb = palette[colorIdx];
        petal.setColor(hi ? argb : (argb & 0x00FFFFFF) | 0xCC000000);
        canvas.drawCircle(x, y, pr * (hi ? 1.15f : 1f), petal);
        ring.setColor(0x66FFFFFF);
        canvas.drawCircle(x, y, pr * (hi ? 1.15f : 1f), ring);
        label.setTextSize(pr * 0.55f);
        // Contrast: black petal needs light text on dark mode (light 黒), dark 黒 on light mode uses light text too
        boolean lightPetal = ((argb >> 16) & 0xFF) > 0xA0 && ((argb >> 8) & 0xFF) > 0xA0 && (argb & 0xFF) > 0xA0;
        label.setColor(lightPetal ? 0xFF111111 : 0xFFFFFFFF);
        canvas.drawText(names[colorIdx], x, y + pr * 0.18f, label);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 220, getResources().getDisplayMetrics());
        int w = resolveSize(size, wSpec);
        int h = resolveSize(size, hSpec);
        setMeasuredDimension(w, h);
    }
}
