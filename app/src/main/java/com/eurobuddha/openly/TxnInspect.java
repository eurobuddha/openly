package com.eurobuddha.openly;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed view of a `txnlist id:X` response, so {@link CoSigner} can validate an imported settlement
 * transaction BEFORE signing it. Fails closed: any shape it doesn't recognise leaves the lists empty,
 * and CoSigner's count checks then reject.
 *
 * Shape (verified against a live node):
 *   response = { transaction:{ inputs:[coin…], outputs:[coin…], state:[{port,data}] }, witness }
 *   a coin/output carries coinid, amount(String), address, tokenid, storestate(boolean).
 */
public class TxnInspect {

    public static class IO {
        public String coinid = "", address = "", tokenid = "0x00";
        public BigDecimal amount = BigDecimal.ZERO;
        public boolean storestate = false;
    }

    public final List<IO> inputs = new ArrayList<>();
    public final List<IO> outputs = new ArrayList<>();
    public int outcomePort20 = -1;
    public boolean parsed = false;

    public static TxnInspect of(JSONObject txnlistResponse) {
        TxnInspect t = new TxnInspect();
        try {
            JSONObject resp = txnlistResponse;
            // response may be an array [ {id,transaction,…} ] or the object directly
            Object r = txnlistResponse.opt("response");
            if (r instanceof JSONArray && ((JSONArray) r).length() > 0) resp = ((JSONArray) r).getJSONObject(0);
            else if (r instanceof JSONObject) resp = (JSONObject) r;
            JSONObject txn = resp.optJSONObject("transaction");
            if (txn == null) return t;
            readIO(txn.optJSONArray("inputs"), t.inputs);
            readIO(txn.optJSONArray("outputs"), t.outputs);
            JSONArray st = txn.optJSONArray("state");
            if (st != null) {
                for (int i = 0; i < st.length(); i++) {
                    JSONObject e = st.optJSONObject(i);
                    if (e != null && e.optInt("port", -1) == 20) {
                        try { t.outcomePort20 = Integer.parseInt(e.optString("data", "-1").trim()); }
                        catch (Exception ignore) {}
                    }
                }
            }
            t.parsed = true;
        } catch (Exception ignored) {}
        return t;
    }

    private static void readIO(JSONArray arr, List<IO> into) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            IO io = new IO();
            io.coinid = c.optString("coinid", "");
            io.address = c.optString("address", "");
            io.tokenid = c.optString("tokenid", "0x00");
            try { io.amount = Num.of(c.optString("amount", "0")); } catch (Exception e) { io.amount = BigDecimal.ZERO; }
            io.storestate = c.optBoolean("storestate", c.optBoolean("keepstate", false));
            into.add(io);
        }
    }

    public BigDecimal totalIn() {
        BigDecimal s = BigDecimal.ZERO;
        for (IO io : inputs) s = Num.add(s, io.amount);
        return s;
    }

    public BigDecimal totalOut() {
        BigDecimal s = BigDecimal.ZERO;
        for (IO io : outputs) s = Num.add(s, io.amount);
        return s;
    }
}
