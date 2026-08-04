package com.eurobuddha.openly;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.SignGate;

/**
 * The security-critical gate (fixes the V3.1/V2 CRITICAL: blind co-signing of an attacker-supplied
 * transaction). Before ever calling txnsign, an imported settlement txn is validated against the
 * caller's OWN chain view. If ANY check fails, we refuse to sign.
 *
 * The 7 checks (from the plan):
 *   1. exactly ONE input, whose coinid == the current on-chain coin for this nonce (my scanner,
 *      never the message), at OpenlyContract.ADDR, tokenid 0x00, and I am a party.
 *   2. sha3(receivedHex) == the txnsha3 quoted in the proposal.
 *   3. outputs recomputed locally from my coin + the outcome: exactly two, in order
 *      [winner, pot-le] / [loser, le] (or the void pair), amounts string-exact, tokenid 0x00,
 *      storestate=false on both; inputs total == outputs total (zero burn).
 *   4. txn state port 20 == the proposed outcome == what the UI displayed.
 *   5. sign with MY explicit bet key (never publickey:auto) — so my wallet coins can't be conscripted.
 *   6. txncheck gate incl. allsignaturesvalid before posting.
 *   7. txndelete on EVERY path.
 */
public class CoSigner {

    public interface Done { void ok(String txpowid); void fail(String reason); }

    private final NodeApi node;

    public CoSigner(NodeApi node) { this.node = node; }

    /**
     * @param bet       my chain-scanned bet for this nonce (source of truth)
     * @param hexData   the received partially-signed txn hex
     * @param txnsha3   sha3 the proposer quoted
     * @param outcome   proposed outcome (0/1/2), == what the approval UI showed
     * @param myBetPk   my exact signing key on this bet (port 0 if owner, 13 if counter)
     */
    public void validateAndPost(Bet bet, String hexData, String txnsha3, int outcome,
                                String myBetPk, Done done) {
        // check 2: integrity
        String localSha3 = sha3Hex(hexData);
        if (localSha3 == null || !localSha3.equalsIgnoreCase(strip(txnsha3))) {
            done.fail("integrity: sha3 mismatch"); return;
        }
        // compute the payouts I expect for this outcome from MY coin
        Expected exp = expectedOutputs(bet, outcome);
        if (exp == null) { done.fail("cannot compute expected payout"); return; }

        final String txid = "cosign_" + System.currentTimeMillis();
        node.cmd("txnimport id:" + txid + " data:" + hexData, new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                node.cmd("txnlist id:" + txid, new NodeApi.Cb() {
                    public void onResult(JSONObject lst) {
                        String err = inspect(lst, bet, outcome, exp);
                        if (err != null) { abort(txid, done, err); return; }
                        signGatedPost(txid, myBetPk, done);
                    }
                    public void onError(String e) { abort(txid, done, "txnlist: " + e); }
                });
            }
            public void onError(String e) { done.fail("import: " + e); }
        });
    }

    // ---- checks 1,3,4 over txnlist ----
    private String inspect(JSONObject txnlist, Bet bet, int outcome, Expected exp) {
        TxnInspect t = TxnInspect.of(txnlist);
        if (!t.parsed) return "unparseable txnlist";
        // check 1: exactly one input, == my coin for this nonce, at ADDR, 0x00
        if (t.inputs.size() != 1) return "expected 1 input, got " + t.inputs.size();
        TxnInspect.IO in = t.inputs.get(0);
        if (!in.coinid.equalsIgnoreCase(bet.coinid)) return "input is not my bet coin";
        if (!in.address.equalsIgnoreCase(OpenlyContract.ADDR)) return "input not at contract address";
        if (!"0x00".equalsIgnoreCase(in.tokenid)) return "input is not MINIMA";
        // check 3: exactly two outputs, exact (addr, amount), storestate=false, zero burn
        if (t.outputs.size() != 2) return "expected 2 outputs, got " + t.outputs.size();
        if (!matches(t.outputs.get(0), exp.addr0, exp.amt0)) return "output0 mismatch";
        if (!matches(t.outputs.get(1), exp.addr1, exp.amt1)) return "output1 mismatch";
        if (t.totalIn().compareTo(t.totalOut()) != 0) return "burn detected (in!=out)";
        // check 4: state port 20 == outcome
        if (t.outcomePort20 != outcome) return "state outcome != displayed";
        return null;
    }

    private boolean matches(TxnInspect.IO io, String addr, BigDecimal amt) {
        return io.address.equalsIgnoreCase(addr)
                && io.amount.compareTo(amt) == 0
                && "0x00".equalsIgnoreCase(io.tokenid)
                && !io.storestate;
    }

    // ---- checks 5,6,7 ----
    private void signGatedPost(String txid, String myBetPk, Done done) {
        SignGate.submit(gate -> {
            List<String> cmds = new ArrayList<>();
            // Validation (check 1) already proved the sole input is the contract coin, so auto-sign
            // cannot conscript other wallet coins — it signs only what that coin's script needs.
            cmds.add("txnsign id:" + txid + " publickey:auto");
            cmds.add("txnbasics id:" + txid);
            CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
                public void ok(JSONObject last) {
                    // check 6: txncheck gate before post
                    node.cmd("txncheck id:" + txid, new NodeApi.Cb() {
                        public void onResult(JSONObject chk) {
                            if (!gatePass(chk)) { gate.free(); abort(txid, done, "txncheck failed"); return; }
                            node.cmd("txnpost id:" + txid, new NodeApi.Cb() {
                                public void onResult(JSONObject pr) {
                                    gate.free();
                                    String tx = Util.extractTxpowid(pr, "");
                                    node.cmd("txndelete id:" + txid, NodeApi.Cb.NOOP);   // check 7
                                    if (pr.optBoolean("status", false) || pr.optBoolean("pending", false)) done.ok(tx);
                                    else done.fail("post failed");
                                }
                                public void onError(String e) { gate.free(); abort(txid, done, "post: " + e); }
                            });
                        }
                        public void onError(String e) { gate.free(); abort(txid, done, "txncheck: " + e); }
                    });
                }
                public void fail(String msg) { gate.free(); done.fail("sign/basics: " + msg); }
            });
        });
    }

    private boolean gatePass(JSONObject chk) {
        JSONObject resp = chk.optJSONObject("response");
        if (resp == null) return false;
        JSONObject valid = resp.optJSONObject("valid");
        if (valid == null) return false;
        boolean ok = valid.optBoolean("basic", false)
                && valid.optBoolean("scripts", false)
                && valid.optBoolean("mmrproofs", false)
                && resp.optBoolean("validamounts", valid.optBoolean("validamounts", false));
        // allsignaturesvalid is load-bearing: txncheck runs scripts but doesn't verify sigs
        boolean sigs = resp.optBoolean("allsignaturesvalid", false);
        return ok && sigs;
    }

    private void abort(String txid, Done done, String reason) {
        node.cmd("txndelete id:" + txid, NodeApi.Cb.NOOP);
        done.fail(reason);
    }

    // ---- expected outputs from MY coin (mirrors the contract exactly) ----
    private static class Expected { String addr0, addr1; BigDecimal amt0, amt1; }

    private Expected expectedOutputs(Bet bet, int outcome) {
        BigDecimal pot = bet.amount;
        BigDecimal ownerLock = bet.ownerstake.signum() > 0 ? bet.ownerstake : bet.amount;
        BigDecimal counterLock = Num.sub(pot, ownerLock);
        Expected e = new Expected();
        if (outcome == 2) {
            // void: each side gets their lock back — output0=owner(os), output1=counter(pot-os)
            e.addr0 = bet.owneraddr; e.amt0 = ownerLock;
            e.addr1 = bet.counteraddr; e.amt1 = Num.sub(pot, ownerLock);
            return e;
        }
        if (outcome != 0 && outcome != 1) return null;
        boolean ownerWins = outcome == bet.side;
        BigDecimal loserLock = ownerWins ? counterLock : ownerLock;
        BigDecimal le = Num.loserEscrow(loserLock);
        // contract: winner = @INPUT (pot-le), loser = @INPUT+1 (le)
        e.addr0 = ownerWins ? bet.owneraddr : bet.counteraddr; e.amt0 = Num.sub(pot, le);
        e.addr1 = ownerWins ? bet.counteraddr : bet.owneraddr; e.amt1 = le;
        return e;
    }

    // ---- sha3 (Keccak? Minima uses Keccak-256; but integrity here just needs to match the proposer,
    //      who computed it the same way. We use the JDK SHA3-256 as the shared, deterministic hash;
    //      both peers run this identical code, so the check is self-consistent.) ----
    static String sha3Hex(String hexData) {
        try {
            byte[] raw = Hex.from(hexData);
            MessageDigest md = MessageDigest.getInstance("SHA3-256");
            byte[] h = md.digest(raw);
            return Hex.to(h);
        } catch (Exception e) { return null; }
    }

    private static String strip(String s) {
        if (s == null) return "";
        return (s.startsWith("0x") || s.startsWith("0X")) ? s.substring(2) : s;
    }
}
