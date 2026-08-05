package com.eurobuddha.openly;

import org.json.JSONObject;

import java.math.BigDecimal;

import com.eurobuddha.comms.CommsTransport;

/**
 * Self-settle protocol (0% fee, 2-of-2). No auto-cosign: outcome is a subjective fact a human must
 * assert, so accepting is always one explicit tap. Dual same-outcome proposals converge harmlessly
 * on-chain (identical outputs spend the same coin; the chain keeps one).
 *
 * Propose: compute payouts from my coin → buildSettle (sign+export) → store OUT proposal → send
 *          SETTLE_PROPOSE (hex inline; measured 4861 B fits one comms blob).
 * Accept:  load IN proposal → {@link CoSigner} validates against my chain view → co-sign + post.
 */
public class SettleEngine {

    public interface Cb { void ok(); void fail(String msg); }

    private final MainActivity act;
    private final OpenlyTxn txn;
    private final OpenlyComms comms;
    private final OpenlyDb db;
    private final CoSigner cosigner;

    public SettleEngine(MainActivity act) {
        this.act = act;
        this.txn = act.txn;
        this.comms = act.comms;
        this.db = act.db;
        this.cosigner = new CoSigner(act.node());
    }

    private String myBetPk(Bet bet) { return bet.isMine ? bet.ownerpk : bet.counterpk; }
    private String theirCommsId(Bet bet) { return bet.isMine ? bet.countercommsid : bet.ownercommsid; }

    /** Payout addresses/amounts for an outcome, from my coin (identical to CoSigner + contract). */
    private static class Payout { String winnerAddr, loserAddr; BigDecimal winnerAmt, loserAmt; int outcome; }

    private Payout payout(Bet bet, int outcome) {
        Payout p = new Payout();
        p.outcome = outcome;
        BigDecimal pot = bet.amount;
        BigDecimal ownerLock = bet.ownerstake.signum() > 0 ? bet.ownerstake : bet.amount;
        BigDecimal counterLock = Num.sub(pot, ownerLock);
        if (outcome == 2) {
            p.winnerAddr = bet.owneraddr; p.winnerAmt = ownerLock;
            p.loserAddr = bet.counteraddr; p.loserAmt = counterLock;
            return p;
        }
        boolean ownerWins = outcome == bet.side;
        BigDecimal loserLock = ownerWins ? counterLock : ownerLock;
        BigDecimal le = Num.loserEscrow(loserLock);
        p.winnerAddr = ownerWins ? bet.owneraddr : bet.counteraddr; p.winnerAmt = Num.sub(pot, le);
        p.loserAddr = ownerWins ? bet.counteraddr : bet.owneraddr; p.loserAmt = le;
        return p;
    }

    /** I declare an outcome: build + sign + export the settlement, store it, and send the WHOLE signed
     *  hex to my counterparty in ONE sealed message. No chunking: the ~14 KB signed blob rides in a
     *  single coin's state var (CommsTransport posts with storestate:true, node limit ~64 KB) — the
     *  same one-coin mechanism PocketFS/MinimaFS use to put a whole site on-chain. */
    public void propose(Bet bet, int outcome, Cb cb) {
        if (!bet.isMine && !bet.isMyCounter) { cb.fail("not a party"); return; }
        final Payout p = payout(bet, outcome);
        txn.buildSettle(bet, outcome, myBetPk(bet), p.winnerAddr, p.winnerAmt, p.loserAddr, p.loserAmt,
                new OpenlyTxn.Exported() {
                    public void ok(String hex) {
                        String sha3 = CoSigner.sha3Hex(hex);
                        if (hex == null || hex.isEmpty() || sha3 == null) {
                            cb.fail("could not hash settlement"); return;
                        }
                        long now = System.currentTimeMillis();
                        db.upsertProposal(bet.nonce, "OUT", outcome, sha3, hex,
                                Num.plain(p.winnerAmt), Num.plain(p.loserAmt), "SENT", now);

                        OpenlyMessage prop = new OpenlyMessage();
                        prop.type = OpenlyMessage.SETTLE_PROPOSE;
                        prop.ref = bet.nonce;
                        prop.randomid = rid(now, sha3);
                        prop.to = theirCommsId(bet); prop.date = now; prop.coinid = bet.coinid;
                        prop.outcome = outcome;
                        prop.winnerAmt = Num.plain(p.winnerAmt);
                        prop.loserAmt = Num.plain(p.loserAmt);
                        prop.txnsha3 = sha3;
                        prop.hexchunk = hex;         // the WHOLE signed hex, inline — one coin, no chunking
                        prop.total = 1;
                        // Post to the per-bet settlement address (not the shared OPENLY channel), so the
                        // counterparty finds it reliably at any depth without shared-address bloat.
                        comms.sendTo(OpenlyContract.settleAddr(bet.nonce), prop.to, prop, noop());
                        cb.ok();
                    }
                    public void fail(String msg) { cb.fail("build: " + msg); }
                });
    }

    private static String rid(long now, String sha3) {
        int tag = (sha3 == null ? 0 : sha3.hashCode()) & 0xffff;   // null-safe: never crash a message id
        return "0x" + Long.toHexString(now) + Integer.toHexString(tag);
    }

    /** Store an inbound SETTLE_PROPOSE. It now carries the WHOLE signed hex inline — sha3-verify it and,
     *  if it matches, mark RECEIVED so the Agree button shows. Corrupt/short → AWAITING (a resend replaces). */
    public void onInboundPropose(OpenlyMessage m) {
        long now = System.currentTimeMillis();
        String hex = m.hexchunk;
        String local = (hex == null || hex.isEmpty()) ? null : CoSigner.sha3Hex(hex);
        if (local == null || !local.equalsIgnoreCase(strip(m.txnsha3))) {
            db.upsertProposal(m.ref, "IN", m.outcome, m.txnsha3, null,
                    m.winnerAmt, m.loserAmt, "AWAITING", now);
            return;
        }
        db.upsertProposal(m.ref, "IN", m.outcome, m.txnsha3, hex,
                m.winnerAmt, m.loserAmt, "RECEIVED", now);   // full hex present + verified → Agree shows
    }

    private static String strip(String s) {
        if (s == null) return "";
        return (s.startsWith("0x") || s.startsWith("0X")) ? s.substring(2) : s;
    }

    /**
     * I disagree: tell the counterparty (comms, legitimate) and raise the dispute ON-CHAIN to the
     * arbiter's payout address. No arbiter comms id — the arbiter finds it by scanning their address.
     */
    public void dispute(Bet bet, Cb cb) {
        long now = System.currentTimeMillis();
        db.setProposalState(bet.nonce, "IN", "REJECTED", now);
        // notify counterparty (best-effort comms — this channel is legitimate)
        OpenlyMessage rej = base(bet, OpenlyMessage.SETTLE_REJECT, theirCommsId(bet), now);
        comms.send(rej.to, rej, noop());
        // raise dispute on-chain to the arbiter's port-3 address
        txn.raiseDispute(bet, new OpenlyTxn.Done() {
            public void ok() { cb.ok(); }
            public void fail(String m) { cb.fail("dispute: " + m); }
        });
    }

    private OpenlyMessage base(Bet bet, String type, String to, long now) {
        OpenlyMessage m = new OpenlyMessage();
        m.type = type; m.ref = bet.nonce; m.to = to; m.date = now; m.coinid = bet.coinid;
        m.randomid = "0x" + Long.toHexString(now) + Integer.toHexString(type.hashCode() & 0xffff);
        return m;
    }

    private CommsTransport.SendCb noop() {
        return new CommsTransport.SendCb() {
            public void onSent(String t) {}
            public void onFailed(String e) {}
        };
    }

    /** I accept the inbound proposal: validate via CoSigner against my chain view, then co-sign + post. */
    public void accept(Bet bet, Cb cb) {
        if (!bet.isMine && !bet.isMyCounter) { cb.fail("not a party"); return; }
        OpenlyDb.Proposal p = db.inboundProposal(bet.nonce);
        if (p == null || p.hex == null || p.hex.isEmpty()) { cb.fail("no proposal"); return; }
        cosigner.validateAndPost(bet, p.hex, p.txnsha3, p.outcome, myBetPk(bet), new CoSigner.Done() {
            public void ok(String txpowid) {
                db.setProposalState(bet.nonce, "IN", "ACCEPTED", System.currentTimeMillis());
                db.setProposalState(bet.nonce, "OUT", "ABANDONED", System.currentTimeMillis());
                cb.ok();
            }
            public void fail(String reason) { cb.fail(reason); }
        });
    }
}
