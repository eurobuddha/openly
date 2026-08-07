package com.eurobuddha.openly;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.math.BigDecimal;

import com.eurobuddha.comms.NodeApi;

/**
 * Post a proposition. Fields: proposition, side (TRUE/FALSE), my stake, they-must-stake, arbiter
 * (pk/addr/commsid), timeout. Live escrow + implied-odds captions. Grain-validated CTA.
 */
public class CreateView extends BaseView {

    // Remember the last arbiter used so the next wager is pre-populated (edit or clear any time).
    private static final String PREFS = "openly_arbiter";

    private EditText propIn, stakeIn, wantIn, arbPkIn, arbAddrIn, arbCommsIn;
    private int side = 1;
    private TextView sideTrue, sideFalse, escrowCap, oddsCap, statusTv;
    private TextView cta;
    private int timeout = 200;

    private boolean lastDark;

    public CreateView(MainActivity a) {
        super(a, new ScrollView(a));
        buildForm();
        lastDark = Design.isDark();
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setInputType(type);
        e.setTextColor(Design.TEXT());
        e.setHintTextColor(Design.DIM2());
        e.setTextSize(15);
        e.setTypeface(Design.sans());
        e.setBackground(Design.roundBg(act, Design.SURFACE2(), 14));
        int p = Ui.dp(act, 12);
        e.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 6);
        e.setLayoutParams(lp);
        return e;
    }

    private void field(LinearLayout col, String label, EditText in) {
        TextView l = Ui.label(act, label);
        Ui.topMargin(l, Ui.dp(act, 14));
        col.addView(l);
        col.addView(in);
    }

    private void buildForm() {
        ScrollView sv = (ScrollView) root;
        sv.removeAllViews();                 // rebuildable so the theme toggle repaints the whole form
        sv.setBackgroundColor(Design.BG());
        LinearLayout col = Ui.col(act);
        int p = Ui.dp(act, 16);
        col.setPadding(p, p, p, p);
        sv.addView(col);

        col.addView(Ui.text(act, "Post a proposition", Design.TEXT(), 20, true));

        propIn = input("e.g. BTC above 80k by Friday", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field(col, "Proposition", propIn);

        // side toggle
        TextView sl = Ui.label(act, "My side");
        Ui.topMargin(sl, Ui.dp(act, 14));
        col.addView(sl);
        LinearLayout toggle = Ui.row(act);
        Ui.topMargin(toggle, Ui.dp(act, 6));
        sideTrue = seg("TRUE", 1);
        sideFalse = seg("FALSE", 0);
        LinearLayout.LayoutParams w1 = Ui.weight(1); w1.rightMargin = Ui.dp(act, 6);
        toggle.addView(sideTrue, w1);
        toggle.addView(sideFalse, Ui.weight(1));
        col.addView(toggle);
        paintSide();

        stakeIn = input("10", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field(col, "My stake (MINIMA)", stakeIn);
        escrowCap = Ui.money(act, "", Design.GOLD(), 12, false);
        Ui.topMargin(escrowCap, Ui.dp(act, 4));
        col.addView(escrowCap);

        wantIn = input("10", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field(col, "They must stake", wantIn);
        oddsCap = Ui.money(act, "", Design.DIM(), 12, false);
        Ui.topMargin(oddsCap, Ui.dp(act, 4));
        col.addView(oddsCap);

        arbPkIn = input("0x…", InputType.TYPE_CLASS_TEXT);
        field(col, "Arbiter public key", arbPkIn);
        arbAddrIn = input("0x…", InputType.TYPE_CLASS_TEXT);
        field(col, "Arbiter address", arbAddrIn);
        arbCommsIn = input("0x… (optional)", InputType.TYPE_CLASS_TEXT);
        field(col, "Arbiter comms id — enables arbiter chat", arbCommsIn);

        // Sticky arbiter: pre-fill from the last wager so you don't re-paste every time.
        android.content.SharedPreferences ap = act.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        arbPkIn.setText(ap.getString("pk", ""));
        arbAddrIn.setText(ap.getString("addr", ""));
        arbCommsIn.setText(ap.getString("commsid", ""));

        TextWatcher w = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { updateCaptions(); }
            public void afterTextChanged(Editable s) {}
        };
        stakeIn.addTextChangedListener(w);
        wantIn.addTextChangedListener(w);

        statusTv = Ui.text(act, "", Design.NEG(), 12, false);
        Ui.topMargin(statusTv, Ui.dp(act, 12));
        col.addView(statusTv);

        cta = Ui.button(act, "POST", Design.ACCENT(), Design.ON_ACCENT(), true);
        Ui.topMargin(cta, Ui.dp(act, 12));
        cta.setOnClickListener(v -> submit());
        col.addView(cta);
        updateCaptions();
    }

    private TextView seg(String txt, int s) {
        TextView t = Ui.text(act, txt, Design.TEXT(), 14, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 12));
        Design.pressable(t);
        t.setOnClickListener(v -> { side = s; paintSide(); updateCaptions(); });
        return t;
    }

    private void paintSide() {
        boolean t = side == 1;
        sideTrue.setBackground(Design.stroked(act, t ? Design.TRUE_SOFT() : Design.SURFACE2(),
                t ? Design.TRUE_C() : Design.BORDER(), 14));
        sideTrue.setTextColor(t ? Design.TRUE_C() : Design.DIM());
        sideFalse.setBackground(Design.stroked(act, !t ? Design.FALSE_SOFT() : Design.SURFACE2(),
                !t ? Design.FALSE_C() : Design.BORDER(), 14));
        sideFalse.setTextColor(!t ? Design.FALSE_C() : Design.DIM());
    }

    private BigDecimal parse(EditText e) {
        try { return Num.of(e.getText().toString().trim()); } catch (Exception ex) { return null; }
    }

    private void updateCaptions() {
        BigDecimal stake = parse(stakeIn);
        if (stake != null && stake.signum() > 0) {
            BigDecimal lock = Num.lock(stake);
            BigDecimal esc = Num.sub(lock, stake);
            escrowCap.setText("Locks " + Num.plain(lock) + " (" + Num.plain(stake) + " + "
                    + Num.plain(esc) + " escrow)");
        } else escrowCap.setText("");
        BigDecimal want = parse(wantIn);
        if (stake != null && want != null && stake.signum() > 0 && want.signum() > 0) {
            BigDecimal mult = want.divide(stake, Num.MC);
            String other = side == 1 ? "FALSE" : "TRUE";
            oddsCap.setText(Num.ratio(stake, want) + "  ·  " + Num.plain(stake) + " wants "
                    + Num.plain(want) + "  ·  they get " + Num.plain(mult.stripTrailingZeros())
                    + "× if " + other);
        } else oddsCap.setText("");
    }

    private void submit() {
        if (act.identityOrphaned) { fail("Your node's seed no longer matches this app — new bets are blocked. Reinstall Openly."); return; }
        String prop = propIn.getText().toString().trim();
        BigDecimal stake = parse(stakeIn), want = parse(wantIn);
        String arbpk = arbPkIn.getText().toString().trim();
        String arbaddr = arbAddrIn.getText().toString().trim();
        final String arbcommsid = arbCommsIn.getText().toString().trim();
        if (prop.isEmpty()) { fail("Enter a proposition"); return; }
        if (!Num.validStake(stake)) { fail("Stake must be ≥ 0.1 in 0.01 steps"); return; }
        if (!Num.validStake(want)) { fail("They-must-stake must be ≥ 0.1 in 0.01 steps"); return; }
        if (!Util.isValidAddress(arbpk) || !Util.isValidAddress(arbaddr)) { fail("Enter valid arbiter key + address"); return; }
        // A real comms id is boxPk‖signPk = 64 bytes → 0x + 128 hex. Require the full width so a
        // truncated paste can't pin a value that silently fails arbiter-chat auth on the parties.
        if (!arbcommsid.isEmpty() && !arbcommsid.matches("^0x[0-9a-fA-F]{128,}$")) {
            fail("Arbiter comms id looks wrong — paste the full value, or leave blank"); return;
        }
        // Sticky arbiter: remember it as soon as the fields are valid (not only on a confirmed post),
        // so a cancelled or failed attempt still leaves it pre-filled for next time.
        act.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString("pk", arbpk).putString("addr", arbaddr)
                .putString("commsid", arbcommsid).apply();
        statusTv.setTextColor(Design.DIM());
        statusTv.setText("Posting…");
        cta.setEnabled(false);

        final BigDecimal fStake = stake, fWant = want;
        // fresh 32-byte nonce, then post
        act.node().cmd("random size:32", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                String nonce = r.optJSONObject("response") != null
                        ? r.optJSONObject("response").optString("random", "") : "";
                if (nonce.isEmpty()) { fail("nonce failed"); cta.setEnabled(true); return; }
                act.txn.post(prop, side, fStake, fWant, arbpk, arbaddr, arbcommsid, timeout, 0, nonce,
                        new OpenlyTxn.Done() {
                            public void ok() {
                                Sfx.lock();
                                statusTv.setTextColor(Design.TRUE_C());
                                statusTv.setText("Posted — confirming on-chain (~1–2 min)");
                                cta.setEnabled(true);
                                // Watch it land: MY BETS shows an animated "confirming" card until the coin appears.
                                act.addPendingPost(nonce, prop, side, fStake, fWant, "POST", null);
                                propIn.setText(""); stakeIn.setText(""); wantIn.setText("");
                                act.toast("Bet posted — see it in My Bets");
                                act.goToTab(1);   // leave POST for MY BETS; the open bet lands there on scan
                            }
                            public void fail(String msg) { fail("Post failed: " + msg); cta.setEnabled(true); }
                        });
            }
            public void onError(String e) { fail("nonce error"); cta.setEnabled(true); }
        });
    }

    private void fail(String msg) {
        statusTv.setTextColor(Design.NEG());
        statusTv.setText(msg);
    }

    // Rebuild ONLY when the theme actually changed, so a toggle repaints every input/caption/CTA but an
    // ordinary tab-select doesn't wipe text the user is typing.
    @Override public void refresh() {
        if (Design.isDark() == lastDark) return;
        lastDark = Design.isDark();
        buildForm();
    }
}
