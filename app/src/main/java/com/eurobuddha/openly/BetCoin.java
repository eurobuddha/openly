package com.eurobuddha.openly;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * A single coin at the contract address, with its V4 state ports flattened.
 * Adapted from casino/Coin.java. The `state` map holds ports exactly as stored on-chain.
 */
public class BetCoin {

    public String coinid = "";
    public String address = "";
    public String tokenid = "0x00";
    public String amount = "0";     // human-readable Minima amount (@AMOUNT)
    public long created = -1;       // block height created in (for @COINAGE)

    public final Map<Integer, String> state = new HashMap<>();

    public static BetCoin from(JSONObject c) {
        BetCoin x = new BetCoin();
        x.coinid = c.optString("coinid", "");
        x.address = c.optString("address", "");
        x.tokenid = c.optString("tokenid", "0x00");
        boolean minima = "0x00".equalsIgnoreCase(x.tokenid);
        x.amount = minima ? c.optString("amount", "0")
                          : c.optString("tokenamount", c.optString("amount", "0"));
        String created = c.optString("created", "");
        if (!created.isEmpty()) {
            try { x.created = Long.parseLong(created); } catch (NumberFormatException ignored) {}
        }
        JSONArray st = c.optJSONArray("state");
        if (st != null) {
            for (int i = 0; i < st.length(); i++) {
                JSONObject e = st.optJSONObject(i);
                if (e == null) continue;
                int port = e.optInt("port", -1);
                if (port < 0) continue;
                x.state.put(port, e.optString("data", ""));
            }
        }
        return x;
    }

    public String at(int port) {
        String v = state.get(port);
        return v == null ? "" : v;
    }

    public int ageFrom(int tipBlock) {
        if (created < 0 || tipBlock <= 0) return 0;
        return Math.max(0, tipBlock - (int) created);
    }
}
