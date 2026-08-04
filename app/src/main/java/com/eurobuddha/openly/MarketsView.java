package com.eurobuddha.openly;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Markets board — open bets grouped by proposition into cards with an animated {@link OddsBar}.
 * Phase 2: read-only render. Take/Counter actions arrive in Phase 4.
 */
public class MarketsView extends BaseView {

    private final LinearLayout list;

    public MarketsView(MainActivity a) {
        super(a, build(a));
        ScrollView sv = (ScrollView) root;
        list = (LinearLayout) sv.getChildAt(0);
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

    private static class Market {
        String prop;
        final List<Bet> yes = new ArrayList<>();
        final List<Bet> no = new ArrayList<>();
    }

    @Override public void refresh() {
        list.removeAllViews();

        List<Bet> matchedMine = new ArrayList<>();
        for (Bet b : act.scanner.matched) if (b.isMine || b.isMyCounter || b.isMyArb) matchedMine.add(b);

        Map<String, Market> markets = new LinkedHashMap<>();
        for (Bet b : act.scanner.open) {
            String key = b.proposition == null || b.proposition.isEmpty() ? b.coinid : b.proposition;
            Market m = markets.get(key);
            if (m == null) { m = new Market(); m.prop = b.proposition; markets.put(key, m); }
            (b.side == 1 ? m.yes : m.no).add(b);
        }

        if (markets.isEmpty() && matchedMine.isEmpty()) {
            list.addView(empty());
            return;
        }
        for (Bet b : matchedMine) list.addView(liveRow(b));
        for (Market m : markets.values()) list.addView(marketCard(m));
    }

    private View marketCard(Market m) {
        LinearLayout card = Ui.card(act);
        card.addView(Ui.text(act, m.prop == null || m.prop.isEmpty() ? "Untitled" : m.prop,
                Design.TEXT(), 15, true));

        Bet bestTrue = pickBest(m.yes), bestFalse = pickBest(m.no);
        // "Ask" = the stake the taker of that side must put up (the poster's wantstake).
        BigDecimal trueAsk = bestTrue != null ? bestTrue.counterBet() : BigDecimal.ZERO;
        BigDecimal falseAsk = bestFalse != null ? bestFalse.counterBet() : BigDecimal.ZERO;

        OddsBar bar = new OddsBar(act);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(act, 12);
        bar.setLayoutParams(blp);
        float total = trueAsk.add(falseAsk).floatValue();
        float pct = total <= 0 ? 0.5f : trueAsk.floatValue() / total;
        bar.setOdds(pct, "TRUE " + Ui.compact(trueAsk), "FALSE " + Ui.compact(falseAsk),
                bestTrue != null, bestFalse != null, false);
        card.addView(bar);

        // Per-side offer lines: "TRUE bet 0.5 → win 1  (2.0×)". Shows what each poster staked and the
        // multiple they'd win, so a taker understands the price before opening the slider.
        if (bestTrue != null) card.addView(offerLine(bestTrue, 1));
        if (bestFalse != null) card.addView(offerLine(bestFalse, 0));

        // YOURS — my position in this market (owner sees their stake + odds).
        Bet mine = null;
        for (Bet b : m.yes) if (b.isMine) mine = b;
        for (Bet b : m.no) if (b.isMine) mine = b;
        if (mine != null) {
            TextView yours = Ui.money(act, "YOURS: " + (mine.side == 1 ? "TRUE" : "FALSE") + " "
                    + Num.plain(mine.ownerBet()) + " → win " + Num.plain(mine.counterBet())
                    + "  ·  waiting for a taker", Design.GOLD(), 12, false);
            Ui.topMargin(yours, Ui.dp(act, 10));
            card.addView(yours);
        }

        // Actions: take/counter the OPPOSITE side of an offer I didn't post. Taking a TRUE offer
        // means I bet FALSE, and vice versa. The sheet lets me take at full ask or counter the price.
        LinearLayout actions = Ui.row(act);
        Ui.topMargin(actions, Ui.dp(act, 12));
        if (bestTrue != null && !bestTrue.isMine) {
            TextView t = Ui.button(act, "Bet FALSE →", Design.FALSE_SOFT(), Design.FALSE_C(), false);
            final Bet b = bestTrue;
            t.setOnClickListener(v -> new CounterSheet(act, b).show());
            LinearLayout.LayoutParams lp = Ui.weight(1); lp.rightMargin = Ui.dp(act, 6);
            actions.addView(t, lp);
        }
        if (bestFalse != null && !bestFalse.isMine) {
            TextView t = Ui.button(act, "Bet TRUE →", Design.TRUE_SOFT(), Design.TRUE_C(), false);
            final Bet b = bestFalse;
            t.setOnClickListener(v -> new CounterSheet(act, b).show());
            actions.addView(t, Ui.weight(1));
        }
        if (actions.getChildCount() > 0) card.addView(actions);
        return card;
    }

    /** "TRUE  bet 0.5 → win 1  ·  2.0×" — one poster's offer and the multiple they win. */
    private View offerLine(Bet b, int side) {
        BigDecimal stake = b.ownerBet(), want = b.counterBet();
        String mult = want.signum() > 0 && stake.signum() > 0
                ? want.divide(stake, Num.MC).stripTrailingZeros().toPlainString() + "×" : "—";
        LinearLayout row = Ui.row(act);
        Ui.topMargin(row, Ui.dp(act, 8));
        row.addView(Ui.text(act, side == 1 ? "TRUE" : "FALSE", Design.sideColor(side), 12, true));
        row.addView(Ui.money(act, "  bet " + Num.plain(stake) + " → win " + Num.plain(want),
                Design.DIM(), 12, false));
        TextView m = Ui.money(act, mult, Design.sideColor(side), 12, true);
        LinearLayout.LayoutParams lp = Ui.weight(1);
        m.setGravity(android.view.Gravity.END);
        row.addView(m, lp);
        return row;
    }

    private Bet pickBest(List<Bet> side) {
        Bet best = null;
        for (Bet b : side) if (best == null || b.ownerBet().compareTo(best.ownerBet()) > 0) best = b;
        return best;
    }

    private View liveRow(Bet b) {
        LinearLayout card = Ui.card(act);
        LinearLayout head = Ui.row(act);
        head.addView(Ui.chip(act, "LIVE", Design.ACCENT(), Design.ACCENT_SOFT()));
        head.addView(Ui.text(act, "  " + (b.proposition.isEmpty() ? "Bet" : b.proposition),
                Design.TEXT(), 14, true));
        card.addView(head);
        TextView pot = Ui.money(act, "Pot " + Num.plain(b.potStakes()) + " MINIMA", Design.GOLD(), 12, false);
        Ui.topMargin(pot, Ui.dp(act, 8));
        card.addView(pot);
        return card;
    }

    private View empty() {
        LinearLayout l = Ui.col(act);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, Ui.dp(act, 60), 0, 0);
        TextView glyph = Ui.text(act, "◎", Design.DIM2(), 48, false);
        glyph.setGravity(Gravity.CENTER);
        l.addView(glyph);
        TextView t = Ui.text(act, "No propositions yet", Design.TEXT(), 16, true);
        t.setGravity(Gravity.CENTER);
        Ui.topMargin(t, Ui.dp(act, 12));
        l.addView(t);
        TextView s = Ui.text(act, "Be the first. Post one — your terms, your odds.", Design.DIM(), 13, false);
        s.setGravity(Gravity.CENTER);
        Ui.topMargin(s, Ui.dp(act, 6));
        l.addView(s);
        return l;
    }

    @Override public void onNewBlock() { refresh(); }
}
