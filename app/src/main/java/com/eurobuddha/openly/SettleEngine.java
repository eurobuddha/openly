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

    /** I declare an outcome: build + sign + export the settlement, store it, send it to my counterparty. */
    public void propose(Bet bet, int outcome, Cb cb) {
        if (!bet.isMine && !bet.isMyCounter) { cb.fail("not a party"); return; }
        final Payout p = payout(bet, outcome);
        txn.buildSettle(bet, outcome, myBetPk(bet), p.winnerAddr, p.winnerAmt, p.loserAddr, p.loserAmt,
                new OpenlyTxn.Exported() {
                    public void ok(String hex) {
                        String sha3 = CoSigner.sha3Hex(hex);
                        long now = System.currentTimeMillis();
                        db.upsertProposal(bet.nonce, "OUT", outcome, sha3, hex,
                                Num.plain(p.winnerAmt), Num.plain(p.loserAmt), "SENT", now);
                        OpenlyMessage m = new OpenlyMessage();
                        m.type = OpenlyMessage.SETTLE_PROPOSE;
                        m.ref = bet.nonce;
                        m.randomid = "0x" + Long.toHexString(now) + Integer.toHexString((int)(Math.abs(hex.hashCode())));
                        m.to = theirCommsId(bet);
                        m.date = now;
                        m.coinid = bet.coinid;
                        m.outcome = outcome;
                        m.winnerAmt = Num.plain(p.winnerAmt);
                        m.loserAmt = Num.plain(p.loserAmt);
                        m.txnsha3 = sha3;
                        m.hexchunk = hex;   // single blob (measured < chunk ceiling)
                        m.seq = 0; m.total = 1;
                        comms.send(m.to, m, new CommsTransport.SendCb() {
                            public void onSent(String txpowid) { cb.ok(); }
                            public void onFailed(String e) { cb.fail("send: " + e); }
                        });
                    }
                    public void fail(String msg) { cb.fail("build: " + msg); }
                });
    }

    /** Store an inbound SETTLE_PROPOSE (from the authenticated sink). */
    public void onInboundPropose(OpenlyMessage m) {
        long now = System.currentTimeMillis();
        db.upsertProposal(m.ref, "IN", m.outcome, m.txnsha3, m.hexchunk,
                m.winnerAmt, m.loserAmt, "RECEIVED", now);
    }

    /** I disagree: reject to the counterparty and raise a DISPUTE to the arbiter's commsid. */
    public void dispute(Bet bet, Cb cb) {
        long now = System.currentTimeMillis();
        db.setProposalState(bet.nonce, "IN", "REJECTED", now);
        // notify counterparty (best-effort)
        OpenlyMessage rej = base(bet, OpenlyMessage.SETTLE_REJECT, theirCommsId(bet), now);
        comms.send(rej.to, rej, noop());
        // raise dispute to arbiter
        if (bet.arbcommsid != null && !bet.arbcommsid.isEmpty()) {
            OpenlyMessage d = base(bet, OpenlyMessage.DISPUTE, bet.arbcommsid, now);
            comms.send(d.to, d, new CommsTransport.SendCb() {
                public void onSent(String t) { cb.ok(); }
                public void onFailed(String e) { cb.fail("dispute send: " + e); }
            });
        } else cb.ok();
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
