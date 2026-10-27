package com.yxlanyu.acckeyguard.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Pill switch, fully custom so no AndroidX is required. */
public final class Toggle extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tick = new Path();
    private final RectF box = new RectF();

    private boolean on;
    private float pos;
    private boolean touchable = true;
    private OnChange listener;
    private ValueAnimator anim;

    public interface OnChange {
        void onChanged(boolean value);
    }

    public Toggle(Context c) {
        super(c);
        check.setStyle(Paint.Style.STROKE);
        check.setStrokeCap(Paint.Cap.ROUND);
        check.setColor(Theme.BG);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!touchable) {
                    return;
                }
                set(!on, true);
                if (listener != null) {
                    listener.onChanged(on);
                }
            }
        });
    }

    public void setListener(OnChange l) {
        listener = l;
    }

    public void setTouchable(boolean v) {
        touchable = v;
        setAlpha(v ? 1f : 0.55f);
    }

    public boolean value() {
        return on;
    }

    public void set(boolean value, boolean animate) {
        if (on == value && pos == (value ? 1f : 0f)) {
            return;
        }
        on = value;
        float target = value ? 1f : 0f;
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        if (!animate) {
            pos = target;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(pos, target);
        anim.setDuration(160L);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Theme.dp(getContext(), 46), Theme.dp(getContext(), 27));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float r = height / 2f;
        box.set(0, 0, width, height);

        int off = Theme.LINE;
        int col = blend(off, Theme.ACCENT, pos);
        track.setColor(col);
        canvas.drawRoundRect(box, r, r, track);

        float pad = Theme.dp(getContext(), 3);
        float d = height - pad * 2f;
        float x = pad + (width - pad * 2f - d) * pos;
        thumb.setColor(blend(Theme.TEXT_MUTE, 0xFFFFFFFF, pos));
        canvas.drawCircle(x + d / 2f, height / 2f, d / 2f, thumb);

        if (pos > 0.35f) {
            check.setStrokeWidth(Math.max(1, Theme.dp(getContext(), 1.6f)));
            float cx = x + d / 2f;
            float cy = height / 2f;
            float s = d * 0.20f;
            tick.reset();
            tick.moveTo(cx - s, cy);
            tick.lineTo(cx - s * 0.15f, cy + s * 0.8f);
            tick.lineTo(cx + s, cy - s * 0.7f);
            check.setAlpha((int) (255 * Math.min(1f, (pos - 0.35f) / 0.4f)));
            canvas.drawPath(tick, check);
        }
    }

    private static int blend(int from, int to, float t) {
        float k = Math.max(0f, Math.min(1f, t));
        int a = (int) (((from >> 24) & 0xFF) + ((((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * k));
        int r = (int) (((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k));
        int g = (int) (((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k));
        int b = (int) ((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * k));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
