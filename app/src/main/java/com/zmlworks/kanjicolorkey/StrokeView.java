package com.zmlworks.kanjicolorkey;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Kanji/kana from KanjiVG strokes. Optional highlightIndex draws that stroke thicker and dims others
 * (used by the paper-restore flick flow). palette: COLOR_ARGB or COLOR_ARGB_DARK.
 */
@SuppressLint("ViewConstructor")
public class StrokeView extends View {
    private final String[] strokes;
    private int[] colors;
    private final int[] palette;
    private final Path[] paths;
    private final float[][] starts;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int highlightIndex = -1; // -1 = all equal

    public StrokeView(Context c, String[] strokes, int[] colors, int[] palette) {
        super(c);
        this.palette = palette;
        this.strokes = strokes;
        this.colors = colors != null ? colors.clone() : new int[strokes.length];
        paths = new Path[strokes.length];
        starts = new float[strokes.length][];
        for (int i = 0; i < strokes.length; i++) {
            paths[i] = SvgPath.parse(strokes[i]);
            starts[i] = SvgPath.start(strokes[i]);
        }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        num.setTextSize(8f);
        num.setFakeBoldText(true);
    }

    public int strokeCount() { return strokes.length; }

    /** Resolved ARGB for stroke i (for tests / accessibility). */
    public int strokeColor(int i) {
        int ci = i < colors.length ? colors[i] : 0;
        if (ci < 0) ci = 0;
        return palette[ci & 3];
    }

    public void setColors(int[] colors) {
        this.colors = colors != null ? colors.clone() : new int[strokes.length];
        invalidate();
    }

    public void setHighlightIndex(int i) {
        highlightIndex = i;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float k = Math.min(getWidth(), getHeight()) / 109f;
        canvas.save();
        canvas.scale(k, k);
        for (int i = 0; i < paths.length; i++) {
            int ci = i < colors.length ? colors[i] : 0;
            if (ci < 0) ci = 0; // unknown: draw as 黒 dimmed
            int c = palette[ci & 3];
            boolean hi = highlightIndex < 0 || i == highlightIndex;
            boolean pending = highlightIndex >= 0 && i > highlightIndex;
            line.setStrokeWidth(hi && highlightIndex >= 0 ? 5.5f : 3.5f);
            int alpha = pending ? 0x55 : (hi ? 0xFF : 0x88);
            line.setColor((c & 0x00FFFFFF) | (alpha << 24));
            canvas.drawPath(paths[i], line);
            num.setColor((c & 0x00FFFFFF) | (alpha << 24));
            num.setTextSize(hi && highlightIndex >= 0 ? 9f : 7f);
            canvas.drawText(Integer.toString(i + 1), starts[i][0] - 3, starts[i][1] - 1, num);
        }
        canvas.restore();
    }
}
