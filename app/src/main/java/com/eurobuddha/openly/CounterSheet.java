package com.eurobuddha.openly;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.math.BigDecimal;

import com.eurobuddha.comms.NodeApi;

/**
 * Accept / Counter an open bet. The slider sets MY STAKE, from a floor up to the full ask.
 *
 *  - At the full ask (right)  → ACCEPT: fill the existing bet at their terms.
 *  - Below the full ask (left) → COUNTER: post my own opposite-side bet with a lower stake for the
 *    same win — a better-priced offer the poster (or anyone) can take.
 *
 * I always win the poster's stake; I risk my slider stake. Scrollable + dismissible (tap outside or
 * the Close row), padded above the nav bar.
 */
public class CounterSheet extends Dialog {

    private final MainActivity act;
    private final Bet bet;
    private final BigDecimal theirStake;   // poster's stake — what I WIN if I win
    private final BigDecimal fullAsk;      // poster's wantstake — the max I'd stake (accept point)
    private final int mySide;              // opposite of the poster

    private SeekBar slider;
    private EditText valInput;                 // the big number — now typeable
    private TextView escrowLabel, termsWin, termsLose, cta, status, modeLabel;
    private BigDecimal currentStake;           // single source of truth (slider + field both drive this)
    private boolean syncing = false;           // re-entrancy guard so field↔slider updates don't loop
    private static final int STEPS = 1000;

    public CounterSheet(MainActivity a, Bet bet) {
        super(a, android.R.style.Theme_Translucent_NoTitleBar);
        this.act = a;
        this.bet = bet;
        this.theirStake = bet.ownerBet();
        this.fullAsk = bet.counterBet();
        this.mySide = bet.side == 1 ? 0 : 1;
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(true);   // tap the dimmed area to dismiss
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        setContentView(build());
        applyStake(fullAsk, false);   // start pinned at the full ask (Accept)
    }

    private View build() {
        // Cap the sheet at ~88% of screen height and make it scroll, so the CTA is always reachable.
        ScrollView scroll = new ScrollView(act) {
            @Override protected void onMeasure(int wSpec, int hSpec) {
                int max = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.88f);
                super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroll.setBackground(Design.roundBg(act, Design.SURFACE(), 24));

        LinearLayout sheet = Ui.col(act);
        int p = Ui.dp(act, 20);
        int navPad = Ui.dp(act, 28);   // clear the system nav bar
        sheet.setPadding(p, Ui.dp(act, 10), p, navPad);
        scroll.addView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // drag handle (tap to dismiss)
        View handle = new View(act);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL; hlp.bottomMargin = Ui.dp(act, 12);
        handle.setLayoutParams(hlp);
        handle.setBackground(Design.roundBg(act, Design.SURFACE3(), 999));
        handle.setOnClickListener(v -> dismiss());
        sheet.addView(handle);

        String theirSide = bet.side == 1 ? "TRUE" : "FALSE";
        String mySideWord = mySide == 1 ? "TRUE" : "FALSE";
        sheet.addView(Ui.text(act, bet.proposition.isEmpty() ? "Bet" : bet.proposition, Design.TEXT(), 15, true));
        sheet.addView(Ui.text(act, "They bet " + Num.plain(theirStake) + " " + theirSide
                + " · you'd bet " + mySideWord, Design.DIM(), 12, false));

        modeLabel = Ui.label(act, "");
        Ui.topMargin(modeLabel, Ui.dp(act, 16)); sheet.addView(modeLabel);

        // The big number is now an editable field — type an exact stake (e.g. "4") when the slider
        // can't land on it. Styled like the old money label (mono-bold, side colour, no box).
        valInput = new EditText(act);
        valInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        valInput.setTextColor(Design.sideColor(mySide));
        valInput.setTypeface(Design.monoBold());
        valInput.setTextSize(34);
        valInput.setGravity(Gravity.CENTER_VERTICAL);
        // Underlined box so it reads as a tap-to-type field (not a static number), with a caption below.
        valInput.setBackground(Design.roundBg(act, Design.SURFACE2(), 12));
        int vp = Ui.dp(act, 12);
        valInput.setPadding(vp, Ui.dp(act, 6), vp, Ui.dp(act, 6));
        valInput.setCursorVisible(true);
        valInput.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        valInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                if (syncing) return;
                BigDecimal v;
                try { v = s.toString().trim().isEmpty() ? Num.GRAIN : Num.of(s.toString().trim()); }
                catch (Exception e) { return; }              // partial input ("." / "4.") — wait for valid
                applyStake(clamp(v, Num.GRAIN), true);        // typed values snap only to the 0.01 grain
            }
        });
        sheet.addView(valInput);
        TextView hint = Ui.text(act, "tap to type an exact amount · slider snaps to round steps",
                Design.DIM2(), 10, false);
        Ui.topMargin(hint, Ui.dp(act, 4));
        sheet.addView(hint);
        escrowLabel = Ui.money(act, "", Design.GOLD(), 12, false);
        Ui.topMargin(escrowLabel, Ui.dp(act, 8));
        sheet.addView(escrowLabel);

        slider = new SeekBar(act);
        slider.setMax(STEPS);
        slider.setProgress(STEPS);   // start at the full ask (Accept)
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(act, 16);
        slider.setLayoutParams(slp);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                if (syncing || !fromUser) return;             // ignore programmatic sync-back
                act.performHapticFeedback();
                BigDecimal raw = fullAsk.multiply(new BigDecimal(prog / (double) STEPS), Num.MC);
                applyStake(clamp(raw, grid()), false);        // slider snaps to the coarse grid
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        sheet.addView(slider);

        LinearLayout ends = Ui.row(act);
        ends.addView(Ui.text(act, "← counter (offer less)", Design.DIM2(), 10, false), Ui.weight(1));
        TextView takeEnd = Ui.text(act, "accept " + Num.plain(fullAsk), Design.DIM2(), 10, false);
        ends.addView(takeEnd);
        sheet.addView(ends);

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
        Ui.topMargin(cta, Ui.dp(act, 10));
        cta.setOnClickListener(v -> submit());
        sheet.addView(cta);

        TextView close = Ui.text(act, "Close", Design.DIM(), 13, true);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, Ui.dp(act, 14), 0, 0);
        close.setOnClickListener(v -> dismiss());
        sheet.addView(close);

        return scroll;
    }

    /** Notch grid for the SLIDER, sized by range so dragging lands on round numbers: ≥20→1, ≥5→0.5,
     *  ≥1→0.1, else 0.01. The FIELD always uses the finer 0.01 grain for exact typed entry. */
    private BigDecimal grid() {
        if (fullAsk.compareTo(new BigDecimal("20")) >= 0) return new BigDecimal("1");
        if (fullAsk.compareTo(new BigDecimal("5"))  >= 0) return new BigDecimal("0.5");
        if (fullAsk.compareTo(new BigDecimal("1"))  >= 0) return new BigDecimal("0.1");
        return Num.GRAIN;
    }

    /** Clamp to [0.01, fullAsk] and snap to grid g. Typing above the ask = accept at the full ask. */
    private BigDecimal clamp(BigDecimal v, BigDecimal g) {
        if (v == null) return Num.GRAIN;
        if (v.compareTo(fullAsk) > 0) v = fullAsk;
        v = v.divide(g, 0, java.math.RoundingMode.HALF_UP).multiply(g);
        if (v.compareTo(Num.GRAIN) < 0) v = Num.GRAIN;
        if (v.compareTo(fullAsk) > 0) v = fullAsk;
        return v;
    }

    private boolean isAccept(BigDecimal stake) {
        return stake.subtract(fullAsk).abs().compareTo(new BigDecimal("0.01")) < 0;
    }

    /** Set the (already clamped/snapped) stake and reflect it into the slider, the field (unless the
     *  field is the source — preserve the caret), and the terms labels. Guarded against sync loops. */
    private void applyStake(BigDecimal stake, boolean fromField) {
        currentStake = stake;
        syncing = true;
        double frac = fullAsk.signum() > 0 ? stake.divide(fullAsk, Num.MC).doubleValue() : 1.0;
        int prog = (int) Math.round(frac * STEPS);
        slider.setProgress(Math.max(0, Math.min(STEPS, prog)));
        if (!fromField) {
            valInput.setText(Num.plain(stake));
            valInput.setSelection(valInput.getText().length());
        }
        syncing = false;
        updateLabels();
    }

    private void updateLabels() {
        boolean accept = isAccept(currentStake);
        BigDecimal lock = Num.lock(currentStake);
        modeLabel.setText(accept ? "Accept — your stake" : "Counter — your stake");
        escrowLabel.setText("+ 25% escrow → locks " + Num.plain(lock));
        termsWin.setText("Win  +" + Num.plain(theirStake));
        termsLose.setText("Lose  −" + Num.plain(currentStake));
        cta.setText(accept
                ? "ACCEPT — lock " + Num.plain(lock)
                : "COUNTER — lock " + Num.plain(lock));
    }

    private void submit() {
        final BigDecimal stake = currentStake;
        boolean accept = isAccept(stake);
        cta.setEnabled(false);
        status.setTextColor(Design.DIM());
        status.setText(accept ? "Accepting…" : "Posting counter…");

        if (accept) {
            act.txn.fill(bet, new OpenlyTxn.Done() {
                public void ok() {
                    act.scanner.markFilled(bet.nonce);   // hide the open coin now (spent, but unconfirmed ~1-2 min)
                    act.toast("Accepted — confirming on-chain"); act.refreshCurrent(); dismiss();
                }
                public void fail(String m) { statusFail("Accept failed: " + m); }
            });
        } else {
            act.node().cmd("random size:32", new NodeApi.Cb() {
                public void onResult(JSONObject r) {
                    String nonce = r.optJSONObject("response") != null
                            ? r.optJSONObject("response").optString("random", "") : "";
                    if (nonce.isEmpty()) { statusFail("nonce failed"); return; }
                    // Counter: bet my side, staking `stake`, wanting the poster's stake.
                    act.txn.post(bet.proposition, mySide, stake, theirStake,
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
        act.toast(m);
    }
}
