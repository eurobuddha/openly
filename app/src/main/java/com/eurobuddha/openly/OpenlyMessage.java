package com.eurobuddha.openly;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Typed comms message (modeled on atomix OtcMessage). Sealed + signed by the crypto provider,
 * carried in state[99] of a 1-nano coin at the Openly channel address.
 *
 * ref = the bet NONCE (never the proposition — proposition text is never a key). Sender is always
 * authenticated against the on-chain pinned commsid of the bet's parties before any action.
 */
public class OpenlyMessage {

    // types
    public static final String SETTLE_PROPOSE = "SETTLE_PROPOSE";
    public static final String SETTLE_TXN     = "SETTLE_TXN";     // chunk of a partially-signed txn
    public static final String SETTLE_ACCEPT  = "SETTLE_ACCEPT";
    public static final String SETTLE_REJECT  = "SETTLE_REJECT";
    public static final String DISPUTE        = "DISPUTE";
    public static final String DISPUTE_WITHDRAW = "DISPUTE_WITHDRAW";  // caller retracts an arbiter summons
    public static final String ARB_RESULT     = "ARB_RESULT";
    public static final String CHAT           = "CHAT";          // per-bet chat; text in `statement`

    public String type;
    public String ref;         // bet nonce
    public String randomid;    // dedup id
    public String from;        // sender publicId (set by crypto on open; set to my id on send)
    public String to;          // recipient publicId
    public long date;

    // payload (type-dependent)
    public String coinid;
    public int outcome = -1;
    public String winnerAmt, loserAmt, txnsha3, hexchunk;
    public int seq = 0, total = 1;
    public String txpowid, reason, statement;

    public String raw;         // the full JSON as received/sent (for chunk reassembly / audit)

    public byte[] toWire() {
        try {
            JSONObject j = new JSONObject();
            j.put("type", type); j.put("ref", ref); j.put("randomid", randomid);
            j.put("to", to); j.put("date", date);
            if (coinid != null) j.put("coinid", coinid);
            if (outcome >= 0) j.put("outcome", outcome);
            if (winnerAmt != null) j.put("winnerAmt", winnerAmt);
            if (loserAmt != null) j.put("loserAmt", loserAmt);
            if (txnsha3 != null) j.put("txnsha3", txnsha3);
            if (hexchunk != null) j.put("hexchunk", hexchunk);
            j.put("seq", seq); j.put("total", total);
            if (txpowid != null) j.put("txpowid", txpowid);
            if (reason != null) j.put("reason", reason);
            if (statement != null) j.put("statement", statement);
            return j.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) { return new byte[0]; }
    }

    public static OpenlyMessage fromWire(byte[] plaintext, String fromPublicId) {
        try {
            String s = new String(plaintext, StandardCharsets.UTF_8);
            JSONObject j = new JSONObject(s);
            OpenlyMessage m = new OpenlyMessage();
            m.type = j.optString("type", "");
            m.ref = j.optString("ref", "");
            m.randomid = j.optString("randomid", "");
            m.to = j.optString("to", "");
            m.from = fromPublicId;
            m.date = j.optLong("date", 0);
            m.coinid = j.optString("coinid", "");
            m.outcome = j.optInt("outcome", -1);
            m.winnerAmt = j.optString("winnerAmt", null);
            m.loserAmt = j.optString("loserAmt", null);
            m.txnsha3 = j.optString("txnsha3", null);
            m.hexchunk = j.optString("hexchunk", null);
            m.seq = j.optInt("seq", 0);
            m.total = j.optInt("total", 1);
            m.txpowid = j.optString("txpowid", null);
            m.reason = j.optString("reason", null);
            m.statement = j.optString("statement", null);
            m.raw = s;
            return m;
        } catch (Exception e) { return null; }
    }
}
