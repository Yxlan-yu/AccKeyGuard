package com.yxlanyu.acckeyguard.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Horizontal segmented meter: guarded / healthy / broken proportions. */
public final class Bar extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int total = 0;
    private int healthy = 0;
    private int broken = 0;

    public Bar(Context c) {
        super(c);
    }

    public void set(int total, int healthy, int broken) {
        this.total = Math.max(0, total);
        this.healthy = Math.max(0, Math.min(healthy, this.total));
        this.broken = Math.max(0, Math.min(broken, this.total - this.healthy));
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, Theme.dp(getContext(), 8));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float h = getHeight();
        float r = h / 2f;
        rect.set(0, 0, getWidth(), h);

        paint.setColor(Theme.LINE);
        canvas.drawRoundRect(rect, r, r, paint);

        if (total <= 0) {
            return;
        }
        float unit = getWidth() / (float) total;
        int gap = Math.max(1, Theme.dp(getContext(), 2));
        int healthyLeft = healthy;
        int brokenLeft = broken;
        int index = 0;
        paint.setColor(Theme.OK);
        for (int i = 0; i < healthyLeft; i++, index++) {
            drawCell(canvas, index, unit, gap, r, Theme.OK);
        }
        for (int i = 0; i < brokenLeft; i++, index++) {
            drawCell(canvas, index, unit, gap, r, Theme.BAD);
        }
        for (; index < total; index++) {
            drawCell(canvas, index, unit, gap, r, Theme.TEXT_MUTE);
        }
    }

    private void drawCell(Canvas canvas, int index, float unit, int gap, float r, int colour) {
        paint.setColor(colour);
        float left = index * unit + gap * 0.5f;
        float right = (index + 1) * unit - gap * 0.5f;
        if (right <= left) {
            return;
        }
        rect.set(left, 0, right, getHeight());
        canvas.drawRoundRect(rect, r, r, paint);
    }
}
