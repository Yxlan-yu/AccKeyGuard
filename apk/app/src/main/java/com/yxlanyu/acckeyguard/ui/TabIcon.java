package com.yxlanyu.acckeyguard.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Small vector glyph used for the three header tabs. */
public final class TabIcon extends View {

    public static final int LIST = 0;
    public static final int PULSE = 1;
    public static final int SLIDER = 2;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final int kind;
    private boolean selected;
    private boolean badge;

    public TabIcon(Context c, int kind) {
        super(c);
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        dot.setStyle(Paint.Style.FILL);
        fill.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    public void setSelectedState(boolean v) {
        selected = v;
        invalidate();
    }

    public void setBadge(boolean v) {
        if (badge != v) {
            badge = v;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        Context c = getContext();

        if (selected) {
            fill.setColor(0x1F4C8DFF);
            canvas.drawCircle(cx, cy, Math.min(w, h) * 0.42f, fill);
        }

        int colour = selected ? Theme.ACCENT : Theme.TEXT_MUTE;
        stroke.setColor(colour);
        stroke.setStrokeWidth(Math.max(1f, Theme.dp(c, 1.7f)));
        dot.setColor(colour);

        float s = Math.min(w, h) * 0.24f;
        path.reset();

        if (kind == LIST) {
            float gap = s * 0.78f;
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * gap;
                canvas.drawLine(cx - s, y, cx + s * 0.55f, y, stroke);
            }
            dot.setColor(selected ? Theme.OK : Theme.TEXT_DIM);
            for (int i = -1; i <= 1; i++) {
                canvas.drawCircle(cx + s * 0.95f, cy + i * gap, s * 0.13f, dot);
            }
        } else if (kind == PULSE) {
            path.moveTo(cx - s * 1.5f, cy);
            path.lineTo(cx - s * 0.75f, cy);
            path.lineTo(cx - s * 0.30f, cy - s * 0.95f);
            path.lineTo(cx + s * 0.18f, cy + s * 0.95f);
            path.lineTo(cx + s * 0.62f, cy);
            path.lineTo(cx + s * 1.5f, cy);
            canvas.drawPath(path, stroke);
        } else {
            float gap = s * 0.85f;
            for (int i = -1; i <= 1; i += 2) {
                float y = cy + i * gap;
                canvas.drawLine(cx - s * 1.35f, y, cx + s * 1.35f, y, stroke);
                float x = cx + (i < 0 ? -s * 0.45f : s * 0.5f);
                dot.setColor(selected ? Theme.ACCENT : Theme.TEXT_DIM);
                canvas.drawCircle(x, y, s * 0.34f, dot);
            }
        }

        if (badge) {
            dot.setColor(Theme.BAD);
            canvas.drawCircle(w - Theme.dp(c, 9), Theme.dp(c, 9), Theme.dp(c, 3), dot);
        }
    }
}
