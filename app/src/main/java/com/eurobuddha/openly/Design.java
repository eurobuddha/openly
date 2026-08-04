package com.eurobuddha.openly;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

/**
 * Openly design-token engine (forked from the atomix Design system).
 *
 *  - NOIR     — violet-black night market: electric-violet accent, hairline cards, mono money. Default.
 *  - DAYBREAK — light variant descended from the original cream dapp.
 *
 * TRUE/FALSE are POSITIONS, not moral outcomes, so they are teal / magenta — not green / red.
 * Red is reserved for errors + escrow forfeit; gold for the arbiter, escrow, and the win moment.
 *
 * Every view reads colours + typefaces from here; the mode toggle restyles the whole app by re-render
 * (no Activity recreate). Numbers are JetBrains Mono (tabular); text is Inter.
 */
public final class Design {

    public enum Mode { NOIR, DAYBREAK }

    private static final String PREFS = "openly_design";
    private static final String KEY = "theme";

    private static Mode mode = Mode.NOIR;
    private static Typeface sSans, sMono;

    private Design() {}

    // ---- lifecycle ----
    public static void load(Context c) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, Mode.NOIR.name());
        try { mode = Mode.valueOf(s); } catch (Exception e) { mode = Mode.NOIR; }
        if (sSans == null) { try { sSans = ResourcesCompat.getFont(c, R.font.inter); } catch (Exception ignore) {} }
        if (sMono == null) { try { sMono = ResourcesCompat.getFont(c, R.font.jetbrains_mono); } catch (Exception ignore) {} }
    }

    public static void set(Context c, Mode m) {
        mode = m;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.name()).apply();
    }

    public static void toggle(Context c) { set(c, mode == Mode.NOIR ? Mode.DAYBREAK : Mode.NOIR); }

    public static Mode mode() { return mode; }
    public static boolean isDark() { return mode == Mode.NOIR; }
    public static String label() { return isDark() ? "Noir" : "Daybreak"; }

    private static int pick(int noir, int day) { return mode == Mode.NOIR ? noir : day; }

    // ---- semantic colours (ARGB) ----
    public static int BG()          { return pick(0xFF0B0912, 0xFFF6F4EF); }
    public static int SURFACE()     { return pick(0xFF131020, 0xFFFFFFFF); }
    public static int SURFACE2()    { return pick(0xFF1C1830, 0xFFEEEBE4); }
    public static int SURFACE3()    { return pick(0xFF262040, 0xFFE4E0D6); }
    public static int BORDER()      { return pick(0x14FFFFFF, 0xFFE4E1D8); }
    public static int BORDER_LIVE() { return pick(0x598B6CFF, 0x596C3AED); }
    public static int TEXT()        { return pick(0xFFF0EDF8, 0xFF16121F); }
    public static int DIM()         { return pick(0xFF9B93B3, 0xFF6B6580); }
    public static int DIM2()        { return pick(0xFF665F80, 0xFF9B95AD); }

    public static int ACCENT()      { return pick(0xFF8B6CFF, 0xFF6C3AED); }
    public static int GRAD_START()  { return pick(0xFFA78BFF, 0xFF8A5CF6); }
    public static int ACCENT_SOFT() { return pick(0x2E8B6CFF, 0x1F6C3AED); }
    public static int ON_ACCENT()   { return pick(0xFF0B0912, 0xFFFFFFFF); }

    public static int TRUE_C()      { return pick(0xFF2FE0BD, 0xFF0EA88A); }
    public static int TRUE_SOFT()   { return pick(0x242FE0BD, 0xFFD8F6EE); }
    public static int FALSE_C()     { return pick(0xFFFF5C9E, 0xFFDB3D7B); }
    public static int FALSE_SOFT()  { return pick(0x24FF5C9E, 0xFFFBDFEC); }
    public static int GOLD()        { return pick(0xFFE8B54A, 0xFFA87B14); }
    public static int GOLD_SOFT()   { return pick(0x24E8B54A, 0xFFF7EDD2); }
    public static int WARN()        { return pick(0xFFFFB224, 0xFFB87400); }
    public static int WARN_SOFT()   { return pick(0x24FFB224, 0xFFFCEED2); }
    public static int NEG()         { return pick(0xFFFF5D5D, 0xFFDC2F2F); }
    public static int NEG_SOFT()    { return pick(0x24FF5D5D, 0xFFFBDDDD); }

    /** side==1 → TRUE (teal); side==0 → FALSE (magenta). */
    public static int sideColor(int side) { return side == 1 ? TRUE_C() : FALSE_C(); }
    public static int sideSoft(int side)  { return side == 1 ? TRUE_SOFT() : FALSE_SOFT(); }

    // ---- typography ----
    public static Typeface sans()     { return sSans != null ? sSans : Typeface.SANS_SERIF; }
    public static Typeface sansBold() { return Typeface.create(sans(), Typeface.BOLD); }
    public static Typeface mono()     { return sMono != null ? sMono : Typeface.MONOSPACE; }
    public static Typeface monoBold() { return Typeface.create(mono(), Typeface.BOLD); }

    // ---- metrics ----
    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    // ---- drawables ----
    public static GradientDrawable roundBg(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable card(Context c, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE());
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), BORDER());
        return d;
    }

    public static GradientDrawable stroked(Context c, int fill, int strokeColor, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), strokeColor);
        return d;
    }

    public static GradientDrawable gradientCta(Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{GRAD_START(), ACCENT()});
        d.setCornerRadius(dp(c, 14));
        return d;
    }

    public static RippleDrawable ripple(GradientDrawable base) {
        return new RippleDrawable(ColorStateList.valueOf(ACCENT_SOFT()), base, null);
    }

    // ---- micro-motion ----
    public static void pressable(final View v) {
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start(); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); break;
            }
            return false;
        });
    }

    public static void pulse(final View v, final int flashColor) {
        if (v == null) return;
        v.post(() -> {
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
            ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.18f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.18f, 1f);
            sx.setRepeatCount(2); sy.setRepeatCount(2);
            AnimatorSet set = new AnimatorSet();
            set.playTogether(sx, sy);
            set.setDuration(420);
            set.setInterpolator(new OvershootInterpolator());
            set.start();
            if (v instanceof TextView) {
                final TextView tv = (TextView) v;
                final int original = tv.getCurrentTextColor();
                ValueAnimator flash = ValueAnimator.ofArgb(original, flashColor);
                flash.setDuration(220);
                flash.setRepeatMode(ValueAnimator.REVERSE);
                flash.setRepeatCount(3);
                flash.addUpdateListener(a -> tv.setTextColor((int) a.getAnimatedValue()));
                flash.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) { tv.setTextColor(original); }
                });
                flash.start();
            }
        });
    }
}
