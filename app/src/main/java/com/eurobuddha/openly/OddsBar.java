package com.eurobuddha.openly;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * The living odds bar — a split capsule, TRUE (teal) from the left, FALSE (magenta) from the
 * right, with a hairline gap at the split. The split animates when new counters/fills arrive on
 * a block, so the board reads as a market, not a list.
 *
 * setOdds(truePct) drives the split; call with animate=true from onNewBlock().
 */
public class OddsBar extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float truePct = 0.5f;     // rendered split
    private float targetPct = 0.5f;
    private boolean hasBets = false;
    private String trueLabel = "", falseLabel = "";
    private final Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);

    public OddsBar(Context c) {
        super(c);
        textP.setTypeface(Design.monoBold());
        textP.setTextSize(Design.dp(c, 11));
    }

    /** tp = TRUE fraction in [0,1]; labels are the money asks shown inset. */
    public void setOdds(float tp, String tLabel, String fLabel, boolean bets, boolean animate) {
        this.targetPct = Math.max(0.08f, Math.min(0.92f, tp));
        this.trueLabel = tLabel;
        this.falseLabel = fLabel;
        this.hasBets = bets;
        if (!animate) { this.truePct = targetPct; invalidate(); return; }
        ValueAnimator a = ValueAnimator.ofFloat(this.truePct, targetPct);
        a.setDuration(350);
        a.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        a.addUpdateListener(u -> { this.truePct = (float) u.getAnimatedValue(); invalidate(); });
        a.start();
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Design.dp(getContext(), 30));
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        float rad = h / 2f;
        if (!hasBets) {
            fill.setColor(Design.SURFACE2());
            r.set(0, 0, w, h);
            cv.drawRoundRect(r, rad, rad, fill);
            textP.setColor(Design.DIM());
            textP.setTextAlign(Paint.Align.CENTER);
            cv.drawText("NO TAKERS YET", w / 2f, h / 2f + textP.getTextSize() / 3f, textP);
            return;
        }
        int gap = Design.dp(getContext(), 3);
        float split = w * truePct;
        // TRUE (left)
        fill.setColor(Design.TRUE_C());
        r.set(0, 0, split - gap / 2f, h);
        cv.drawRoundRect(r, rad, rad, fill);
        // FALSE (right)
        fill.setColor(Design.FALSE_C());
        r.set(split + gap / 2f, 0, w, h);
        cv.drawRoundRect(r, rad, rad, fill);
        // labels
        textP.setColor(Design.ON_ACCENT());
        textP.setTextAlign(Paint.Align.LEFT);
        float ty = h / 2f + textP.getTextSize() / 3f;
        if (split > Design.dp(getContext(), 64)) cv.drawText(trueLabel, Design.dp(getContext(), 10), ty, textP);
        textP.setTextAlign(Paint.Align.RIGHT);
        if (w - split > Design.dp(getContext(), 64)) cv.drawText(falseLabel, w - Design.dp(getContext(), 10), ty, textP);
    }
}
