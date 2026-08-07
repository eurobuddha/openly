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
 * WON / LOST / VOID / REFUNDED with the net balance change (gold count-up) and, at equal billing,
 * the exact money IN and money OUT plus a path-specific context line (escrow returned · arbiter fee ·
 * timeout refund). Celebratory (teal) on a win, dignified (dim) on a loss, gold on a void/refund.
 * One tap to dismiss. Numbers are exact {@link Num} values, identical to the on-chain payout.
 *
 * path drives the money math: SELF (2-of-2, 0% fee) · ARBITER (winner gets pot−10%, loser forfeits
 * everything) · TIMEOUT (both stakes refunded).
 */
public class SettleResult extends Dialog {

    public static final String SELF = "SELF", ARBITER = "ARBITER", TIMEOUT = "TIMEOUT";

    private final MainActivity act;
    private final String path;
    private final boolean confirmed;
    private final boolean won, voided;
    private final BigDecimal moneyIn, moneyOut, net, fee, escrowBack, myLock, theirLock;
    private final int mySide;

    /** Self-settle convenience (0% fee). */
    public SettleResult(MainActivity a, Bet b, int outcome, boolean confirmed) {
        this(a, b, outcome, confirmed, SELF);
    }

    public SettleResult(MainActivity a, Bet b, int outcome, boolean confirmed, String path) {
        super(a, android.R.style.Theme_Translucent_NoTitleBar);
        this.act = a;
        this.confirmed = confirmed;
        this.path = path == null ? SELF : path;
        this.mySide = Payouts.mySide(b);
        this.myLock = Payouts.myLock(b);
        this.theirLock = Payouts.theirLock(b);

        boolean refund = TIMEOUT.equals(this.path);
        this.voided = refund || outcome == 2;
        this.won = !voided && outcome == mySide;

        this.moneyIn = Payouts.moneyIn(b, outcome, this.path);
        this.moneyOut = myLock;
        this.fee = ARBITER.equals(this.path) ? Payouts.fee(b) : BigDecimal.ZERO;
        this.escrowBack = won ? Num.loserEscrow(theirLock) : Num.loserEscrow(myLock);
        this.net = Num.sub(moneyIn, myLock);
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
        View root = build();
        setContentView(root);
        act.performHapticFeedback();
        // The moment: confetti + rising sine on a win; a soft neutral tone otherwise (never a sad trombone).
        if (won && !voided) { Confetti.burstOnDialog(this); Sfx.settleWin(); }
        else { Sfx.settleEnd(); }
    }

    private View build() {
        int accent = voided ? Design.GOLD() : (won ? Design.TRUE_C() : Design.NEG());
        String headline = TIMEOUT.equals(path) ? "REFUNDED"
                : voided ? "VOID" : (won ? "YOU WON" : "YOU LOST");

        LinearLayout wrap = Ui.col(act);
        wrap.setGravity(Gravity.CENTER);
        wrap.setBackgroundColor(0xCC000000);       // dim scrim so the card reads as a modal, not floating
        int m = Ui.dp(act, 28);
        wrap.setPadding(m, m, m, m);
        wrap.setOnClickListener(v -> dismiss());   // tap the dim to dismiss

        LinearLayout card = Ui.col(act);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(Design.SURFACE2());
        cardBg.setCornerRadius(Ui.dp(act, 24));
        cardBg.setStroke(Ui.dp(act, 1), (accent & 0x00FFFFFF) | 0x55000000);   // ~33% accent hairline
        card.setBackground(cardBg);
        card.setElevation(Ui.dp(act, 16));
        int p = Ui.dp(act, 24);
        card.setPadding(p, Ui.dp(act, 30), p, Ui.dp(act, 24));
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setOnClickListener(v -> {});          // swallow taps on the card

        // Path badge — makes it unmistakable HOW it settled.
        String pathWord = ARBITER.equals(path) ? "ARBITER DECISION"
                : TIMEOUT.equals(path) ? "TIMEOUT REFUND" : "SELF-SETTLED";
        TextView badge = Ui.text(act, pathWord, ARBITER.equals(path) ? Design.GOLD() : Design.DIM(), 10, true);
        badge.setLetterSpacing(0.18f);
        card.addView(badge);

        TextView head = Ui.text(act, headline, accent, 15, true);
        head.setLetterSpacing(0.16f);
        Ui.topMargin(head, Ui.dp(act, 4));
        card.addView(head);

        // Big net-change number (gold count-up). Sign reflects the real balance change.
        String sign = net.signum() > 0 ? "+" : (net.signum() < 0 ? "−" : "");
        final TextView big = Ui.money(act, sign + "0.00", accent, 46, true);
        Ui.topMargin(big, Ui.dp(act, 8));
        card.addView(big);
        countUp(big, sign, net.abs());

        TextView potL = Ui.money(act, "net change to your balance", Design.DIM(), 11, false);
        Ui.topMargin(potL, Ui.dp(act, 4));
        card.addView(potL);

        // Money IN / OUT at equal billing — the "what came in, what went out" the reveal must show.
        LinearLayout detail = Ui.col(act);
        detail.setBackground(Design.roundBg(act, Design.SURFACE(), 14));
        int dp = Ui.dp(act, 14);
        detail.setPadding(dp, dp, dp, dp);
        Ui.topMargin(detail, Ui.dp(act, 18));
        detail.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        detail.addView(kv("Money in", "+" + Num.plain(moneyIn) + " M", Design.TRUE_C()));
        detail.addView(kv("Money out", "−" + Num.plain(moneyOut) + " M", Design.NEG()));
        detail.addView(context());
        card.addView(detail);

        TextView status = Ui.text(act, confirmed ? "Settled on-chain" : "Confirming on-chain…",
                Design.DIM(), 11, false);
        status.setGravity(Gravity.CENTER);
        Ui.topMargin(status, Ui.dp(act, 14));
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

    /** The path-specific teaching line — escrow returned · arbiter fee · timeout refund. */
    private TextView context() {
        String s; int color;
        if (TIMEOUT.equals(path)) {
            s = "Timeout — both stakes returned in full"; color = Design.GOLD();
        } else if (ARBITER.equals(path)) {
            s = won ? "Arbiter ruled your way · fee −" + Num.plain(fee) + " M taken from the pot"
                    : "Arbiter ruled against you · escrow forfeited"; color = Design.GOLD();
        } else if (voided) {
            s = "Void — both stakes returned in full"; color = Design.GOLD();
        } else if (won) {
            s = "Self-settled · 0% fee · your " + Num.plain(myLock) + " back + winnings"; color = Design.GOLD();
        } else {
            s = "Self-settled · your escrow " + Num.plain(escrowBack) + " M returned (honest declare)"; color = Design.GOLD();
        }
        TextView t = Ui.text(act, s, color, 11, false);
        Ui.topMargin(t, Ui.dp(act, 8));
        return t;
    }

    private View kv(String k, String v, int valColor) {
        LinearLayout row = Ui.row(act);
        Ui.topMargin(row, Ui.dp(act, 2));
        TextView kk = Ui.text(act, k, Design.DIM(), 12, false);
        row.addView(kk, Ui.weight(1));
        row.addView(Ui.money(act, v, valColor, 13, true));
        return row;
    }

    /** Count-up to the net magnitude (0 → |net| over 0.7s), then snap to the exact Num value. */
    private void countUp(final TextView t, final String sign, final BigDecimal target) {
        final double tv = target.doubleValue();
        if (tv <= 0) { t.setText(sign + Num.plain(target)); return; }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(700);
        a.addUpdateListener(an -> t.setText(sign + money(tv * (float) an.getAnimatedValue())));
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator an) {
                t.setText(sign + Num.plain(target));
                Design.pulse(t, t.getCurrentTextColor());   // a beat of emphasis as the number lands
            }
        });
        a.start();
    }

    private static String money(double d) {
        return new BigDecimal(d).setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }
}
