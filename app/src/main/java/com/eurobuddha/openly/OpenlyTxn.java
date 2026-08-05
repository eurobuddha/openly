package com.eurobuddha.openly;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.SignGate;

/**
 * On-chain transaction builders for Openly. Every multi-step build runs through {@link CmdChain}
 * with `txndelete id:X` cleanup on any failure, and every signing path goes through
 * {@link SignGate} (serial signing — concurrent signing once caused Winternitz key reuse).
 *
 * Phase 3: post (send) + cancel. Fill / settle / arbiter / timeout land in later phases.
 */
public class OpenlyTxn {

    public interface Done { void ok(); void fail(String msg); }

    private final NodeApi node;
    private final Identity id;

    /** Funding coins reserved from post until confirmation (never double-spend across concurrent builds). */
    private final Set<String> inflightCoins = ConcurrentHashMap.newKeySet();

    public OpenlyTxn(NodeApi node, Identity id) {
        this.node = node;
        this.id = id;
    }

    private static String tag(String p) {
        return p + "_" + System.currentTimeMillis() + "_" + Integer.toHexString((int) (System.nanoTime() & 0xffffff));
    }

    // ---------------------------------------------------------------- POST (phase 0)
    /**
     * Post a new bet: a single `send` to the contract address with owner-set state ports 0–12.
     * lock = stake × 1.25 is sent as @AMOUNT; wantstake is the counter's required lock.
     */
    public void post(String proposition, int side, BigDecimal stake, BigDecimal wantstake,
                     String arbpk, String arbaddr, int timeout, int settleblock,
                     String nonce, Done done) {
        BigDecimal lock = Num.lock(stake);
        BigDecimal wlock = Num.lock(wantstake);
        JSONObject st = new JSONObject();
        try {
            st.put("0", id.pubkey);
            st.put("1", id.hexaddr);
            st.put("2", arbpk);
            st.put("3", arbaddr);
            st.put("4", String.valueOf(timeout));
            st.put("5", String.valueOf(side));
            st.put("6", Num.plain(wlock));
            st.put("7", Util.strToHex(proposition));
            st.put("8", String.valueOf(settleblock));
            st.put("9", nonce);
            st.put("10", id.commsId);
            // Port 11 is unused by the contract. Disputes reach the arbiter ON-CHAIN (a marker coin to
            // their port-3 payout address), so there is no separate arbiter comms id to carry.
            st.put("11", "0");
            st.put("12", "0");
        } catch (Exception e) { done.fail("state build failed"); return; }

        final String cmd = "send amount:" + Num.plain(lock) + " address:" + OpenlyContract.ADDR
                + " state:" + st.toString();
        SignGate.submit(gate -> node.cmd(cmd, new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                gate.free();
                boolean okStatus = r.optBoolean("status", false) || r.optBoolean("pending", false);
                if (okStatus) done.ok(); else done.fail(r.optString("error", "post failed"));
            }
            public void onError(String e) { gate.free(); done.fail(e); }
        }));
    }

    /** Dispute-marker state ports (on a 1-nano coin to the arbiter's payout address). */
    public static final int DISPUTE_NONCE_PORT = 50;
    public static final int DISPUTE_TAG_PORT = 51;
    public static final String DISPUTE_TAG = Util.strToHex("DISPUTE");

    // ---------------------------------------------------------------- DISPUTE (on-chain, to arbiter)
    /**
     * Raise a dispute the arbiter can find by scanning their own payout address (port 3) — no comms
     * id needed. A 1-nano coin to that address carries the bet nonce (port 50) + a DISPUTE tag
     * (port 51). The arbiter's app matches the nonce to a matched bet where they are the arbiter.
     */
    public void raiseDispute(Bet bet, Done done) {
        JSONObject st = new JSONObject();
        try {
            st.put(String.valueOf(DISPUTE_NONCE_PORT), bet.nonce);
            st.put(String.valueOf(DISPUTE_TAG_PORT), DISPUTE_TAG);
        } catch (Exception e) { done.fail("marker build failed"); return; }
        final String cmd = "send amount:0.000000001 address:" + bet.arbaddr + " state:" + st.toString();
        SignGate.submit(gate -> node.cmd(cmd, new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                gate.free();
                if (r.optBoolean("status", false) || r.optBoolean("pending", false)) done.ok();
                else done.fail(r.optString("error", "dispute post failed"));
            }
            public void onError(String e) { gate.free(); done.fail(e); }
        }));
    }

    // ---------------------------------------------------------------- CANCEL (phase 0)
    /** Owner reclaims an unmatched bet: input the contract coin, pay out to owner addr, owner-sign. */
    public void cancel(Bet bet, Done done) {
        final String txid = tag("cancel");
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid);
        cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(bet.amount)
                + " address:" + bet.owneraddr + " storestate:false");
        // owner-sign with the exact key stored in the coin (port 0)
        cmds.add("txnsign id:" + txid + " publickey:" + bet.ownerpk);
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        SignGate.submit(gate -> CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
            public void ok(JSONObject last) {
                gate.free();
                node.cmd("txndelete id:" + txid, NodeApi.Cb.NOOP);
                done.ok();
            }
            public void fail(String msg) {
                gate.free();
                done.fail(msg);
            }
        }));
    }

    // ---------------------------------------------------------------- FILL (phase 0→1)
    /**
     * Counter matches an open bet: input the contract coin (index 0) + funding, recreate the pot
     * (@AMOUNT + wantstake) at the address with state (storestate:true), set ports 0–17 pinning the
     * owner region and writing only my identity ports, sign auto, gated post.
     */
    public void fill(Bet bet, Done done) {
        final BigDecimal need = bet.wantstake;                 // counter's total lock
        final BigDecimal pot = Num.add(bet.amount, bet.wantstake);
        node.cmd("coins sendable:true tokenid:0x00 depth:400", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                JSONArray arr = r.optJSONArray("response");
                JSONObject fund = null;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        String cid = c.optString("coinid", "");
                        if (inflightCoins.contains(cid)) continue;
                        BigDecimal amt;
                        try { amt = Num.of(c.optString("amount", "0")); } catch (Exception e) { continue; }
                        if (amt.compareTo(need) >= 0) { fund = c; break; }
                    }
                }
                if (fund == null) { done.fail("No single coin ≥ " + Num.plain(need) + " MINIMA to fund"); return; }
                final String fundId = fund.optString("coinid", "");
                final BigDecimal fundAmt;
                try { fundAmt = Num.of(fund.optString("amount", "0")); }
                catch (Exception e) { done.fail("bad funding amount"); return; }
                inflightCoins.add(fundId);
                buildFill(bet, fundId, fundAmt, pot, need, done);
            }
            public void onError(String e) { done.fail(e); }
        });
    }

    private void buildFill(Bet bet, String fundId, BigDecimal fundAmt, BigDecimal pot,
                           BigDecimal need, Done done) {
        final String txid = tag("fill");
        BigDecimal change = Num.sub(fundAmt, need);
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid);          // contract coin = input 0
        cmds.add("txninput id:" + txid + " coinid:" + fundId);
        cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(pot)
                + " address:" + OpenlyContract.ADDR + " storestate:true");
        if (change.compareTo(new BigDecimal("0.000000001")) > 0) {
            cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(change)
                    + " address:" + id.hexaddr + " storestate:false");
        }
        // state: pin owner region 0–11, phase→1, my identity 13/14/16, 15=@AMOUNT, 17=0
        cmds.add(st(txid, 0, bet.ownerpk));
        cmds.add(st(txid, 1, bet.owneraddr));
        cmds.add(st(txid, 2, bet.arbpk));
        cmds.add(st(txid, 3, bet.arbaddr));
        cmds.add(st(txid, 4, String.valueOf(bet.timeout)));
        cmds.add(st(txid, 5, String.valueOf(bet.side)));
        cmds.add(st(txid, 6, Num.plain(bet.wantstake)));
        cmds.add(st(txid, 7, Util.strToHex(bet.proposition)));
        cmds.add(st(txid, 8, String.valueOf(bet.settleblock)));
        cmds.add(st(txid, 9, bet.nonce));
        cmds.add(st(txid, 10, bet.ownercommsid));
        cmds.add(st(txid, 11, bet.arbcommsid));
        cmds.add(st(txid, 12, "1"));
        cmds.add(st(txid, 13, id.pubkey));
        cmds.add(st(txid, 14, id.hexaddr));
        cmds.add(st(txid, 15, Num.plain(bet.amount)));
        cmds.add(st(txid, 16, id.commsId));
        cmds.add(st(txid, 17, "0"));
        cmds.add("txnsign id:" + txid + " publickey:auto");
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        SignGate.submit(gate -> CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
            public void ok(JSONObject last) {
                gate.free();
                node.cmd("txndelete id:" + txid, NodeApi.Cb.NOOP);
                done.ok();   // inflight released on next confirmation scan
            }
            public void fail(String msg) {
                gate.free();
                inflightCoins.remove(fundId);   // build failed → coin didn't spend, free it
                done.fail(msg);
            }
        }));
    }

    private static String st(String txid, int port, String val) {
        return "txnstate id:" + txid + " port:" + port + " value:" + val;
    }

    /** Prune inflight reservations to coins still sendable (drops spent ones). Called each block. */
    public void pruneInflight(Set<String> stillSendable) { inflightCoins.retainAll(stillSendable); }

    /** Query sendable coinids and prune inflight to them (a spent funding coin is released). */
    public void refreshInflight() {
        if (inflightCoins.isEmpty()) return;   // nothing reserved → skip the (possibly large) query
        node.cmd("coins sendable:true tokenid:0x00 depth:400", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                java.util.Set<String> present = ConcurrentHashMap.newKeySet();
                JSONArray arr = r.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c != null) present.add(c.optString("coinid", ""));
                }
                if (!inflightCoins.isEmpty()) pruneInflight(present);
            }
            public void onError(String e) {}
        });
    }

    // ---------------------------------------------------------------- TIMEOUT refund (MAST leaf)
    /**
     * Anyone can trigger the proportional refund once the coin is older than the timeout: owner gets
     * their lock back (port 15), counter gets the rest. Attaches the timeout MAST proof, sets port
     * 20 = 3. The leaf carries no SIGNEDBY, so no contract signature is needed.
     */
    public void timeout(Bet bet, Done done) {
        final String txid = tag("timeout");
        BigDecimal os = bet.ownerstake;                 // port 15, always set on a matched coin
        BigDecimal counterPart = Num.sub(bet.amount, os);
        String scripts = mastArg(OpenlyContract.LEAF_TIMEOUT, OpenlyContract.PROOF_TIMEOUT);
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid);
        cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(os)
                + " address:" + bet.owneraddr + " storestate:false");
        cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(counterPart)
                + " address:" + bet.counteraddr + " storestate:false");
        cmds.add("txnstate id:" + txid + " port:20 value:3");
        cmds.add("txnscript id:" + txid + " scripts:" + scripts);
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        SignGate.submit(gate -> CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
            public void ok(JSONObject last) {
                gate.free();
                node.cmd("txndelete id:" + txid, NodeApi.Cb.NOOP);
                done.ok();
            }
            public void fail(String msg) { gate.free(); done.fail(msg); }
        }));
    }

    // ---------------------------------------------------------------- REFRESH (keep-alive)
    /**
     * Re-lock a coin to reset @COINAGE inside the visibility horizon. Phase 0: owner-signed relock,
     * all state preserved. Phase 1: MAST refresh leaf, state 0–16 pinned + refreshcount+1, signed by
     * owner or counter. Every phase-1 spend sets port 20 = 3.
     */
    public void refresh(Bet bet, String myBetPk, Done done) {
        final String txid = tag("refresh");
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid);
        cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(bet.amount)
                + " address:" + OpenlyContract.ADDR + " storestate:true");
        // preserve ports 0–16; phase 1 bumps refreshcount and needs the MAST leaf + port 20
        if (bet.phase == 1) {
            cmds.add(st(txid, 17, String.valueOf(bet.refreshcount + 1)));
            cmds.add(st(txid, 20, "3"));
            cmds.add("txnscript id:" + txid + " scripts:"
                    + mastArg(OpenlyContract.LEAF_REFRESH, OpenlyContract.PROOF_REFRESH));
            cmds.add("txnsign id:" + txid + " publickey:" + myBetPk);
        } else {
            cmds.add("txnsign id:" + txid + " publickey:" + bet.ownerpk);
        }
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        SignGate.submit(gate -> CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
            public void ok(JSONObject last) {
                gate.free();
                node.cmd("txndelete id:" + txid, NodeApi.Cb.NOOP);
                done.ok();
            }
            public void fail(String msg) { gate.free(); done.fail(msg); }
        }));
    }

    private static String mastArg(String script, String proof) {
        try {
            JSONObject o = new JSONObject();
            o.put(script, proof);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    // ---------------------------------------------------------------- ARBITER resolve (phase 1)
    /**
     * Arbiter declares the outcome: input the contract coin, pay the winner pot−fee and the arbiter
     * fee = pot/10, state port 20 = outcome, sign with the arbiter key, gated post.
     */
    public void resolve(Bet bet, int outcome, Done done) {
        BigDecimal pot = bet.amount;
        BigDecimal fee = Num.fee(pot);
        BigDecimal winnings = Num.sub(pot, fee);
        boolean ownerWins = outcome == bet.side;
        String winnerAddr = ownerWins ? bet.owneraddr : bet.counteraddr;
        final String txid = tag("resolve");
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid);
        cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(winnings)
                + " address:" + winnerAddr + " storestate:false");
        cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(fee)
                + " address:" + bet.arbaddr + " storestate:false");
        cmds.add("txnstate id:" + txid + " port:20 value:" + outcome);
        cmds.add("txnsign id:" + txid + " publickey:" + bet.arbpk);
        cmds.add("txnbasics id:" + txid);
        cmds.add("txnpost id:" + txid);
        SignGate.submit(gate -> CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
            public void ok(JSONObject last) {
                gate.free();
                node.cmd("txndelete id:" + txid, NodeApi.Cb.NOOP);
                done.ok();
            }
            public void fail(String msg) { gate.free(); done.fail(msg); }
        }));
    }

    // ---------------------------------------------------------------- SETTLE build (proposer)
    public interface Exported { void ok(String hex); void fail(String msg); }

    /**
     * Proposer builds a 2-of-2 settlement: input the contract coin only, two payout outputs
     * (winner pot-le, loser le; or the void pair), state port 20 = outcome, sign with MY exact bet
     * key (never auto), export the hex, then txndelete (no lingering pending txn). The exported hex
     * goes to the counterparty who validates it via {@link CoSigner} before co-signing.
     */
    public void buildSettle(Bet bet, int outcome, String myBetPk,
                            String winnerAddr, BigDecimal winnerAmt,
                            String loserAddr, BigDecimal loserAmt, Exported cb) {
        final String txid = tag("settle");
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + txid);
        // scriptmmr:true bakes the contract coin's SCRIPT + MMR PROOF into the txn. That proof travels
        // with txnexport→txnimport intact (verified: basic/scripts/mmrproofs all stay true through the
        // round-trip), so the co-signer needs NO txnbasics — essential because the co-signer's node only
        // coinnotify's the shared contract address and can't rebuild the proof itself. This is the
        // original Wager MDS's working pattern; the earlier proposer-txnbasics clobbered it.
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid + " scriptmmr:true");
        cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(winnerAmt)
                + " address:" + winnerAddr + " storestate:false");
        cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(loserAmt)
                + " address:" + loserAddr + " storestate:false");
        cmds.add("txnstate id:" + txid + " port:20 value:" + outcome);
        // VOID (outcome 2) leaves the main script's self-settle branch (guarded o LTE 1) and resolves
        // via the MAST void leaf — its proof must be attached, and it travels with txnexport to the
        // co-signer. TRUE/FALSE (0/1) settle on the main branch, no proof needed.
        if (outcome == 2) {
            cmds.add("txnscript id:" + txid + " scripts:"
                    + mastArg(OpenlyContract.LEAF_VOID, OpenlyContract.PROOF_VOID));
        }
        // Sign with MY EXPLICIT bet key. `publickey:auto` attaches NOTHING when spending a script
        // (contract) address — the node can't infer which wallet key controls a script coin — leaving
        // the settle txn with `signatures:0`, so the contract's `SIGNEDBY(ok) AND SIGNEDBY(ck)` fails.
        cmds.add("txnsign id:" + txid + " publickey:" + myBetPk);
        // NO txnbasics here (original Wager MDS pattern): scriptmmr:true already put the proof+script in,
        // and the explicit-key txnsign serializes into the export. Adding txnbasics would bake a second,
        // node-relative proof that the co-signer's re-basics then clobbers → mmrproofs:false at settle.
        SignGate.submit(gate -> CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
            public void ok(JSONObject last) {
                node.cmd("txnexport id:" + txid, new NodeApi.Cb() {
                    public void onResult(JSONObject r) {
                        gate.free();
                        String hex = r.optJSONObject("response") != null
                                ? r.optJSONObject("response").optString("data", "") : "";
                        node.cmd("txndelete id:" + txid, NodeApi.Cb.NOOP);   // no lingering pending txn
                        if (hex.isEmpty()) cb.fail("export empty"); else cb.ok(hex);
                    }
                    public void onError(String e) {
                        gate.free();
                        node.cmd("txndelete id:" + txid, NodeApi.Cb.NOOP);
                        cb.fail("export: " + e);
                    }
                });
            }
            public void fail(String msg) { gate.free(); cb.fail(msg); }
        }));
    }
}
