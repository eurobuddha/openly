package com.eurobuddha.openly;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * The sealed-envelope declaration reveal (design spec §6.4/§5.5): the counterparty has declared, but
 * you answer reality — not them — so their answer stays sealed behind a wax-dot envelope until you
 * commit your own. {@link #tear(Runnable)} flaps it open (450ms Overshoot) and fires the callback,
 * at which point the parent shows what was inside. Kills the anchoring you'd get from seeing their
 * pick first. Pure Canvas; no assets.
 */
public class EnvelopeView extends View {

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hair = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wax = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path tri = new Path();
    private float tear = 0f;              // 0 sealed → 1 open
    private boolean torn = false;
    private ValueAnimator anim;

    public EnvelopeView(Context c) {
        super(c);
        body.setColor(Design.SURFACE2());
        flap.setColor(Design.SURFACE3());
        hair.setStyle(Paint.Style.STROKE);
        hair.setStrokeWidth(dp(1));
        hair.setColor(Design.BORDER());
        wax.setColor(Design.GOLD());
        label.setColor(Design.DIM());
        label.setTypeface(Design.sansBold());
        label.setTextSize(dp(11));
        label.setTextAlign(Paint.Align.CENTER);
        label.setLetterSpacing(0.08f);
    }

    /** Tear the envelope open, then run onRevealed (parent swaps in the revealed content). */
    public void tear(final Runnable onRevealed) {
        if (torn) { if (onRevealed != null) onRevealed.run(); return; }
        torn = true;
        Sfx.seal();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(450);
        anim.setInterpolator(new OvershootInterpolator(1.1f));
        anim.addUpdateListener(an -> { tear = (float) an.getAnimatedValue(); invalidate(); });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                anim = null;
                if (onRevealed != null && getParent() != null) onRevealed.run();   // skip if recycled
            }
        });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) { anim.cancel(); anim = null; }
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), dp(96));
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float rad = dp(14);
        r.set(dp(1), dp(1), w - dp(1), h - dp(1));
        cv.drawRoundRect(r, rad, rad, body);
        cv.drawRoundRect(r, rad, rad, hair);

        // Flap: a downward V from the top corners to a mid point, rotating up around the top edge as it tears.
        float openDeg = -150f * tear;                 // 0 → -150° (lifts away)
        cv.save();
        cv.rotate(openDeg, w / 2f, dp(1));
        tri.reset();
        tri.moveTo(dp(1), dp(1));
        tri.lineTo(w - dp(1), dp(1));
        tri.lineTo(w / 2f, h * 0.62f);
        tri.close();
        flap.setAlpha((int) (255 * (1f - 0.15f * tear)));
        cv.drawPath(tri, flap);
        cv.drawPath(tri, hair);
        cv.restore();

        // Wax dot at the seam, fading as it opens.
        wax.setAlpha((int) (255 * (1f - tear)));
        cv.drawCircle(w / 2f, h * 0.55f, dp(6), wax);

        // Caption under the seal (only while sealed).
        if (tear < 0.5f) {
            label.setAlpha((int) (255 * (1f - tear * 2f)));
            cv.drawText("SEALED — THEY'VE DECLARED", w / 2f, h - dp(14), label);
        }
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
