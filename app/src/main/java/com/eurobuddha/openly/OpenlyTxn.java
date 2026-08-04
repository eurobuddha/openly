package com.eurobuddha.openly;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
}
