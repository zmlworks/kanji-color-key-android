package com.zmlworks.kanjicolorkey;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** One kanji drawn from its KanjiVG strokes, each stroke in its color, with stroke numbers (as the web app's SVG). */
@SuppressLint("ViewConstructor") // created from code only
public class StrokeView extends View {
    private final String[] strokes;
    private final int[] colors;
    private final Path[] paths;
    private final float[][] starts;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), num = new Paint(Paint.ANTI_ALIAS_FLAG);

    public StrokeView(Context c, String[] strokes, int[] colors) {
        super(c);
        this.strokes = strokes;
        this.colors = colors;
        paths = new Path[strokes.length];
        starts = new float[strokes.length][];
        for (int i = 0; i < strokes.length; i++) { paths[i] = SvgPath.parse(strokes[i]); starts[i] = SvgPath.start(strokes[i]); }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3.5f);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        num.setTextSize(7f);
    }

    public int strokeCount() { return strokes.length; }

    @Override protected void onDraw(Canvas canvas) {
        float k = Math.min(getWidth(), getHeight()) / 109f;
        canvas.save();
        canvas.scale(k, k);
        for (int i = 0; i < paths.length; i++) {
            int c = Kck.COLOR_ARGB[i < colors.length ? colors[i] : 0];
            line.setColor(c);
            canvas.drawPath(paths[i], line);
            num.setColor(c);
            canvas.drawText(Integer.toString(i + 1), starts[i][0] - 3, starts[i][1] - 1, num);
        }
        canvas.restore();
    }
}
