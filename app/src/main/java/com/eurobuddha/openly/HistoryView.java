package com.eurobuddha.openly;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Terminal bets — WON / LOST / VOID / CANCELLED / REFUNDED — newest first, read from the local
 * history table (written when a settlement is revealed / a bet is cancelled or timed-out).
 */
public class HistoryView extends BaseView {

    private final LinearLayout list;

    public HistoryView(MainActivity a) {
        super(a, new ScrollView(a));
        ScrollView sv = (ScrollView) getRoot();
        sv.setBackgroundColor(Design.BG());
        list = Ui.col(a);
        int p = Ui.dp(a, 16);
        list.setPadding(p, p, p, p);
        sv.addView(list);
    }

    @Override public void refresh() {
        list.removeAllViews();
        list.setBackgroundColor(Design.BG());
        ((View) list.getParent()).setBackgroundColor(Design.BG());
        List<OpenlyDb.HistoryRow> rows = act.db.history();
        if (rows.isEmpty()) {
            TextView empty = Ui.text(act, "No settled bets yet.\nYour wins and losses land here.",
                    Design.DIM(), 14, false);
            empty.setGravity(Gravity.CENTER);
            Ui.topMargin(empty, Ui.dp(act, 48));
            list.addView(empty);
            return;
        }
        list.addView(Ui.text(act, "History", Design.TEXT(), 18, true));
        for (OpenlyDb.HistoryRow h : rows) list.addView(row(h));
    }

    private View row(OpenlyDb.HistoryRow h) {
        LinearLayout card = Ui.card(act);
        String label; int color;
        switch (h.result) {
            case 1:  label = "WON";       color = Design.TRUE_C(); break;
            case 0:  label = "LOST";      color = Design.NEG();    break;
            case 2:  label = "VOID";      color = Design.GOLD();   break;
            case 3:  label = "CANCELLED"; color = Design.DIM();    break;
            case 4:  label = "REFUNDED";  color = Design.WARN();   break;
            default: label = "SETTLED";   color = Design.DIM();    break;   // 5: outcome unknown
        }
        card.addView(Ui.chip(act, label, color, Design.SURFACE2()));

        TextView q = Ui.text(act, h.proposition == null || h.proposition.isEmpty() ? "Bet" : h.proposition,
                Design.TEXT(), 15, true);
        Ui.topMargin(q, Ui.dp(act, 8));
        card.addView(q);

        // "you were TRUE · arbiter decided" — the position + how it terminated.
        TextView sub = Ui.text(act, "you were " + (h.side == 1 ? "TRUE" : "FALSE") + "  ·  " + pathLabel(h.path),
                Design.DIM(), 12, false);
        Ui.topMargin(sub, Ui.dp(act, 6));
        card.addView(sub);

        // Money in / out at equal billing, then the net. Skipped for result 5 (SETTLED — outcome
        // unknown, so no amounts are asserted). Falls back gracefully for pre-v3 rows with no columns.
        String cur = Util.tokenLabel(h.tokenid);   // null tokenid (pre-v4 rows) → "Minima"
        if (h.result != 5 && h.moneyIn != null && h.moneyOut != null) {
            TextView money = Ui.money(act, "in +" + h.moneyIn + " " + cur + "   ·   out −" + h.moneyOut + " " + cur,
                    Design.TEXT(), 13, false);
            Ui.topMargin(money, Ui.dp(act, 6));
            card.addView(money);
            TextView net = Ui.money(act, "net " + (h.amount == null ? "0" : h.amount) + " " + cur, color, 14, true);
            Ui.topMargin(net, Ui.dp(act, 4));
            card.addView(net);
        } else if (h.result == 5) {
            TextView note = Ui.text(act, "Settled on-chain — outcome not recorded on this device.",
                    Design.DIM(), 12, false);
            Ui.topMargin(note, Ui.dp(act, 6));
            card.addView(note);
        }
        return card;
    }

    private static String pathLabel(String path) {
        if ("ARBITER".equals(path)) return "arbiter decided";
        if ("TIMEOUT".equals(path)) return "timeout refund";
        if ("CANCEL".equals(path))  return "cancelled";
        if ("SELF".equals(path))    return "self-settled";
        return "settled";
    }
}
