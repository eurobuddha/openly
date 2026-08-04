package com.eurobuddha.openly;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.math.BigDecimal;

import com.eurobuddha.comms.NodeApi;

/**
 * The Counter/Take surface — a bottom sheet with a stake slider from a floor up to the FULL ASK.
 *
 *  - At the full ask  → TAKE: fill the bet directly (fillBet).
 *  - Below the ask    → COUNTER: post a new bet on the opposite side (my stake = their bet, my
 *    want = slider value). A counter is an offer, not a fill — the CTA reflects that.
 *
 * This is the signature interaction; the CTA morphs TAKE ↔ COUNTER as you leave the ask.
 */
public class CounterSheet extends Dialog {

    private final MainActivity act;
    private final Bet bet;
    private final BigDecimal theirBet;   // displayed stake the owner put up
    private final BigDecimal theirAsk;   // displayed stake they want from me (== full take)

    private SeekBar slider;
    private TextView valLabel, escrowLabel, termsWin, termsLose, cta, status;
    private static final int STEPS = 1000;

    public CounterSheet(MainActivity a, Bet bet) {
        super(a, android.R.style.Theme_Translucent_NoTitleBar);
        this.act = a;
        this.bet = bet;
        this.theirBet = bet.ownerBet();
        this.theirAsk = bet.counterBet();
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        setContentView(build());
        update();
    }

    private View build() {
        LinearLayout sheet = Ui.col(act);
        sheet.setBackground(Design.roundBg(act, Design.SURFACE(), 24));
        int p = Ui.dp(act, 20);
        sheet.setPadding(p, Ui.dp(act, 12), p, p);

        // drag handle
        View handle = new View(act);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(act, 36), Ui.dp(act, 4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL; hlp.bottomMargin = Ui.dp(act, 14);
        handle.setLayoutParams(hlp);
        handle.setBackground(Design.roundBg(act, Design.SURFACE3(), 999));
        sheet.addView(handle);

        String theirSide = bet.side == 1 ? "TRUE" : "FALSE";
        sheet.addView(Ui.text(act, bet.proposition.isEmpty() ? "Bet" : bet.proposition, Design.TEXT(), 15, true));
        sheet.addView(Ui.text(act, "They bet " + Num.plain(theirBet) + " " + theirSide, Design.DIM(), 12, false));

        // center readout
        TextView lbl = Ui.label(act, "Your stake");
        Ui.topMargin(lbl, Ui.dp(act, 16)); sheet.addView(lbl);
        int mySide = bet.side == 1 ? 0 : 1;
        valLabel = Ui.money(act, "", Design.sideColor(mySide), 34, true);
        sheet.addView(valLabel);
        escrowLabel = Ui.money(act, "", Design.GOLD(), 12, false);
        sheet.addView(escrowLabel);

        // slider
        slider = new SeekBar(act);
        slider.setMax(STEPS);
        slider.setProgress(STEPS); // start at full ask
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(act, 16);
        slider.setLayoutParams(slp);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                if (fromUser) act.performHapticFeedback();
                update();
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        sheet.addView(slider);

        LinearLayout ends = Ui.row(act);
        ends.addView(Ui.text(act, "counter", Design.DIM2(), 10, false), Ui.weight(1));
        TextView takeEnd = Ui.text(act, "take " + Num.plain(theirAsk), Design.DIM2(), 10, false);
        ends.addView(takeEnd);
        sheet.addView(ends);

        // terms
        LinearLayout terms = Ui.col(act);
        terms.setBackground(Design.roundBg(act, Design.SURFACE2(), 14));
        int tp = Ui.dp(act, 12); terms.setPadding(tp, tp, tp, tp);
        Ui.topMargin(terms, Ui.dp(act, 16));
        termsWin = Ui.money(act, "", Design.TRUE_C(), 13, false);
        termsLose = Ui.money(act, "", Design.NEG(), 13, false);
        terms.addView(termsWin);
        Ui.topMargin(termsLose, Ui.dp(act, 4));
        terms.addView(termsLose);
        sheet.addView(terms);

        status = Ui.text(act, "", Design.DIM(), 12, false);
        Ui.topMargin(status, Ui.dp(act, 10)); sheet.addView(status);

        cta = Ui.button(act, "", Design.ACCENT(), Design.ON_ACCENT(), true);
        Ui.topMargin(cta, Ui.dp(act, 8));
        cta.setOnClickListener(v -> submit());
        sheet.addView(cta);
        return sheet;
    }

    /** Current slider value in displayed-stake units (0.01 floor .. theirAsk). */
    private BigDecimal sliderValue() {
        double frac = slider.getProgress() / (double) STEPS;
        BigDecimal v = theirAsk.multiply(new BigDecimal(frac), Num.MC);
        if (v.compareTo(Num.GRAIN) < 0) v = Num.GRAIN;
        // snap to 0.01 grain
        return v.divide(Num.GRAIN, 0, java.math.RoundingMode.HALF_UP).multiply(Num.GRAIN);
    }

    private boolean isFullAsk(BigDecimal v) {
        return v.subtract(theirAsk).abs().compareTo(new BigDecimal("0.01")) < 0;
    }

    private void update() {
        BigDecimal myWant = sliderValue();
        BigDecimal myStake = theirBet;                 // my stake is fixed at their bet
        boolean full = isFullAsk(myWant);
        int mySide = bet.side == 1 ? 0 : 1;
        String mySideWord = mySide == 1 ? "TRUE" : "FALSE";

        valLabel.setText(Num.plain(myStake));
        BigDecimal lock = Num.lock(myStake);
        escrowLabel.setText("+ 25% escrow → locks " + Num.plain(lock));
        termsWin.setText("Win " + mySideWord + "  +" + Num.plain(myWant));
        termsLose.setText("Lose  -" + Num.plain(myStake));
        cta.setText(full ? "TAKE — LOCK " + Num.plain(lock)
                         : "COUNTER — LOCK " + Num.plain(lock));
    }

    private void submit() {
        BigDecimal myWant = sliderValue();
        boolean full = isFullAsk(myWant);
        cta.setEnabled(false);
        status.setTextColor(Design.DIM());
        status.setText(full ? "Taking bet…" : "Posting counter…");

        if (full) {
            act.txn.fill(bet, new OpenlyTxn.Done() {
                public void ok() { act.toast("Bet taken — confirming"); act.refreshCurrent(); dismiss(); }
                public void fail(String m) { statusFail("Fill failed: " + m); }
            });
        } else {
            int mySide = bet.side == 1 ? 0 : 1;
            act.node().cmd("random size:32", new NodeApi.Cb() {
                public void onResult(JSONObject r) {
                    String nonce = r.optJSONObject("response") != null
                            ? r.optJSONObject("response").optString("random", "") : "";
                    if (nonce.isEmpty()) { statusFail("nonce failed"); return; }
                    act.txn.post(bet.proposition, mySide, theirBet, myWant,
                            bet.arbpk, bet.arbaddr, bet.timeout, bet.settleblock, nonce,
                            new OpenlyTxn.Done() {
                                public void ok() { act.toast("Counter posted"); act.refreshCurrent(); dismiss(); }
                                public void fail(String m) { statusFail("Counter failed: " + m); }
                            });
                }
                public void onError(String e) { statusFail("nonce error"); }
            });
        }
    }

    private void statusFail(String m) {
        status.setTextColor(Design.NEG());
        status.setText(m);
        cta.setEnabled(true);
    }
}
