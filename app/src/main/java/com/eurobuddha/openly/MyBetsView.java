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
        return card;
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
}
