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
    // A single sealed message (a coin's state value) holds well under 2 KB, but a signed settlement
    // hex is ~10 KB. So the hex is split into CHUNK-sized SETTLE_TXN messages and reassembled by the
    // receiver, keyed by (nonce, txnsha3). SETTLE_PROPOSE carries only metadata + the chunk count.
    private static final int CHUNK = 900;   // hex chars per message (+ JSON/seal overhead stays < limit)

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
                        int total = (hex.length() + CHUNK - 1) / CHUNK;
                        String to = theirCommsId(bet);

                        // 1) metadata proposal (no hex — too big for one message)
                        OpenlyMessage prop = new OpenlyMessage();
                        prop.type = OpenlyMessage.SETTLE_PROPOSE;
                        prop.ref = bet.nonce;
                        prop.randomid = rid(now, sha3, -1);
                        prop.to = to; prop.date = now; prop.coinid = bet.coinid;
                        prop.outcome = outcome;
                        prop.winnerAmt = Num.plain(p.winnerAmt);
                        prop.loserAmt = Num.plain(p.loserAmt);
                        prop.txnsha3 = sha3;
                        prop.total = total;
                        comms.send(to, prop, noop());

                        // 2) the hex chunks
                        for (int seq = 0; seq < total; seq++) {
                            int end = Math.min(hex.length(), (seq + 1) * CHUNK);
                            OpenlyMessage ch = new OpenlyMessage();
                            ch.type = OpenlyMessage.SETTLE_TXN;
                            ch.ref = bet.nonce;
                            ch.randomid = rid(now, sha3, seq);
                            ch.to = to; ch.date = now;
                            ch.txnsha3 = sha3; ch.seq = seq; ch.total = total;
                            ch.hexchunk = hex.substring(seq * CHUNK, end);
                            comms.send(to, ch, noop());
                        }
                        cb.ok();
                    }
                    public void fail(String msg) { cb.fail("build: " + msg); }
                });
    }

    private static String rid(long now, String sha3, int seq) {
        return "0x" + Long.toHexString(now) + Integer.toHexString((sha3.hashCode() & 0xffff)) + "_" + (seq + 1);
    }

    /** Store an inbound SETTLE_PROPOSE (metadata only; hex arrives as SETTLE_TXN chunks). */
    public void onInboundPropose(OpenlyMessage m) {
        long now = System.currentTimeMillis();
        // AWAITING until all chunks are in and the sha3 checks out — Agree stays hidden till then.
        db.upsertProposal(m.ref, "IN", m.outcome, m.txnsha3, null,
                m.winnerAmt, m.loserAmt, "AWAITING", now);
        tryReassemble(m.ref, m.txnsha3, m.total);
    }

    /** A SETTLE_TXN chunk arrived — try to reassemble the full hex and promote the proposal. */
    public void onInboundChunk(OpenlyMessage m) {
        tryReassemble(m.ref, m.txnsha3, m.total);
    }

    private void tryReassemble(String nonce, String sha3, int total) {
        if (sha3 == null || sha3.isEmpty() || total <= 0) return;
        java.util.List<String> bodies = db.chunksFor(nonce, sha3);   // chunk message JSON, ordered by seq
        if (bodies.size() < total) return;                 // still waiting for chunks
        StringBuilder sb = new StringBuilder();
        for (String body : bodies) {
            OpenlyMessage cm = OpenlyMessage.fromWire(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "");
            if (cm != null && cm.hexchunk != null) sb.append(cm.hexchunk);
        }
        String hex = sb.toString();
        String local = CoSigner.sha3Hex(hex);
        if (local == null || !local.equalsIgnoreCase(strip(sha3))) return;   // incomplete/corrupt — wait
        db.setProposalHexReceived(nonce, hex, System.currentTimeMillis());   // → state RECEIVED, Agree shows
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
