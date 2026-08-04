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

        BigDecimal trueAsk = BigDecimal.ZERO, falseAsk = BigDecimal.ZERO;
        for (Bet b : m.yes) trueAsk = Num.add(trueAsk, b.counterBet());
        for (Bet b : m.no) falseAsk = Num.add(falseAsk, b.counterBet());

        OddsBar bar = new OddsBar(act);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(act, 12);
        bar.setLayoutParams(blp);
        float total = trueAsk.add(falseAsk).floatValue();
        float pct = total <= 0 ? 0.5f : trueAsk.floatValue() / total;
        boolean bets = !m.yes.isEmpty() || !m.no.isEmpty();
        bar.setOdds(pct, "TRUE " + Ui.compact(trueAsk), "FALSE " + Ui.compact(falseAsk), bets, false);
        card.addView(bar);

        int count = m.yes.size() + m.no.size();
        LinearLayout foot = Ui.row(act);
        Ui.topMargin(foot, Ui.dp(act, 10));
        foot.addView(Ui.text(act, count + " open · TRUE " + m.yes.size() + " / FALSE " + m.no.size(),
                Design.DIM(), 11, false));
        card.addView(foot);

        // Take buttons: taking a TRUE offer means I bet FALSE, and vice versa.
        Bet bestTrue = pickBest(m.yes), bestFalse = pickBest(m.no);
        LinearLayout actions = Ui.row(act);
        Ui.topMargin(actions, Ui.dp(act, 12));
        if (bestTrue != null && !bestTrue.isMine) {
            TextView t = Ui.button(act, "Take FALSE", Design.FALSE_SOFT(), Design.FALSE_C(), false);
            final Bet b = bestTrue;
            t.setOnClickListener(v -> new CounterSheet(act, b).show());
            LinearLayout.LayoutParams lp = Ui.weight(1); lp.rightMargin = Ui.dp(act, 6);
            actions.addView(t, lp);
        }
        if (bestFalse != null && !bestFalse.isMine) {
            TextView t = Ui.button(act, "Take TRUE", Design.TRUE_SOFT(), Design.TRUE_C(), false);
            final Bet b = bestFalse;
            t.setOnClickListener(v -> new CounterSheet(act, b).show());
            actions.addView(t, Ui.weight(1));
        }
        if (actions.getChildCount() > 0) card.addView(actions);
        return card;
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
