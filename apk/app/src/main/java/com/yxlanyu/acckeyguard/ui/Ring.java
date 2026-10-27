package com.yxlanyu.acckeyguard.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Circular gauge: background track + value arc + big percentage in the middle. */
public final class Ring extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private float pct = 0f;
    private int tint = Theme.OK;
    private String big = "0";
    private String small = "";

    public Ring(Context c) {
        super(c);
        track.setStyle(Paint.Style.STROKE);
        track.setColor(Theme.LINE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        cap.setStyle(Paint.Style.FILL);
        label.setColor(Theme.TEXT);
        label.setTextAlign(Paint.Align.CENTER);
        sub.setColor(Theme.TEXT_DIM);
        sub.setTextAlign(Paint.Align.CENTER);
    }

    public void set(float percent, int colour, String top, String bottom) {
        pct = Math.max(0f, Math.min(1f, percent));
        tint = colour;
        big = top;
        small = bottom;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
        if (size <= 0) {
            size = Theme.dp(getContext(), 132);
        }
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Context c = getContext();
        float w = Math.min(getWidth(), getHeight());
        float stroke = Theme.dp(c, 9);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        arc.setColor(tint);
        cap.setColor(tint);

        float pad = stroke / 2f + Theme.dp(c, 6);
        box.set(pad, pad, w - pad, w - pad);
        canvas.drawArc(box, 0f, 360f, false, track);
        if (pct > 0.001f) {
            canvas.drawArc(box, -90f, 360f * pct, false, arc);
            double a = Math.toRadians(-90f + 360f * pct);
            float r = box.width() / 2f;
            float cx = box.centerX() + (float) (Math.cos(a) * r);
            float cy = box.centerY() + (float) (Math.sin(a) * r);
            canvas.drawCircle(cx, cy, stroke * 0.34f, cap);
        }

        label.setTextSize(Theme.sp(c, 34));
        float baseY = w / 2f - (label.descent() + label.ascent()) / 2f;
        canvas.drawText(big, w / 2f, baseY, label);
        if (small.length() > 0) {
            sub.setTextSize(Theme.sp(c, 11));
            canvas.drawText(small, w / 2f, baseY + Theme.dp(c, 20), sub);
        }
    }
}
