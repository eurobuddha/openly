package com.eurobuddha.openly;

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

        // Just-posted bets/counters not yet on-chain — show an animated "confirming" card so the
        // user sees SOMETHING happening during the ~1-2 min wait, instead of a blank screen.
        List<MainActivity.PendingPost> pending = new ArrayList<>();
        for (MainActivity.PendingPost p : act.pendingPostList())
            if (act.betByNonce(p.nonce) == null) pending.add(p);

        List<Bet> mineOpen = new ArrayList<>();
        for (Bet b : act.scanner.open) if (b.isMine) mineOpen.add(b);
        List<Bet> mineLive = new ArrayList<>();
        for (Bet b : act.scanner.matched) if (b.isMine || b.isMyCounter) mineLive.add(b);

        if (mineOpen.isEmpty() && mineLive.isEmpty() && pending.isEmpty()) {
            list.addView(empty());
            return;
        }
        if (!pending.isEmpty()) {
            list.addView(Ui.label(act, "Confirming"));
            for (MainActivity.PendingPost p : pending) list.addView(pendingCard(p));
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

    /** A just-broadcast bet/counter, still landing on-chain — animated so the wait feels alive. */
    private View pendingCard(MainActivity.PendingPost p) {
        LinearLayout card = Ui.card(act);
        card.addView(Ui.chip(act, "CONFIRMING", Design.ACCENT(), Design.ACCENT_SOFT()));
        TextView q = Ui.text(act, p.proposition == null || p.proposition.isEmpty()
                ? "Your new bet" : p.proposition, Design.TEXT(), 15, true);
        Ui.topMargin(q, Ui.dp(act, 8));
        card.addView(q);
        String sideWord = p.side == 1 ? "TRUE" : "FALSE";
        TextView t = Ui.money(act, sideWord + "  " + Num.plain(p.stake) + " wants " + Num.plain(p.want)
                + "  ·  " + Num.ratio(p.stake, p.want), Design.DIM(), 12, false);
        Ui.topMargin(t, Ui.dp(act, 6));
        card.addView(t);
        TextView note = Ui.text(act, "⏳ confirming on-chain — usually 1–2 min", Design.ACCENT(), 12, true);
        Ui.topMargin(note, Ui.dp(act, 10));
        card.addView(note);
        Ui.throb(note);
        return card;
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
                public void ok() { act.recordCancelled(b); act.toast("Cancelled — funds returning"); act.refreshCurrent(); }
                public void fail(String m) { act.toast("Cancel failed: " + m); cancel.setEnabled(true); }
            });
        });
        card.addView(cancel);
        return card;
    }

    private View liveCard(Bet b) {
        LinearLayout card = Ui.card(act);
        OpenlyDb.Proposal in = act.db.inboundProposal(b.nonce);
        Integer myDeclared = act.db.myDeclaredOutcome(b.nonce);
        boolean atArb = act.isArbiterCalled(b.nonce);
        // Lifecycle status chip — LIVE → DECLARED/awaiting → THEY DECLARED/respond → AT ARBITER.
        String cw; int cc, cbg;
        if (atArb)                   { cw = "AT ARBITER — AWAITING DECISION"; cc = Design.GOLD();   cbg = Design.GOLD_SOFT(); }
        else if (in != null)         { cw = "THEY DECLARED — RESPOND";        cc = Design.WARN();   cbg = Design.WARN_SOFT(); }
        else if (myDeclared != null) { cw = "DECLARED — AWAITING THEM";       cc = Design.ACCENT(); cbg = Design.ACCENT_SOFT(); }
        else                         { cw = "LIVE";                           cc = Design.ACCENT(); cbg = Design.ACCENT_SOFT(); }
        card.addView(Ui.chip(act, cw, cc, cbg));
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

        // Summoning the arbiter supersedes everything else — show the waiting state and no other actions,
        // so a rebuild can't re-offer declare/settle and double-fire.
        if (atArb) {
            TextView note = Ui.text(act, "The arbiter has been summoned. They'll rule TRUE or FALSE; the "
                    + "winner is paid and the arbiter takes 10%.", Design.DIM(), 12, false);
            Ui.topMargin(note, Ui.dp(act, 12));
            card.addView(note);
            addChat(card, b);
            return card;
        }

        // Incoming proposal → the sealed-envelope commit-reveal. Their declaration stays hidden until I
        // commit my own (no anchoring). Match → settle 0% fee; differ → the arbiter decides.
        if (in != null) {
            final int theirOutcome = in.outcome;
            // Both already declared the same outcome → we're auto-settling. Show confirming, not the
            // envelope, and never re-offer a manual co-sign (avoids a duplicate spend attempt).
            if (act.isSettling(b.nonce)) {
                TextView s = Ui.text(act, "You both agreed " + word(theirOutcome) + " — settling on-chain…",
                        Design.TRUE_C(), 13, true);
                Ui.topMargin(s, Ui.dp(act, 12)); card.addView(s); Ui.throb(s);
                addChat(card, b);
                return card;
            }
            // Already picked this session? Reconstruct the revealed state directly (no re-seal), so the
            // per-block rebuild doesn't throw away the torn-open reveal + Settle/Call action.
            Integer picked = act.revealedPick(b.nonce);
            if (picked != null) {
                LinearLayout box = Ui.col(act);
                Ui.topMargin(box, Ui.dp(act, 12));
                card.addView(box);
                renderRevealResult(box, b, picked, theirOutcome);
                addChat(card, b);
                return card;
            }
            final EnvelopeView env = new EnvelopeView(act);
            Ui.topMargin(env, Ui.dp(act, 12));
            card.addView(env);
            TextView prompt = Ui.text(act, "They've declared. What did you see?", Design.TEXT(), 13, true);
            Ui.topMargin(prompt, Ui.dp(act, 10));
            card.addView(prompt);

            final LinearLayout reveal = Ui.col(act);   // populated after the tear
            final LinearLayout drow = Ui.row(act);
            Ui.topMargin(drow, Ui.dp(act, 8));
            final TextView t  = Ui.button(act, "TRUE",  Design.TRUE_SOFT(),  Design.TRUE_C(),  false);
            final TextView f  = Ui.button(act, "FALSE", Design.FALSE_SOFT(), Design.FALSE_C(), false);
            final TextView vd = Ui.button(act, "Void",  Design.SURFACE2(),   Design.DIM(),     false);
            t.setOnClickListener(v ->  pickReveal(env, reveal, b, 1, theirOutcome, t, f, vd));
            f.setOnClickListener(v ->  pickReveal(env, reveal, b, 0, theirOutcome, t, f, vd));
            vd.setOnClickListener(v -> pickReveal(env, reveal, b, 2, theirOutcome, t, f, vd));
            LinearLayout.LayoutParams l1 = Ui.weight(1); l1.rightMargin = Ui.dp(act, 6);
            LinearLayout.LayoutParams l2 = Ui.weight(1); l2.rightMargin = Ui.dp(act, 6);
            drow.addView(t, l1); drow.addView(f, l2); drow.addView(vd, Ui.weight(0.6f));
            card.addView(drow);
            Ui.topMargin(reveal, Ui.dp(act, 10));
            card.addView(reveal);
            addChat(card, b);
            return card;
        }

        // Declare what happened — unless I already declared and am waiting for them to agree.
        if (myDeclared != null && !act.isRedeclaring(b.nonce)) {
            String dw = myDeclared == 2 ? "VOID" : myDeclared == 1 ? "TRUE" : "FALSE";
            TextView declaredNote = Ui.text(act, "You declared " + dw
                    + " — waiting for the counterparty to agree.", Design.DIM(), 12, false);
            Ui.topMargin(declaredNote, Ui.dp(act, 12));
            card.addView(declaredNote);
            // Change your mind: re-open the choice (a new declaration supersedes the old, until it settles).
            // Warn honestly — a signature that's already been sent can still be co-signed by the other side.
            TextView change = Ui.button(act, "Change my answer", Design.SURFACE2(), Design.DIM(), false);
            Ui.topMargin(change, Ui.dp(act, 8));
            change.setOnClickListener(v -> new android.app.AlertDialog.Builder(act)
                    .setTitle("Change your answer?")
                    .setMessage("Your earlier answer was already sent and can still be accepted by the other "
                            + "side until your new one reaches them.")
                    .setPositiveButton("Change", (d, w) -> { act.setRedeclaring(b.nonce, true); act.refreshCurrent(); })
                    .setNegativeButton("Cancel", null)
                    .show());
            card.addView(change);
        } else {
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
            // If we entered this row via "Change my answer", offer a way back out without re-declaring.
            if (myDeclared != null && act.isRedeclaring(b.nonce)) {
                TextView keep = Ui.button(act, "Keep " + (myDeclared == 2 ? "VOID" : myDeclared == 1 ? "TRUE" : "FALSE"),
                        Design.SURFACE2(), Design.DIM(), false);
                Ui.topMargin(keep, Ui.dp(act, 8));
                keep.setOnClickListener(v -> { act.setRedeclaring(b.nonce, false); act.refreshCurrent(); });
                card.addView(keep);
            }
        }

        // Counterparty unresponsive? Summon the arbiter. Raises the on-chain dispute marker to the bet's
        // arbiter address so the named arbiter can resolve it — the escape hatch when the other side never
        // proposes (or can't, e.g. their node was reseeded). Without this you could only reach the arbiter
        // by disagreeing with a proposal that never arrives.
        // (A summoned-arbiter bet already returned early above, so here it's always un-summoned.)
        if (b.arbaddr != null && !b.arbaddr.isEmpty()) {
            TextView cap = Ui.text(act, "Counterparty not responding?", Design.DIM(), 11, false);
            Ui.topMargin(cap, Ui.dp(act, 12));
            card.addView(cap);
            final TextView callArb = Ui.button(act, "Call the arbiter", Design.GOLD_SOFT(), Design.GOLD(), false);
            Ui.topMargin(callArb, Ui.dp(act, 6));
            callArb.setOnClickListener(v -> new android.app.AlertDialog.Builder(act)
                    .setTitle("Call the arbiter?")
                    .setMessage("Use this only if the other side won't settle. The arbiter decides the "
                            + "outcome, pays the winner, and takes a 10% fee. The loser forfeits their escrow.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Call arbiter", (d, w) -> {
                        callArb.setEnabled(false);
                        act.settle.dispute(b, new SettleEngine.Cb() {
                            public void ok() {
                                act.markArbiterCalled(b.nonce);
                                act.toast("Arbiter summoned — awaiting their decision");
                                act.refreshCurrent();
                            }
                            public void fail(String m) { act.toast("Call arbiter failed: " + m); callArb.setEnabled(true); }
                        });
                    }).show());
            card.addView(callArb);
        }

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
        addChat(card, b);
        return card;
    }

    /** Per-bet chat panel on a live card: recent messages + a send box (small messages over comms). */
    private void addChat(LinearLayout card, Bet b) {
        LinearLayout chat = Ui.col(act);
        chat.setBackground(Design.roundBg(act, Design.SURFACE2(), 12));
        int p = Ui.dp(act, 10);
        chat.setPadding(p, p, p, p);
        Ui.topMargin(chat, Ui.dp(act, 12));
        chat.addView(Ui.text(act, "Chat", Design.DIM(), 11, true));

        List<OpenlyDb.ChatRow> msgs = act.db.chatFor(b.nonce);
        if (msgs.isEmpty()) {
            TextView hint = Ui.text(act, "Message your counterparty…", Design.DIM2(), 12, false);
            Ui.topMargin(hint, Ui.dp(act, 4));
            chat.addView(hint);
        } else {
            for (int i = Math.max(0, msgs.size() - 6); i < msgs.size(); i++) {
                OpenlyDb.ChatRow m = msgs.get(i);
                boolean isArb = m.inbound && m.fromid != null && b.arbcommsid != null
                        && !b.arbcommsid.isEmpty() && m.fromid.equalsIgnoreCase(b.arbcommsid);
                String who = !m.inbound ? "you  " : (isArb ? "⚖ arbiter  " : "them  ");
                int col = !m.inbound ? Design.ACCENT() : (isArb ? Design.GOLD() : Design.TEXT());
                TextView t = Ui.text(act, who + m.text, col, 13, false);
                Ui.topMargin(t, Ui.dp(act, 4));
                chat.addView(t);
            }
        }

        LinearLayout row = Ui.row(act);
        Ui.topMargin(row, Ui.dp(act, 8));
        final EditText in = new EditText(act);
        in.setHint("message");
        in.setTextColor(Design.TEXT());
        in.setHintTextColor(Design.DIM2());
        in.setTextSize(13);
        in.setTypeface(Design.sans());
        in.setBackground(Design.roundBg(act, Design.SURFACE(), 10));
        in.setSingleLine(true);
        int ip = Ui.dp(act, 8);
        in.setPadding(ip, ip, ip, ip);
        TextView send = Ui.button(act, "Send", Design.ACCENT_SOFT(), Design.ACCENT(), false);
        send.setOnClickListener(v -> {
            String txt = in.getText().toString().trim();
            if (txt.isEmpty()) return;
            in.setText("");
            act.sendChat(b, txt);
        });
        LinearLayout.LayoutParams ilp = Ui.weight(1);
        ilp.rightMargin = Ui.dp(act, 6);
        row.addView(in, ilp);
        row.addView(send);
        chat.addView(row);
        card.addView(chat);
    }

    private void declare(Bet b, int outcome) {
        String word = outcome == 2 ? "VOID" : outcome == 1 ? "TRUE" : "FALSE";
        act.toast("Proposing " + word + "…");
        act.setRedeclaring(b.nonce, false);        // leave the "change my answer" mode
        act.settle.propose(b, outcome, new SettleEngine.Cb() {
            public void ok() {
                Sfx.seal();                        // declaration committed — the "seal" cue
                act.watchSettlement(b, outcome);   // reveal the payoff when the counterparty settles it
                act.toast("Proposal sent — waiting for counterparty");
                act.refreshCurrent();
            }
            public void fail(String m) { act.toast("Propose failed: " + m); }
        });
    }

    /** Commit my blind pick, tear the envelope open, then reveal it against their declaration and offer
     *  the matching action: settle (we agree, 0% fee) or the arbiter (we differ). */
    private void pickReveal(EnvelopeView env, final LinearLayout box, final Bet b, final int myPick,
                            final int theirOutcome, View... buttons) {
        for (View v : buttons) v.setEnabled(false);
        act.setRevealedPick(b.nonce, myPick);   // persist so a per-block rebuild reconstructs this state
        env.tear(() -> renderRevealResult(box, b, myPick, theirOutcome));
    }

    /** Populate the reveal box: my pick vs their declaration, and the matching action (settle / arbiter).
     *  Used both after the tear and when reconstructing after a rebuild. */
    private void renderRevealResult(final LinearLayout box, final Bet b, final int myPick, final int theirOutcome) {
        {
            box.removeAllViews();
            box.addView(Ui.text(act, "You saw " + word(myPick) + "  ·  they declared " + word(theirOutcome),
                    Design.TEXT(), 13, true));
            if (myPick == theirOutcome) {
                if (act.isSettling(b.nonce)) {
                    // Already agreed — the co-sign is mining; don't re-offer Settle (avoids a duplicate co-sign).
                    TextView s = Ui.text(act, "You agreed " + word(theirOutcome) + " — confirming on-chain…",
                            Design.TRUE_C(), 12, false);
                    Ui.topMargin(s, Ui.dp(act, 6)); box.addView(s);
                } else {
                    TextView ok = Ui.text(act, "You agree — settle now, 0% fee.", Design.TRUE_C(), 12, false);
                    Ui.topMargin(ok, Ui.dp(act, 4)); box.addView(ok);
                    final TextView settle = Ui.button(act, "Settle · " + word(theirOutcome),
                            Design.TRUE_SOFT(), Design.TRUE_C(), false);
                    Ui.topMargin(settle, Ui.dp(act, 8));
                    settle.setOnClickListener(v -> {
                        settle.setEnabled(false);
                        act.settle.accept(b, new SettleEngine.Cb() {
                            public void ok() { act.onSettleAgreed(b, theirOutcome); act.refreshCurrent(); }
                            public void fail(String m) { act.toast("Settle rejected: " + m); settle.setEnabled(true); }
                        });
                    });
                    box.addView(settle);
                }
            } else {
                TextView diff = Ui.text(act, "You differ — the arbiter decides (10% fee · loser forfeits escrow).",
                        Design.NEG(), 12, false);
                Ui.topMargin(diff, Ui.dp(act, 4)); box.addView(diff);
                final TextView call = Ui.button(act, "Call the arbiter", Design.GOLD_SOFT(), Design.GOLD(), false);
                Ui.topMargin(call, Ui.dp(act, 8));
                call.setOnClickListener(v -> {
                    call.setEnabled(false);
                    act.settle.dispute(b, new SettleEngine.Cb() {
                        public void ok() { act.markArbiterCalled(b.nonce); act.toast("Arbiter summoned"); act.refreshCurrent(); }
                        public void fail(String m) { act.toast("Dispute failed: " + m); call.setEnabled(true); }
                    });
                });
                box.addView(call);
            }
        }
    }

    private static String word(int o) { return o == 2 ? "VOID" : o == 1 ? "TRUE" : "FALSE"; }

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
