package com.eurobuddha.openly;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Arbiter — my shareable identity (so others can name me) + the cases awaiting my decision
 * (matched bets where I am the arbiter). Resolve pays winner 90%, me 10%; loser forfeits escrow.
 */
public class ArbiterView extends BaseView {

    private final LinearLayout list;

    public ArbiterView(MainActivity a) {
        super(a, build(a));
        list = (LinearLayout) ((ScrollView) root).getChildAt(0);
    }

    private static View build(MainActivity a) {
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(Design.BG());
        LinearLayout l = Ui.col(a);
        int p = Ui.dp(a, 16);
        l.setPadding(p, p, p, p);
        sv.addView(l, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    @Override public void refresh() {
        list.removeAllViews();
        list.addView(identityCard());

        // Only DISPUTED cases get resolve buttons — resolving a bet the parties would settle for free
        // would wrongly take the 10% fee + the loser's escrow. Non-disputed matched bets I arbitrate
        // are shown as monitored (no action).
        List<Bet> disputed = new ArrayList<>();
        List<Bet> monitoring = new ArrayList<>();
        for (Bet b : act.scanner.matched) {
            if (!b.isMyArb) continue;
            if (act.scanner.disputedNonces.contains(b.nonce)) disputed.add(b);
            else monitoring.add(b);
        }

        if (disputed.isEmpty() && monitoring.isEmpty()) {
            TextView e = Ui.text(act, "No cases. Share your identity to be named arbiter.",
                    Design.DIM(), 13, false);
            Ui.topMargin(e, Ui.dp(act, 16));
            e.setGravity(Gravity.CENTER);
            list.addView(e);
            return;
        }
        if (!disputed.isEmpty()) {
            TextView h = Ui.label(act, "Disputed — awaiting your decision");
            Ui.topMargin(h, Ui.dp(act, 12));
            list.addView(h);
            for (Bet b : disputed) list.addView(caseCard(b, true));
        }
        if (!monitoring.isEmpty()) {
            TextView h = Ui.label(act, "Monitoring (no dispute)");
            Ui.topMargin(h, Ui.dp(act, 12));
            list.addView(h);
            for (Bet b : monitoring) list.addView(caseCard(b, false));
        }
    }

    private View identityCard() {
        LinearLayout card = Ui.card(act);
        // gold left accent
        card.setBackground(Design.stroked(act, Design.SURFACE(), Design.GOLD(), 18));
        card.addView(Ui.text(act, "You as arbiter", Design.GOLD(), 15, true));
        card.addView(cap("Anyone can name you arbiter. You earn 10% of pots you resolve."));
        card.addView(kv("Public key", act.identity.pubkey));
        card.addView(kv("Address", act.identity.hexaddr));
        TextView copy = Ui.button(act, "Copy identity", Design.SURFACE2(), Design.TEXT(), false);
        Ui.topMargin(copy, Ui.dp(act, 12));
        copy.setOnClickListener(v -> {
            String blob = "pk=" + act.identity.pubkey + "\naddr=" + act.identity.hexaddr;
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("openly-arbiter", blob));
            act.toast("Arbiter identity copied");
        });
        card.addView(copy);
        return card;
    }

    private View caseCard(Bet b, boolean disputed) {
        LinearLayout card = Ui.card(act);
        card.addView(disputed
                ? Ui.chip(act, "DISPUTED — AWAITING DECISION", Design.NEG(), Design.NEG_SOFT())
                : Ui.chip(act, "MONITORING", Design.DIM(), Design.SURFACE2()));
        TextView q = Ui.text(act, b.proposition.isEmpty() ? "Bet" : b.proposition, Design.TEXT(), 15, true);
        Ui.topMargin(q, Ui.dp(act, 8));
        card.addView(q);

        BigDecimal pot = b.amount;
        BigDecimal fee = Num.fee(pot);
        LinearLayout sides = Ui.row(act);
        Ui.topMargin(sides, Ui.dp(act, 8));
        sides.addView(Ui.money(act, "TRUE " + Num.plain(b.side == 1 ? b.ownerBet() : b.counterBet()),
                Design.TRUE_C(), 13, false), Ui.weight(1));
        sides.addView(Ui.money(act, "FALSE " + Num.plain(b.side == 0 ? b.ownerBet() : b.counterBet()),
                Design.FALSE_C(), 13, false), Ui.weight(1));
        card.addView(sides);

        if (!disputed) {
            TextView note = Ui.text(act, "Both parties can still self-settle for free — nothing for you to do.",
                    Design.DIM(), 11, false);
            Ui.topMargin(note, Ui.dp(act, 8));
            card.addView(note);
            return card;
        }

        TextView feeTv = Ui.money(act, "Your fee 10% = " + Num.plain(fee), Design.GOLD(), 12, false);
        Ui.topMargin(feeTv, Ui.dp(act, 6));
        card.addView(feeTv);

        LinearLayout row = Ui.row(act);
        Ui.topMargin(row, Ui.dp(act, 10));
        TextView t = Ui.button(act, "TRUE", Design.TRUE_SOFT(), Design.TRUE_C(), false);
        TextView f = Ui.button(act, "FALSE", Design.FALSE_SOFT(), Design.FALSE_C(), false);
        t.setOnClickListener(v -> resolve(b, 1));
        f.setOnClickListener(v -> resolve(b, 0));
        LinearLayout.LayoutParams lp = Ui.weight(1); lp.rightMargin = Ui.dp(act, 6);
        row.addView(t, lp);
        row.addView(f, Ui.weight(1));
        card.addView(row);
        return card;
    }

    private void resolve(Bet b, int outcome) {
        String word = outcome == 1 ? "TRUE" : "FALSE";
        act.toast("Resolving " + word + "…");
        act.txn.resolve(b, outcome, new OpenlyTxn.Done() {
            public void ok() { act.toast("Resolved " + word + " — fee incoming"); act.refreshCurrent(); }
            public void fail(String m) { act.toast("Resolve failed: " + m); }
        });
    }

    private TextView cap(String s) {
        TextView t = Ui.text(act, s, Design.DIM(), 12, false);
        Ui.topMargin(t, Ui.dp(act, 6));
        return t;
    }

    private View kv(String k, String v) {
        LinearLayout col = Ui.col(act);
        Ui.topMargin(col, Ui.dp(act, 10));
        col.addView(Ui.label(act, k));
        TextView val = Ui.money(act, v == null || v.isEmpty() ? "—" : v, Design.TEXT(), 11, false);
        col.addView(val);
        return col;
    }

    @Override public void onNewBlock() { refresh(); }
}
