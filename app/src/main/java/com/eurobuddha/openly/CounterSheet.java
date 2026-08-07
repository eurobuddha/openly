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
import android.widget.TextView;

import org.json.JSONObject;

import java.math.BigDecimal;

import com.eurobuddha.comms.NodeApi;

/**
 * Accept or Counter an open bet — explicit about who stakes what, so the money is never a surprise.
 *
 * The offer is "their stake {base} wants {ask}": the poster staked {base} on their side and asks the
 * opposite side to put up {ask} (odds base:ask).
 *
 *  - ACCEPT  → fill their coin at their exact terms: YOU stake {ask}, you win {base}.
 *  - COUNTER → post YOUR OWN opposite-side offer: you set BOTH numbers — what YOU put up and what THEY
 *    put up. You become the owner staking YOUR number (its lock = your number × 1.25), so you can take
 *    the large OR the small side. `post(mySide, youPut, theyPut)` → odds youPut:theyPut.
 *
 * Fixes the old trap where the counterer's own stake was pinned to the poster's (small) stake and the
 * only editable field was actually the OPPONENT's stake.
 */
public class CounterSheet extends Dialog {

    private final MainActivity act;
    private final Bet bet;
    private final BigDecimal base;     // their stake — what you WIN if you accept
    private final BigDecimal ask;      // their want — what you STAKE if you accept
    private final int mySide;          // opposite of the poster

    private EditText youPutIn, theyPutIn;
    private TextView oddsBig, riskLine, escrowLabel, status, counterCta;
    private boolean syncing = false;

    public CounterSheet(MainActivity a, Bet bet) {
        super(a, android.R.style.Theme_Translucent_NoTitleBar);
        this.act = a;
        this.bet = bet;
        this.base = bet.ownerBet();
        this.ask = bet.counterBet();
        this.mySide = bet.side == 1 ? 0 : 1;
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCanceledOnTouchOutside(true);
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        setContentView(build());
        recompute();
    }

    private View build() {
        ScrollView scroll = new ScrollView(act) {
            @Override protected void onMeasure(int wSpec, int hSpec) {
                int max = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.88f);
                super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroll.setBackground(Design.roundBg(act, Design.SURFACE(), 24));

        LinearLayout sheet = Ui.col(act);
        int p = Ui.dp(act, 20);
        sheet.setPadding(p, Ui.dp(act, 10), p, Ui.dp(act, 28));
        scroll.addView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        sheet.addView(Ui.text(act, "They bet " + Num.plain(base) + " " + theirSide + ", want "
                + Num.plain(ask) + "  ·  you'd bet " + mySideWord, Design.DIM(), 12, false));

        // ── ACCEPT their exact terms ──
        LinearLayout acceptBox = Ui.col(act);
        acceptBox.setBackground(Design.roundBg(act, Design.SURFACE2(), 14));
        int abp = Ui.dp(act, 12); acceptBox.setPadding(abp, abp, abp, abp);
        Ui.topMargin(acceptBox, Ui.dp(act, 16));
        TextView acceptCta = Ui.button(act, "ACCEPT — you stake " + Num.plain(ask) + ", win " + Num.plain(base),
                Design.sideColor(mySide), Design.ON_ACCENT(), false);
        acceptCta.setOnClickListener(v -> acceptBet(acceptCta));
        acceptBox.addView(acceptCta);
        TextView acceptSub = Ui.money(act, "you put up " + Num.plain(ask) + " (locks " + Num.plain(Num.lock(ask)) + ")",
                Design.DIM2(), 11, false);
        Ui.topMargin(acceptSub, Ui.dp(act, 6));
        acceptBox.addView(acceptSub);
        sheet.addView(acceptBox);

        TextView divider = Ui.text(act, "— or counter: post your own " + mySideWord + " offer —",
                Design.DIM2(), 11, true);
        divider.setGravity(Gravity.CENTER);
        Ui.topMargin(divider, Ui.dp(act, 18));
        sheet.addView(divider);

        // YOU put up — the editable number is YOUR OWN stake (its lock = ×1.25).
        youPutIn = labeledInput(sheet, "YOU put up", Num.plain(ask));
        // THEY put up — what the other side must stake (your winnings).
        theyPutIn = labeledInput(sheet, "THEY put up", Num.plain(base));

        oddsBig = Ui.money(act, "", Design.sideColor(mySide), 30, true);
        Ui.topMargin(oddsBig, Ui.dp(act, 12));
        sheet.addView(oddsBig);
        riskLine = Ui.money(act, "", Design.TEXT(), 13, false);
        Ui.topMargin(riskLine, Ui.dp(act, 4));
        sheet.addView(riskLine);
        escrowLabel = Ui.money(act, "", Design.GOLD(), 12, false);
        Ui.topMargin(escrowLabel, Ui.dp(act, 6));
        sheet.addView(escrowLabel);

        status = Ui.text(act, "", Design.DIM(), 12, false);
        Ui.topMargin(status, Ui.dp(act, 10)); sheet.addView(status);

        counterCta = Ui.button(act, "", Design.ACCENT(), Design.ON_ACCENT(), true);
        Ui.topMargin(counterCta, Ui.dp(act, 10));
        counterCta.setOnClickListener(v -> postCounter());
        sheet.addView(counterCta);

        TextView close = Ui.text(act, "Close", Design.DIM(), 13, true);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, Ui.dp(act, 14), 0, 0);
        close.setOnClickListener(v -> dismiss());
        sheet.addView(close);

        return scroll;
    }

    private EditText labeledInput(LinearLayout parent, String label, String initial) {
        TextView l = Ui.money(act, label, Design.DIM(), 12, true);
        Ui.topMargin(l, Ui.dp(act, 14));
        parent.addView(l);
        EditText e = new EditText(act);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setTextColor(Design.TEXT());
        e.setTypeface(Design.monoBold());
        e.setTextSize(22);
        e.setText(initial);
        e.setBackground(Design.roundBg(act, Design.SURFACE2(), 12));
        int vp = Ui.dp(act, 12);
        e.setPadding(vp, Ui.dp(act, 8), vp, Ui.dp(act, 8));
        e.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.topMargin(e, Ui.dp(act, 4));
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { if (!syncing) recompute(); }
        });
        parent.addView(e);
        return e;
    }

    private BigDecimal parse(EditText e) {
        try { return Num.of(e.getText().toString().trim()); } catch (Exception ex) { return null; }
    }

    private void recompute() {
        BigDecimal youPut = parse(youPutIn), theyPut = parse(theyPutIn);
        if (youPut == null || theyPut == null || youPut.signum() <= 0 || theyPut.signum() <= 0) {
            oddsBig.setText("—"); riskLine.setText(""); escrowLabel.setText("");
            counterCta.setText("COUNTER"); return;
        }
        oddsBig.setText(Num.ratio(youPut, theyPut));
        riskLine.setText("You risk " + Num.plain(youPut) + " to win " + Num.plain(theyPut));
        escrowLabel.setText("+ 25% escrow → you lock " + Num.plain(Num.lock(youPut)));
        counterCta.setText("COUNTER — you put " + Num.plain(youPut) + ", they put " + Num.plain(theyPut));
    }

    private void acceptBet(TextView btn) {
        if (act.identityOrphaned) { act.toast("Your node's seed changed — betting is blocked. Reinstall Openly."); return; }
        btn.setEnabled(false);
        act.txn.fill(bet, new OpenlyTxn.Done() {
            public void ok() {
                Sfx.lock();
                act.scanner.markFilled(bet.nonce);
                act.toast("Accepted — confirming on-chain"); act.refreshCurrent(); dismiss();
            }
            public void fail(String m) { btn.setEnabled(true); act.toast("Accept failed: " + m); }
        });
    }

    private void postCounter() {
        if (act.identityOrphaned) { statusFail("Your node's seed changed — betting is blocked. Reinstall Openly."); return; }
        final BigDecimal youPut = parse(youPutIn), theyPut = parse(theyPutIn);
        if (!Num.validStake(youPut)) { statusFail("Your stake must be ≥ 0.1 in 0.01 steps"); return; }
        if (!Num.validStake(theyPut)) { statusFail("Their stake must be ≥ 0.1 in 0.01 steps"); return; }
        counterCta.setEnabled(false);
        status.setTextColor(Design.DIM());
        status.setText("Posting counter…");
        act.node().cmd("random size:32", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                String nonce = r.optJSONObject("response") != null
                        ? r.optJSONObject("response").optString("random", "") : "";
                if (nonce.isEmpty()) { statusFail("nonce failed"); return; }
                // Counter: my opposite side, I stake `youPut` (I become owner, my lock = youPut×1.25),
                // and I want `theyPut` from a taker. Odds youPut:theyPut.
                final String fnonce = nonce;
                act.txn.post(bet.proposition, mySide, youPut, theyPut,
                        bet.arbpk, bet.arbaddr, bet.arbcommsid, bet.timeout, bet.settleblock, nonce,
                        new OpenlyTxn.Done() {
                            public void ok() {
                                Sfx.lock();
                                status.setTextColor(Design.ACCENT());
                                status.setText("Confirming on-chain…");
                                Ui.throb(status);
                                counterCta.setText("Confirming on-chain…");
                                act.addPendingPost(fnonce, bet.proposition, mySide, youPut, theyPut, "COUNTER", () -> {
                                    if (!isShowing()) return;
                                    status.setTextColor(Design.TRUE_C());
                                    status.setText("Counter live on-chain ✓");
                                    counterCta.setText("✓ Counter live");
                                    counterCta.postDelayed(() -> { if (isShowing()) dismiss(); }, 1300);
                                });
                                act.refreshCurrent();
                            }
                            public void fail(String m) { statusFail("Counter failed: " + m); }
                        });
            }
            public void onError(String e) { statusFail("nonce error"); }
        });
    }

    private void statusFail(String m) {
        status.setTextColor(Design.NEG());
        status.setText(m);
        counterCta.setEnabled(true);
        act.toast(m);
    }
}
