package com.eurobuddha.openly;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The settlement payoff moment — the "settlement, not jackpot" reveal from the design spec.
 * WON / LOST / VOID with the net amount (gold count-up), the pot, and the escrow-returned line at
 * equal billing. Celebratory (teal) on a win, dignified (dim) on a loss, gold on a void. One tap to
 * dismiss. Numbers are exact {@link Num} values, identical to the contract payout.
 */
public class SettleResult extends Dialog {

    private final MainActivity act;
    private final int outcome;                 // 0 FALSE · 1 TRUE · 2 VOID
    private final int mySide;
    private final BigDecimal myStake, theirStake, pot;
    private final boolean confirmed;           // true = coin spent on-chain; false = posted, confirming

    public SettleResult(MainActivity a, Bet b, int outcome, boolean confirmed) {
        super(a, android.R.style.Theme_Translucent_NoTitleBar);
        this.act = a;
        this.outcome = outcome;
        boolean iAmOwner = b.isMine;
        this.mySide = (b.side == 1 && iAmOwner) || (b.side == 0 && !iAmOwner) ? 1 : 0;
        this.myStake = iAmOwner ? b.ownerBet() : b.counterBet();
        this.theirStake = iAmOwner ? b.counterBet() : b.ownerBet();
        this.pot = Num.add(myStake, theirStake);
        this.confirmed = confirmed;
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(true);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setGravity(Gravity.CENTER);
        }
        setContentView(build());
        act.performHapticFeedback();
    }

    private View build() {
        boolean voided = outcome == 2;
        boolean won = !voided && outcome == mySide;
        int accent = voided ? Design.GOLD() : (won ? Design.TRUE_C() : Design.NEG());

        LinearLayout wrap = Ui.col(act);
        wrap.setGravity(Gravity.CENTER);
        int m = Ui.dp(act, 28);
        wrap.setPadding(m, m, m, m);
        wrap.setOnClickListener(v -> dismiss());   // tap the dim to dismiss

        LinearLayout card = Ui.col(act);
        card.setBackground(Design.roundBg(act, Design.SURFACE(), 24));
        int p = Ui.dp(act, 24);
        card.setPadding(p, Ui.dp(act, 30), p, Ui.dp(act, 24));
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setOnClickListener(v -> {});          // swallow taps on the card

        TextView head = Ui.text(act, voided ? "VOID" : (won ? "YOU WON" : "YOU LOST"), accent, 13, true);
        head.setLetterSpacing(0.20f);
        card.addView(head);

        BigDecimal net = voided ? BigDecimal.ZERO : (won ? theirStake : myStake);
        String sign = voided ? "" : (won ? "+" : "−");   // − minus
        final TextView big = Ui.money(act, sign + "0.00", accent, 46, true);
        Ui.topMargin(big, Ui.dp(act, 8));
        card.addView(big);
        countUp(big, sign, net);

        TextView potL = Ui.money(act, "pot " + Num.plain(pot) + " M", Design.DIM(), 13, false);
        Ui.topMargin(potL, Ui.dp(act, 6));
        card.addView(potL);

        LinearLayout detail = Ui.col(act);
        detail.setBackground(Design.roundBg(act, Design.SURFACE2(), 14));
        int dp = Ui.dp(act, 14);
        detail.setPadding(dp, dp, dp, dp);
        Ui.topMargin(detail, Ui.dp(act, 20));
        detail.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (voided) {
            detail.addView(Ui.money(act, "Void — both stakes returned in full", Design.GOLD(), 13, false));
            detail.addView(sub("you got back " + Num.plain(myStake) + " M"));
        } else if (won) {
            BigDecimal received = Num.sub(pot, Num.loserEscrow(Num.lock(theirStake)));
            detail.addView(Ui.money(act, "You received " + Num.plain(received) + " M", Design.TRUE_C(), 13, false));
            detail.addView(sub("your " + Num.plain(myStake) + " stake back + " + Num.plain(theirStake) + " winnings"));
        } else {
            BigDecimal esc = Num.loserEscrow(Num.lock(myStake));
            detail.addView(Ui.money(act, "Escrow " + Num.plain(esc) + " M returned to you", Design.GOLD(), 13, false));
            detail.addView(sub("you staked " + Num.plain(myStake) + " on " + (mySide == 1 ? "TRUE" : "FALSE")));
        }
        card.addView(detail);

        TextView status = Ui.text(act, confirmed ? "Settled on-chain · 0% fee"
                : "Posted · confirming on-chain…", Design.DIM(), 11, false);
        status.setGravity(Gravity.CENTER);
        Ui.topMargin(status, Ui.dp(act, 16));
        card.addView(status);

        TextView done = Ui.button(act, "Done", accent, Design.ON_ACCENT(), true);
        Ui.topMargin(done, Ui.dp(act, 16));
        done.setOnClickListener(v -> dismiss());
        card.addView(done);

        int wide = Math.min(Ui.dp(act, 360),
                (int) (act.getResources().getDisplayMetrics().widthPixels * 0.88f));
        card.setLayoutParams(new LinearLayout.LayoutParams(wide, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(card);
        return wrap;
    }

    private TextView sub(String s) {
        TextView t = Ui.money(act, s, Design.DIM(), 12, false);
        Ui.topMargin(t, Ui.dp(act, 4));
        return t;
    }

    /** Gold-style count-up to the net amount (0 → net over 0.7s), then snaps to the exact Num value. */
    private void countUp(final TextView t, final String sign, final BigDecimal target) {
        final double tv = target.doubleValue();
        if (tv <= 0) { t.setText(sign + Num.plain(target)); return; }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(700);
        a.addUpdateListener(an -> t.setText(sign + money(tv * (float) an.getAnimatedValue())));
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator an) { t.setText(sign + Num.plain(target)); }
        });
        a.start();
    }

    private static String money(double d) {
        return new BigDecimal(d).setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }
}
