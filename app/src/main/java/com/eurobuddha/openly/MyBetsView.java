package com.eurobuddha.openly;

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
 * My Bets — my open (waiting) bets and my live (matched) bets. Phase 2: read-only render.
 * Cancel arrives in Phase 3; declare / settle in Phase 6.
 */
public class MyBetsView extends BaseView {

    private final LinearLayout list;

    public MyBetsView(MainActivity a) {
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

        List<Bet> mineOpen = new ArrayList<>();
        for (Bet b : act.scanner.open) if (b.isMine) mineOpen.add(b);
        List<Bet> mineLive = new ArrayList<>();
        for (Bet b : act.scanner.matched) if (b.isMine || b.isMyCounter) mineLive.add(b);

        if (mineOpen.isEmpty() && mineLive.isEmpty()) {
            list.addView(empty());
            return;
        }
        if (!mineLive.isEmpty()) {
            list.addView(Ui.label(act, "Live"));
            for (Bet b : mineLive) list.addView(liveCard(b));
        }
        if (!mineOpen.isEmpty()) {
            TextView h = Ui.label(act, "Open");
            Ui.topMargin(h, Ui.dp(act, 8));
            list.addView(h);
            for (Bet b : mineOpen) list.addView(openCard(b));
        }
    }

    private View openCard(Bet b) {
        LinearLayout card = Ui.card(act);
        card.addView(Ui.chip(act, "OPEN · WAITING", Design.WARN(), Design.WARN_SOFT()));
        TextView q = Ui.text(act, b.proposition.isEmpty() ? "Bet" : b.proposition, Design.TEXT(), 15, true);
        Ui.topMargin(q, Ui.dp(act, 8));
        card.addView(q);
        String side = b.side == 1 ? "TRUE" : "FALSE";
        TextView you = Ui.money(act, "You: " + side + " · " + Num.plain(b.ownerBet())
                + "  ·  want " + Num.plain(b.counterBet()), Design.sideColor(b.side), 13, false);
        Ui.topMargin(you, Ui.dp(act, 6));
        card.addView(you);

        TextView cancel = Ui.button(act, "Cancel", Design.SURFACE2(), Design.DIM(), false);
        Ui.topMargin(cancel, Ui.dp(act, 12));
        cancel.setOnClickListener(v -> {
            cancel.setEnabled(false);
            act.txn.cancel(b, new OpenlyTxn.Done() {
                public void ok() { act.toast("Cancelled — funds returning"); act.refreshCurrent(); }
                public void fail(String m) { act.toast("Cancel failed: " + m); cancel.setEnabled(true); }
            });
        });
        card.addView(cancel);
        return card;
    }

    private View liveCard(Bet b) {
        LinearLayout card = Ui.card(act);
        card.addView(Ui.chip(act, "LIVE", Design.ACCENT(), Design.ACCENT_SOFT()));
        TextView q = Ui.text(act, b.proposition.isEmpty() ? "Bet" : b.proposition, Design.TEXT(), 15, true);
        Ui.topMargin(q, Ui.dp(act, 8));
        card.addView(q);

        boolean iAmOwner = b.isMine;
        int mySideNum = (b.side == 1 && iAmOwner) || (b.side == 0 && !iAmOwner) ? 1 : 0;
        BigDecimal myStake = iAmOwner ? b.ownerBet() : b.counterBet();
        BigDecimal theirStake = iAmOwner ? b.counterBet() : b.ownerBet();

        TextView you = Ui.money(act, "You said " + (mySideNum == 1 ? "TRUE" : "FALSE")
                + " · " + Num.plain(myStake), Design.sideColor(mySideNum), 13, false);
        Ui.topMargin(you, Ui.dp(act, 6));
        card.addView(you);
        TextView win = Ui.money(act, "If you win  +" + Num.plain(theirStake), Design.TRUE_C(), 13, false);
        Ui.topMargin(win, Ui.dp(act, 4));
        card.addView(win);
        TextView lose = Ui.money(act, "If you lose  -" + Num.plain(myStake), Design.NEG(), 13, false);
        Ui.topMargin(lose, Ui.dp(act, 2));
        card.addView(lose);

        // Incoming proposal → Agree / Disagree. Their declared outcome is shown (Phase 6 keeps it simple;
        // the sealed-envelope reveal is a Phase 9 polish item).
        OpenlyDb.Proposal in = act.db.inboundProposal(b.nonce);
        if (in != null) {
            String word = in.outcome == 2 ? "VOID" : in.outcome == 1 ? "TRUE" : "FALSE";
            TextView from = Ui.text(act, "Counterparty says: " + word, Design.GOLD(), 13, true);
            Ui.topMargin(from, Ui.dp(act, 12));
            card.addView(from);
            TextView sub = Ui.text(act, "Agree → 0% fee. Disagree → arbiter decides (10%).", Design.DIM(), 11, false);
            Ui.topMargin(sub, Ui.dp(act, 4));
            card.addView(sub);
            LinearLayout row = Ui.row(act);
            Ui.topMargin(row, Ui.dp(act, 10));
            TextView agree = Ui.button(act, "Agree", Design.TRUE_SOFT(), Design.TRUE_C(), false);
            agree.setOnClickListener(v -> {
                agree.setEnabled(false);
                act.settle.accept(b, new SettleEngine.Cb() {
                    public void ok() { act.toast("Settled — 0% fee, confirming"); act.refreshCurrent(); }
                    public void fail(String m) { act.toast("Settle rejected: " + m); agree.setEnabled(true); }
                });
            });
            TextView disagree = Ui.button(act, "Disagree", Design.SURFACE2(), Design.DIM(), false);
            disagree.setOnClickListener(v -> {
                disagree.setEnabled(false);
                act.settle.dispute(b, new SettleEngine.Cb() {
                    public void ok() { act.toast("Dispute sent to arbiter"); act.refreshCurrent(); }
                    public void fail(String m) { act.toast("Dispute failed: " + m); disagree.setEnabled(true); }
                });
            });
            LinearLayout.LayoutParams lp = Ui.weight(1); lp.rightMargin = Ui.dp(act, 6);
            row.addView(agree, lp);
            row.addView(disagree, Ui.weight(1));
            card.addView(row);
            return card;
        }

        // Otherwise: declare what happened.
        TextView decideTitle = Ui.text(act, "What happened?", Design.TEXT(), 13, true);
        Ui.topMargin(decideTitle, Ui.dp(act, 12));
        card.addView(decideTitle);
        LinearLayout drow = Ui.row(act);
        Ui.topMargin(drow, Ui.dp(act, 8));
        TextView t = Ui.button(act, "TRUE", Design.TRUE_SOFT(), Design.TRUE_C(), false);
        TextView f = Ui.button(act, "FALSE", Design.FALSE_SOFT(), Design.FALSE_C(), false);
        TextView vd = Ui.button(act, "Void", Design.SURFACE2(), Design.DIM(), false);
        t.setOnClickListener(v -> declare(b, 1));
        f.setOnClickListener(v -> declare(b, 0));
        vd.setOnClickListener(v -> declare(b, 2));
        LinearLayout.LayoutParams l1 = Ui.weight(1); l1.rightMargin = Ui.dp(act, 6);
        LinearLayout.LayoutParams l2 = Ui.weight(1); l2.rightMargin = Ui.dp(act, 6);
        drow.addView(t, l1);
        drow.addView(f, l2);
        drow.addView(vd, Ui.weight(0.6f));
        card.addView(drow);

        // Timeout escape hatch: flag "reclaim if arbiter silent" (stops auto-refresh so the coin ages),
        // and once age > timeout, offer the manual reclaim.
        boolean flagged = act.auto.isFlagged(b.nonce);
        int remaining = b.timeout - b.ageBlocks;
        if (b.ageBlocks > b.timeout) {
            TextView reclaim = Ui.button(act, "Reclaim both stakes", Design.WARN_SOFT(), Design.WARN(), false);
            Ui.topMargin(reclaim, Ui.dp(act, 10));
            reclaim.setOnClickListener(v -> {
                reclaim.setEnabled(false);
                act.txn.timeout(b, new OpenlyTxn.Done() {
                    public void ok() { act.toast("Timeout refund posted"); act.refreshCurrent(); }
                    public void fail(String m) { act.toast("Timeout failed: " + m); reclaim.setEnabled(true); }
                });
            });
            card.addView(reclaim);
        } else {
            TextView flag = Ui.text(act, (flagged ? "✓ Will reclaim if arbiter silent (~"
                    : "Tap: reclaim if arbiter silent (~") + Math.max(0, remaining) + " blocks)",
                    flagged ? Design.WARN() : Design.DIM(), 11, false);
            Ui.topMargin(flag, Ui.dp(act, 10));
            flag.setOnClickListener(v -> { act.auto.flagTimeout(b.nonce, !flagged); act.refreshCurrent(); });
            card.addView(flag);
        }
        return card;
    }

    private void declare(Bet b, int outcome) {
        String word = outcome == 2 ? "VOID" : outcome == 1 ? "TRUE" : "FALSE";
        act.toast("Proposing " + word + "…");
        act.settle.propose(b, outcome, new SettleEngine.Cb() {
            public void ok() { act.toast("Proposal sent — waiting for counterparty"); act.refreshCurrent(); }
            public void fail(String m) { act.toast("Propose failed: " + m); }
        });
    }

    private View empty() {
        LinearLayout l = Ui.col(act);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, Ui.dp(act, 60), 0, 0);
        TextView t = Ui.text(act, "No bets yet", Design.TEXT(), 16, true);
        t.setGravity(Gravity.CENTER);
        l.addView(t);
        TextView s = Ui.text(act, "Post one, or take a bet from Markets.", Design.DIM(), 13, false);
        s.setGravity(Gravity.CENTER);
        Ui.topMargin(s, Ui.dp(act, 6));
        l.addView(s);
        return l;
    }

    @Override public void onNewBlock() { refresh(); }

    @Override public void onShown() {
        // Opening My Bets triggers a deep comms rescan so a settlement proposal that arrived while
        // backgrounded is recovered and its Agree/Disagree card appears.
        if (act.comms != null && act.comms.ready()) act.comms.deepRescan(act.block());
        refresh();
    }
}
