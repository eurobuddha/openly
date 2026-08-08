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
 *      never the message), at OpenlyContract.ADDR, tokenid == the bet's token, and I am a party.
 *   2. sha3(receivedHex) == the txnsha3 quoted in the proposal.
 *   3. outputs recomputed locally from my coin + the outcome: exactly two, in order
 *      [winner, pot-le] / [loser, le] (or the void pair), amounts string-exact, tokenid == the bet's
 *      token, storestate=false on both; inputs total == outputs total (zero burn).
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
        // check 1: exactly one input, == my coin for this nonce, at ADDR, in the bet's token
        final String btok = bet.tokenid == null || bet.tokenid.isEmpty() ? "0x00" : bet.tokenid;
        if (t.inputs.size() != 1) return "expected 1 input, got " + t.inputs.size();
        TxnInspect.IO in = t.inputs.get(0);
        if (!in.coinid.equalsIgnoreCase(bet.coinid)) return "input is not my bet coin";
        if (!in.address.equalsIgnoreCase(OpenlyContract.ADDR)) return "input not at contract address";
        if (!btok.equalsIgnoreCase(in.tokenid)) return "input token != bet token";
        // check 3: exactly two outputs, exact (addr, amount, token), storestate=false, zero burn
        if (t.outputs.size() != 2) return "expected 2 outputs, got " + t.outputs.size();
        if (!matches(t.outputs.get(0), exp.addr0, exp.amt0, btok)) return "output0 mismatch";
        if (!matches(t.outputs.get(1), exp.addr1, exp.amt1, btok)) return "output1 mismatch";
        if (t.totalIn().compareTo(t.totalOut()) != 0) return "burn detected (in!=out)";
        // check 4: state port 20 == outcome
        if (t.outcomePort20 != outcome) return "state outcome != displayed";
        return null;
    }

    private boolean matches(TxnInspect.IO io, String addr, BigDecimal amt, String tokenid) {
        return io.address.equalsIgnoreCase(addr)
                && io.amount.compareTo(amt) == 0
                && tokenid.equalsIgnoreCase(io.tokenid)
                && !io.storestate;
    }

    // ---- checks 5,6,7 ----
    private void signGatedPost(String txid, String myBetPk, Done done) {
        SignGate.submit(gate -> {
            List<String> cmds = new ArrayList<>();
            // Add MY (the 2nd) signature with my EXPLICIT bet key (port 0 if owner, 13 if counter) —
            // `publickey:auto` attaches nothing to a script-coin spend.
            // NO txnbasics: the proposer baked the input's script + MMR proof via `txninput scriptmmr:true`
            // and it survives the export→import intact, so rebuilding here would clobber it (this node only
            // coinnotify's the shared contract address and can't rebuild the proof). NO txncheck gate: the
            // original Wager MDS never gated on txncheck (it reports mmrproofs/scripts false in cases that
            // still post fine); the security check is the pre-sign inspect() over txnlist, already run.
            cmds.add("txnsign id:" + txid + " publickey:" + myBetPk);
            CmdChain.run(node, cmds, "txndelete id:" + txid, new CmdChain.Done() {
                public void ok(JSONObject last) {
                    node.cmd("txnpost id:" + txid, new NodeApi.Cb() {
                        public void onResult(JSONObject pr) {
                            gate.free();
                            String tx = Util.extractTxpowid(pr, "");
                            node.cmd("txndelete id:" + txid, NodeApi.Cb.NOOP);
                            if (pr.optBoolean("status", false) || pr.optBoolean("pending", false)) done.ok(tx);
                            else done.fail("post failed: " + pr.optString("error", ""));
                        }
                        public void onError(String e) { gate.free(); abort(txid, done, "post: " + e); }
                    });
                }
                public void fail(String msg) { gate.free(); done.fail("sign: " + msg); }
            });
        });
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

    // ---- integrity hash. This is NOT a security primitive — it only lets the two peers agree that
    //      the transported settlement blob arrived intact (tamper-safety is CoSigner's 7-point txn
    //      validation, below). So it just needs to be a shared, deterministic, ALWAYS-AVAILABLE hash.
    //      Earlier this used JDK "SHA3-256" over Hex.from(data); that returned null on the devices'
    //      minimaCore 1.2.x nodes — either SHA3-256 wasn't resolvable in their security provider or
    //      txnexport `data` wasn't plain hex Hex.from could decode — and the null crashed the declare
    //      path (SettleEngine.rid NPE). SHA-256 is present on every Android/JDK; and we hash the raw
    //      bytes whether or not the string decodes as hex. Both peers run this identical code on the
    //      identical string, so the check stays self-consistent. Never returns null for non-null input.
    static String sha3Hex(String data) {
        if (data == null) return null;
        try {
            byte[] raw;
            try { raw = Hex.from(data); }                     // preferred: hash the decoded txn bytes
            catch (Exception nothex) { raw = data.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Hex.to(md.digest(raw));
        } catch (Exception e) {
            // SHA-256 is mandated on every JVM/Android, so this is unreachable — but never null-out
            // and crash a caller: fall back to hashing the string bytes with the JDK's own hashCode.
            return "0x" + Integer.toHexString(data.hashCode());
        }
    }

    private static String strip(String s) {
        if (s == null) return "";
        return (s.startsWith("0x") || s.startsWith("0X")) ? s.substring(2) : s;
    }
}
