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
                     String arbpk, String arbaddr, String arbCommsId, int timeout, int settleblock,
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
            st.put("11", arbCommsId == null || arbCommsId.isEmpty() ? id.commsId : arbCommsId);
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
        node.cmd("coins sendable:true tokenid:0x00", new NodeApi.Cb() {
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

    /** Prune inflight reservations to coins still present (called after a confirmation scan). */
    public void pruneInflight(Set<String> present) { inflightCoins.retainAll(present); }

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
        cmds.add("txninput id:" + txid + " coinid:" + bet.coinid);
        cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(winnerAmt)
                + " address:" + winnerAddr + " storestate:false");
        cmds.add("txnoutput id:" + txid + " amount:" + Num.plain(loserAmt)
                + " address:" + loserAddr + " storestate:false");
        cmds.add("txnstate id:" + txid + " port:20 value:" + outcome);
        cmds.add("txnsign id:" + txid + " publickey:" + myBetPk);
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
