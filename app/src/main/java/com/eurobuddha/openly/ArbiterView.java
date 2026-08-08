package com.eurobuddha.openly;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
        list.addView(eurobuddhaCard());

        // Only DISPUTED cases get resolve buttons — resolving a bet the parties would settle for free
        // would wrongly take the 10% fee + the loser's escrow. Non-disputed matched bets I arbitrate
        // are shown as monitored (no action).
        List<Bet> resolving = new ArrayList<>();
        List<Bet> disputed = new ArrayList<>();
        List<Bet> monitoring = new ArrayList<>();
        for (Bet b : act.scanner.matched) {
            if (!b.isMyArb) continue;
            if (act.scanner.resolvedNonces.contains(b.nonce)) resolving.add(b);
            else if (act.scanner.disputedNonces.contains(b.nonce)) disputed.add(b);
            else monitoring.add(b);
        }

        if (resolving.isEmpty() && disputed.isEmpty() && monitoring.isEmpty()) {
            TextView e = Ui.text(act, "No cases. Share your identity to be named arbiter.",
                    Design.DIM(), 13, false);
            Ui.topMargin(e, Ui.dp(act, 16));
            e.setGravity(Gravity.CENTER);
            list.addView(e);
            return;
        }
        if (!resolving.isEmpty()) {
            TextView h = Ui.label(act, "Resolving — confirming on-chain");
            Ui.topMargin(h, Ui.dp(act, 12));
            list.addView(h);
            for (Bet b : resolving) list.addView(resolvingCard(b));
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

    /** A case I just ruled on — optimistic "confirming" state (no buttons) until the payout mines. */
    private View resolvingCard(Bet b) {
        LinearLayout card = Ui.card(act);
        card.addView(Ui.chip(act, "RESOLVED — CONFIRMING ON-CHAIN", Design.GOLD(), Design.GOLD_SOFT()));
        TextView q = Ui.text(act, b.proposition.isEmpty() ? "Bet" : b.proposition, Design.TEXT(), 15, true);
        Ui.topMargin(q, Ui.dp(act, 8));
        card.addView(q);
        TextView note = Ui.text(act, "Your ruling is posted · payout + your 10% fee are being mined (~1–2 min).",
                Design.DIM(), 12, false);
        Ui.topMargin(note, Ui.dp(act, 8));
        card.addView(note);
        return card;
    }

    private View identityCard() {
        LinearLayout card = Ui.card(act);
        // gold left accent
        card.setBackground(Design.stroked(act, Design.SURFACE(), Design.GOLD(), 18));
        card.addView(Ui.text(act, "You as arbiter", Design.GOLD(), 15, true));
        card.addView(cap("Anyone can name you arbiter. You earn 10% of pots you resolve."));
        card.addView(kv("Public key", act.identity.pubkey));
        card.addView(kv("Address", act.identity.hexaddr));
        card.addView(kv("Comms id (for arbiter chat)", act.identity.commsId));
        TextView copy = Ui.button(act, "Copy identity", Design.SURFACE2(), Design.TEXT(), false);
        Ui.topMargin(copy, Ui.dp(act, 12));
        copy.setOnClickListener(v -> {
            String blob = "pk=" + act.identity.pubkey + "\naddr=" + act.identity.hexaddr
                    + "\ncommsid=" + act.identity.commsId;
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("openly-arbiter", blob));
            act.toast("Arbiter identity copied");
        });
        card.addView(copy);
        return card;
    }

    /** The baked-in default arbiter (the app author) — the values the POST toggle uses, copyable. */
    private View eurobuddhaCard() {
        LinearLayout card = Ui.card(act);
        card.setBackground(Design.stroked(act, Design.SURFACE(), Design.GOLD(), 18));
        card.addView(Ui.text(act, "eurobuddha — default arbiter", Design.GOLD(), 15, true));
        card.addView(cap("The trusted app author, offered as the one-tap default when you post a bet."));
        card.addView(kv("Public key", OpenlyContract.EUROBUDDHA_ARB_PK));
        card.addView(kv("Address", OpenlyContract.EUROBUDDHA_ARB_ADDR));
        card.addView(kv("Comms id", OpenlyContract.EUROBUDDHA_ARB_COMMSID));
        TextView copy = Ui.button(act, "Copy identity", Design.SURFACE2(), Design.TEXT(), false);
        Ui.topMargin(copy, Ui.dp(act, 12));
        copy.setOnClickListener(v -> {
            String blob = "pk=" + OpenlyContract.EUROBUDDHA_ARB_PK + "\naddr=" + OpenlyContract.EUROBUDDHA_ARB_ADDR
                    + "\ncommsid=" + OpenlyContract.EUROBUDDHA_ARB_COMMSID;
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("openly-arbiter", blob));
            act.toast("eurobuddha arbiter identity copied");
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
            card.addView(arbiterChatPanel(b));
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
        card.addView(arbiterChatPanel(b));
        return card;
    }

    /** Arbiter chat for a case — one box that messages BOTH parties identically (no per-party option).
     *  Shows the thread; only enabled when the bet pinned this arbiter's comms id (port 11). */
    private View arbiterChatPanel(final Bet b) {
        LinearLayout chat = Ui.col(act);
        chat.setBackground(Design.roundBg(act, Design.SURFACE2(), 12));
        int p = Ui.dp(act, 10);
        chat.setPadding(p, p, p, p);
        Ui.topMargin(chat, Ui.dp(act, 12));
        chat.addView(Ui.text(act, "Message both parties", Design.GOLD(), 11, true));

        boolean canChat = b.arbcommsid != null && !b.arbcommsid.isEmpty() && !"0".equals(b.arbcommsid);
        if (!canChat) {
            TextView no = Ui.text(act, "Chat needs the arbiter comms id — this bet was posted before that "
                    + "was captured, so messaging isn't available here.", Design.DIM2(), 11, false);
            Ui.topMargin(no, Ui.dp(act, 4));
            chat.addView(no);
            return chat;
        }

        java.util.List<OpenlyDb.ChatRow> msgs = act.db.chatFor(b.nonce);
        if (msgs.isEmpty()) {
            TextView hint = Ui.text(act, "Anything you send goes to both sides, identically.", Design.DIM2(), 12, false);
            Ui.topMargin(hint, Ui.dp(act, 4));
            chat.addView(hint);
        } else {
            for (int i = Math.max(0, msgs.size() - 6); i < msgs.size(); i++) {
                OpenlyDb.ChatRow m = msgs.get(i);
                // This thread is the arbiter's own broadcasts (parties have no reply path yet).
                TextView t = Ui.text(act, "you (both)  " + m.text, Design.GOLD(), 13, false);
                Ui.topMargin(t, Ui.dp(act, 4));
                chat.addView(t);
            }
        }

        LinearLayout row = Ui.row(act);
        Ui.topMargin(row, Ui.dp(act, 8));
        final EditText in = new EditText(act);
        in.setHint("message both parties…");
        in.setTextColor(Design.TEXT());
        in.setHintTextColor(Design.DIM2());
        in.setTextSize(13);
        in.setTypeface(Design.sans());
        in.setBackground(Design.roundBg(act, Design.SURFACE(), 10));
        in.setSingleLine(true);
        int ip = Ui.dp(act, 8);
        in.setPadding(ip, ip, ip, ip);
        TextView send = Ui.button(act, "Send", Design.GOLD_SOFT(), Design.GOLD(), false);
        send.setOnClickListener(v -> {
            String txt = in.getText().toString().trim();
            if (txt.isEmpty()) return;
            in.setText("");
            act.sendArbiterChat(b, txt);
        });
        LinearLayout.LayoutParams ilp = Ui.weight(1);
        ilp.rightMargin = Ui.dp(act, 6);
        row.addView(in, ilp);
        row.addView(send);
        chat.addView(row);
        return chat;
    }

    private void resolve(final Bet b, final int outcome) {
        final String word = outcome == 1 ? "TRUE" : "FALSE";
        BigDecimal fee = Num.fee(b.amount);
        new android.app.AlertDialog.Builder(act)
                .setTitle("Rule " + word + "?")
                .setMessage("You'll pay the winner " + Num.plain(Num.sub(b.amount, fee)) + " M and take a "
                        + Num.plain(fee) + " M (10%) fee. The loser forfeits their escrow. This is final.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rule " + word, (d, w) -> doResolve(b, outcome, word))
                .show();
    }

    private void doResolve(final Bet b, final int outcome, final String word) {
        act.scanner.markResolved(b.nonce);        // flip the card to "confirming" immediately
        act.refreshCurrent();
        act.toast("Ruling " + word + " — posting…");
        act.txn.resolve(b, outcome, new OpenlyTxn.Done() {
            public void ok() { act.arbiterResolvedReveal(b, outcome); act.refreshCurrent(); }
            public void fail(String m) {
                act.scanner.resolvedNonces.remove(b.nonce);   // revert the optimistic state
                act.toast("Resolve failed: " + m); act.refreshCurrent();
            }
        });
    }

    private TextView cap(String s) {
        TextView t = Ui.text(act, s, Design.DIM(), 12, false);
        Ui.topMargin(t, Ui.dp(act, 6));
        return t;
    }

    /** One identity field, in its own tappable box: tap copies ONLY this value (so you can send the
     *  pubkey and the address separately). */
    private View kv(final String k, final String v) {
        LinearLayout col = Ui.col(act);
        Ui.topMargin(col, Ui.dp(act, 10));
        col.setBackground(Design.roundBg(act, Design.SURFACE2(), 10));
        int p = Ui.dp(act, 10);
        col.setPadding(p, p, p, p);
        col.addView(Ui.label(act, k + "  ·  tap to copy"));
        col.addView(Ui.money(act, v == null || v.isEmpty() ? "—" : v, Design.TEXT(), 11, false));
        col.setOnClickListener(view -> {
            if (v == null || v.isEmpty()) { act.toast("identity not ready yet"); return; }
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("openly-" + k, v));
            act.toast(k + " copied");
        });
        return col;
    }

    @Override public void onNewBlock() { refresh(); }
}
