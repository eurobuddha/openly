package com.eurobuddha.openly;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Programmatic-UI helpers reading everything from {@link Design}. Keeps view code readable. */
public final class Ui {

    private Ui() {}

    public static int dp(Context c, int v) { return Design.dp(c, v); }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** A card surface with hairline border, 18dp radius, 16dp pad, 12dp bottom margin. */
    public static LinearLayout card(Context c) {
        LinearLayout l = col(c);
        l.setBackground(Design.card(c, 18));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        l.setLayoutParams(lp);
        return l;
    }

    public static TextView text(Context c, String s, int color, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(bold ? Design.sansBold() : Design.sans());
        return t;
    }

    /** JetBrains Mono tabular amount — for any money value. */
    public static TextView money(Context c, String s, int color, float sp, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(bold ? Design.monoBold() : Design.mono());
        return t;
    }

    /** Micro uppercase tracked label. */
    public static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), Design.DIM(), 10, true);
        t.setLetterSpacing(0.12f);
        return t;
    }

    /** A rounded status chip (soft bg / solid ink). */
    public static TextView chip(Context c, String s, int fg, int bg) {
        TextView t = text(c, s.toUpperCase(), fg, 10, true);
        t.setLetterSpacing(0.08f);
        t.setBackground(Design.roundBg(c, bg, 999));
        t.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4));
        return t;
    }

    /** A pressable pill button. */
    public static TextView button(Context c, String s, int bg, int fg, boolean gradient) {
        TextView t = text(c, s, fg, 14, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(gradient ? Design.gradientCta(c) : Design.roundBg(c, bg, 14));
        t.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12));
        Design.pressable(t);
        return t;
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public static void topMargin(View v, int px) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).topMargin = px;
        } else {
            LinearLayout.LayoutParams n = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            n.topMargin = px;
            v.setLayoutParams(n);
        }
    }

    /** Smart money format for AGGREGATES only (never for a committed amount). */
    public static String compact(java.math.BigDecimal n) {
        double d = n.doubleValue();
        if (d >= 10000) return String.format("%.1fk", d / 1000.0);
        return Num.plain(n);
    }

    /** A slow "alive" alpha throb — for a pending/confirming indicator. Cancels itself on detach. */
    public static void throb(final View v) {
        final android.animation.ObjectAnimator a =
                android.animation.ObjectAnimator.ofFloat(v, "alpha", 1f, 0.35f, 1f);
        a.setDuration(1100);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.start();
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View x) {}
            public void onViewDetachedFromWindow(View x) { a.cancel(); v.setAlpha(1f); }
        });
    }
}
