package com.eurobuddha.openly;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;

import java.util.Random;

/**
 * A throw-away win-celebration overlay (ported from casino Confetti): ~14 teal/violet/gold square
 * particles drop from the top of a parent {@link ViewGroup}, tumbling and fading over ~1.2s, then the
 * view removes itself. Win-only per the design spec. Fire-and-forget via {@link #burst(ViewGroup)}.
 */
public class Confetti extends View {

    private static final int COUNT = 14;
    private static final long DURATION = 1200;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Particle[] parts = new Particle[COUNT];
    private final Random rnd = new Random();
    private float t = 0f;
    private ValueAnimator anim;

    private Confetti(Context c) {
        super(c);
        paint.setStyle(Paint.Style.FILL);
    }

    public static void burst(ViewGroup parent) {
        if (parent == null) return;
        final Confetti cf = new Confetti(parent.getContext());
        cf.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        parent.addView(cf);
        cf.post(cf::start);
    }

    /** Overlay a burst across a whole dialog window (FrameLayout content), so it rains OVER the card
     *  rather than being laid out below it in a vertical container. */
    public static void burstOnDialog(android.app.Dialog d) {
        if (d == null || d.getWindow() == null) return;
        final Confetti cf = new Confetti(d.getContext());
        d.addContentView(cf, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cf.post(cf::start);
    }

    private void start() {
        int w = getWidth(), h = getHeight();
        if (w <= 0) w = 600;
        if (h <= 0) h = 600;
        int[] palette = { Design.TRUE_C(), Design.ACCENT(), Design.GOLD() };
        for (int i = 0; i < COUNT; i++) {
            Particle p = new Particle();
            p.x = rnd.nextFloat() * w;
            p.startY = -rnd.nextFloat() * h * 0.2f;
            p.endY = h * (0.7f + rnd.nextFloat() * 0.4f);
            p.size = (w * 0.016f) + rnd.nextFloat() * (w * 0.02f);
            p.drift = (rnd.nextFloat() * 2 - 1) * w * 0.12f;
            p.spin = (rnd.nextFloat() * 2 - 1) * 720f;
            p.delay = rnd.nextFloat() * 0.25f;
            p.color = palette[i % palette.length];
            parts[i] = p;
        }
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(DURATION);
        anim.addUpdateListener(a -> { t = (float) a.getAnimatedValue(); invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { remove(); }
        });
        anim.start();
    }

    private void remove() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) { anim.cancel(); anim = null; }
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas cv) {
        for (Particle p : parts) {
            if (p == null) continue;
            float lp = (t - p.delay) / (1f - p.delay);
            if (lp <= 0f) continue;
            if (lp > 1f) lp = 1f;
            float y = p.startY + (p.endY - p.startY) * ease(lp);
            float x = p.x + p.drift * lp;
            paint.setColor(p.color);
            paint.setAlpha((int) (255 * (1f - lp)));
            cv.save();
            cv.rotate(p.spin * lp, x, y);
            float half = p.size / 2f;
            cv.drawRect(x - half, y - half, x + half, y + half, paint);
            cv.restore();
        }
    }

    private static float ease(float v) { return v * v * 1.1f; }

    private static final class Particle {
        float x, startY, endY, size, drift, spin, delay;
        int color;
    }
}
