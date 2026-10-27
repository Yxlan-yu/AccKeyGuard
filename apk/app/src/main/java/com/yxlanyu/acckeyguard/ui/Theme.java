package com.yxlanyu.acckeyguard.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Single place for the dashboard look: colours, dp/sp helpers, card backgrounds. */
public final class Theme {

    public static final int BG = 0xFF07080A;
    public static final int SURFACE = 0xFF13161B;
    public static final int SURFACE_HI = 0xFF191D24;
    public static final int LINE = 0xFF23272F;
    public static final int TEXT = 0xFFE9EDF5;
    public static final int TEXT_DIM = 0xFF8B93A3;
    public static final int TEXT_MUTE = 0xFF5C6373;
    public static final int ACCENT = 0xFF4C8DFF;
    public static final int OK = 0xFF35D07F;
    public static final int WARN = 0xFFFFB020;
    public static final int BAD = 0xFFFF5D5D;

    private Theme() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static float sp(Context c, float v) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    public static TextView text(Context c, String s, float sizeSp, int colour) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(colour);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static GradientDrawable card(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(SURFACE);
        d.setCornerRadius(dp(c, 18));
        d.setStroke(Math.max(1, dp(c, 1)), LINE);
        return d;
    }

    public static GradientDrawable chip(Context c, int colour) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(colour);
        d.setCornerRadius(dp(c, 99));
        return d;
    }

    public static GradientDrawable outline(Context c, int colour) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(dp(c, 99));
        d.setStroke(Math.max(1, dp(c, 1)), colour);
        return d;
    }

    public static GradientDrawable round(Context c, int colour, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(colour);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable sheet(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(SURFACE_HI);
        float r = dp(c, 22);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        d.setStroke(Math.max(1, dp(c, 1)), LINE);
        return d;
    }

    /** Wraps a view in the standard card container with the given inner padding. */
    public static View cardWrap(Context c, View child, int padDp) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(c);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackground(card(c));
        int p = dp(c, padDp);
        box.setPadding(p, p, p, p);
        box.addView(child, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /**
     * Vertical gap. Must carry LinearLayout.LayoutParams: addView(View) reuses whatever the
     * child already has, and a plain ViewGroup.LayoutParams would blow up during measure.
     */
    public static View spacer(Context c, float dp) {
        View v = new View(c);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(1, dp(c, dp)));
        return v;
    }

    public static View hline(Context c) {
        View v = new View(c);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.leftMargin = dp(c, 14);
        lp.rightMargin = dp(c, 14);
        v.setLayoutParams(lp);
        v.setBackgroundColor(LINE);
        return v;
    }
}
