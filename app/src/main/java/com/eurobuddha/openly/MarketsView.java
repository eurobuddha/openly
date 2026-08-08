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
    /** Last-rendered TRUE fraction per proposition — drives the OddsBar "animate only on change". */
    private final Map<String, Float> lastPct = new java.util.HashMap<>();
    /** Last-rendered best want per side per proposition — drives the "price changed → throb" pulse. */
    private final Map<String, BigDecimal> lastTrueWant = new java.util.HashMap<>();
    private final Map<String, BigDecimal> lastFalseWant = new java.util.HashMap<>();

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
        String prop, tokenid, key;   // key = proposition|tokenid — one card per proposition PER currency
        final List<Bet> yes = new ArrayList<>();
        final List<Bet> no = new ArrayList<>();
    }

    @Override public void refresh() {
        list.removeAllViews();

        // Markets shows OPEN (phase-0) bets ONLY. A taken bet becomes phase 1 → it leaves
        // scanner.open and disappears from here; it lives in My Bets. Showing matched bets on the
        // board was misleading (looked still-takeable).
        Map<String, Market> markets = new LinkedHashMap<>();
        for (Bet b : act.scanner.open) {
            String base = b.proposition == null || b.proposition.isEmpty() ? b.coinid : b.proposition;
            String key = base + "|" + b.tokenid;   // separate cards for Minima vs mxUSDT on the same prop
            Market m = markets.get(key);
            if (m == null) { m = new Market(); m.prop = b.proposition; m.tokenid = b.tokenid; m.key = key; markets.put(key, m); }
            (b.side == 1 ? m.yes : m.no).add(b);
        }

        if (markets.isEmpty()) {
            list.addView(empty());
            lastPct.clear();
            return;
        }
        for (Market m : markets.values()) list.addView(marketCard(m));
        // Drop odds-tracking for markets no longer on the board (bounded memory).
        java.util.Set<String> keysNow = new java.util.HashSet<>();
        for (Market m : markets.values()) keysNow.add(m.key);
        lastPct.keySet().retainAll(keysNow);
        lastTrueWant.keySet().retainAll(keysNow);
        lastFalseWant.keySet().retainAll(keysNow);
    }

    private View marketCard(Market m) {
        LinearLayout card = Ui.card(act);
        LinearLayout titleRow = Ui.row(act);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(act, m.prop == null || m.prop.isEmpty() ? "Untitled" : m.prop,
                Design.TEXT(), 15, true);
        titleRow.addView(title, Ui.weight(1));
        // Currency chip — a bet is Minima OR mxUSDT; same proposition in each currency is a separate card.
        boolean mm = Util.isMinima(m.tokenid);
        titleRow.addView(Ui.chip(act, Util.tokenLabel(m.tokenid),
                mm ? Design.ACCENT() : Design.GOLD(), mm ? Design.ACCENT_SOFT() : Design.GOLD_SOFT()));
        card.addView(titleRow);

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
        // Animate the split only when it actually moved since the last render (not every block rebuild),
        // so the OddsBar rebalance reads as a real change, not constant motion.
        String key = m.key;
        Float prev = lastPct.get(key);
        boolean animate = prev != null && Math.abs(prev - pct) > 0.01f;
        lastPct.put(key, pct);
        // Price-change detection per side → a throb so a new/updated offer is never a silent change.
        BigDecimal ptw = lastTrueWant.get(key), pfw = lastFalseWant.get(key);
        boolean trueChanged = bestTrue != null && ptw != null && ptw.compareTo(trueAsk) != 0;
        boolean falseChanged = bestFalse != null && pfw != null && pfw.compareTo(falseAsk) != 0;
        if (bestTrue != null) lastTrueWant.put(key, trueAsk); else lastTrueWant.remove(key);
        if (bestFalse != null) lastFalseWant.put(key, falseAsk); else lastFalseWant.remove(key);
        if (trueChanged || falseChanged) Sfx.counter();   // a counter arrived → three bright pings
        bar.setOdds(pct, "TRUE " + Ui.compact(trueAsk), "FALSE " + Ui.compact(falseAsk),
                bestTrue != null, bestFalse != null, animate);
        card.addView(bar);

        // Per-side offer lines. A side whose price just moved throbs its odds pill.
        if (bestTrue != null) card.addView(offerLine(bestTrue, 1, trueChanged));
        if (bestFalse != null) card.addView(offerLine(bestFalse, 0, falseChanged));

        // YOURS — my position in this market (owner sees their stake + odds).
        Bet mine = null;
        for (Bet b : m.yes) if (b.isMine) mine = b;
        for (Bet b : m.no) if (b.isMine) mine = b;
        if (mine != null) {
            TextView yours = Ui.money(act, "YOURS: " + (mine.side == 1 ? "TRUE" : "FALSE") + "  "
                    + Num.plain(mine.ownerBet()) + " wants " + Num.plain(mine.counterBet())
                    + "  ·  " + Num.ratio(mine.ownerBet(), mine.counterBet())
                    + "  ·  waiting for a taker", Design.GOLD(), 12, false);
            Ui.topMargin(yours, Ui.dp(act, 10));
            card.addView(yours);
            // Cancel is also reachable here (not only on My Bets) — you can always pull your own
            // untaken offer straight from the board and get your locked stake back.
            final Bet mineBet = mine;
            TextView cancel = Ui.button(act, "Cancel my bet", Design.SURFACE2(), Design.DIM(), false);
            Ui.topMargin(cancel, Ui.dp(act, 8));
            cancel.setOnClickListener(v -> {
                cancel.setEnabled(false);
                act.txn.cancel(mineBet, new OpenlyTxn.Done() {
                    public void ok() { act.recordCancelled(mineBet); act.toast("Cancelled — funds returning"); act.refreshCurrent(); }
                    public void fail(String m) { act.toast("Cancel failed: " + m); cancel.setEnabled(true); }
                });
            });
            card.addView(cancel);
        }

        // Actions: take/counter the OPPOSITE side of an offer I didn't post. Taking a TRUE offer
        // means I bet FALSE, and vice versa. The sheet lets me take at full ask or counter the price.
        LinearLayout actions = Ui.row(act);
        Ui.topMargin(actions, Ui.dp(act, 12));
        if (bestTrue != null && !bestTrue.isMine) {
            TextView t = Ui.button(act, "Accept " + Num.ratio(bestTrue.ownerBet(), trueAsk) + " / Counter TRUE",
                    Design.FALSE_SOFT(), Design.FALSE_C(), false);
            final Bet b = bestTrue;
            t.setOnClickListener(v -> new CounterSheet(act, b).show());
            LinearLayout.LayoutParams lp = Ui.weight(1); lp.rightMargin = Ui.dp(act, 6);
            actions.addView(t, lp);
            if (trueChanged) Design.pulseTimes(t, Design.FALSE_C(), 3);
        }
        if (bestFalse != null && !bestFalse.isMine) {
            TextView t = Ui.button(act, "Accept " + Num.ratio(bestFalse.ownerBet(), falseAsk) + " / Counter FALSE",
                    Design.TRUE_SOFT(), Design.TRUE_C(), false);
            final Bet b = bestFalse;
            t.setOnClickListener(v -> new CounterSheet(act, b).show());
            actions.addView(t, Ui.weight(1));
            if (falseChanged) Design.pulseTimes(t, Design.TRUE_C(), 3);
        }
        if (actions.getChildCount() > 0) card.addView(actions);
        return card;
    }

    /** One poster's offer, made obvious: "[TRUE]  10 wants 20   [1:2]" over "⬡ in 10 M · you".
     *  When {@code changed}, the odds pill throbs so a re-priced offer is never a silent change. */
    private View offerLine(Bet b, int side, boolean changed) {
        BigDecimal stake = b.ownerBet(), want = b.counterBet();
        LinearLayout col = Ui.col(act);
        Ui.topMargin(col, Ui.dp(act, 10));

        LinearLayout row = Ui.row(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.chip(act, side == 1 ? "TRUE" : "FALSE", Design.sideColor(side), Design.sideSoft(side)));
        // The headline terms: "10 wants 20" — stake, then what a taker must put up.
        row.addView(Ui.money(act, "  " + Num.plain(stake) + " wants " + Num.plain(want),
                Design.TEXT(), 16, true));
        View sp = new View(act);
        row.addView(sp, Ui.weight(1));                              // push the odds pill to the right edge
        // The odds, simplified and loud: 10:20 → "1:2", 50:250 → "1:5".
        TextView pill = Ui.chip(act, Num.ratio(stake, want), Design.sideColor(side), Design.sideSoft(side));
        row.addView(pill);
        if (changed) Design.pulseTimes(pill, Design.sideColor(side), 3);   // bounce 3× on a re-price
        col.addView(row);

        // Caption: bet size ("in 10") + poster identicon/id, so the offer is clear and never anonymous.
        LinearLayout byRow = Ui.row(act);
        Ui.topMargin(byRow, Ui.dp(act, 4));
        byRow.setGravity(Gravity.CENTER_VERTICAL);
        IdentityBadge badge = new IdentityBadge(act, b.ownerpk, 14);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(act, 14), Ui.dp(act, 14));
        blp.rightMargin = Ui.dp(act, 6);
        byRow.addView(badge, blp);
        byRow.addView(Ui.money(act, "in " + Num.plain(stake) + "  ·  "
                + (b.isMine ? "you" : Util.shorten(b.ownerpk)), Design.DIM2(), 11, false));
        col.addView(byRow);
        return col;
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
