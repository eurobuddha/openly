package com.eurobuddha.openly;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import com.eurobuddha.comms.NodeApi;

/**
 * This node's stable betting identity: one signing public key + payout address, chosen once and
 * reused (getaddress returns a different wallet key each call, so we pin the first). Persisted in
 * SharedPreferences. The comms publicId (ports 10/11/16) is added in Phase 5.
 */
public class Identity {

    private static final String PREFS = "openly_identity";
    private static final String K_PK = "pubkey";
    private static final String K_ADDR = "hexaddr";

    public String pubkey = "";
    public String hexaddr = "";
    public String commsId = "0x" + "00".repeat(8);  // placeholder until Phase 5

    private final SharedPreferences prefs;
    private final NodeApi node;

    public Identity(Context c, NodeApi node) {
        this.prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.node = node;
        pubkey = prefs.getString(K_PK, "");
        hexaddr = prefs.getString(K_ADDR, "");
    }

    public boolean ready() { return !pubkey.isEmpty() && !hexaddr.isEmpty(); }

    /** Load the pinned identity, or mint one from getaddress and persist it. */
    public void ensure(Runnable done) {
        if (ready()) { if (done != null) done.run(); return; }
        node.cmd("getaddress", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                try {
                    JSONObject resp = r.getJSONObject("response");
                    pubkey = resp.getString("publickey");
                    hexaddr = resp.getString("address");
                    prefs.edit().putString(K_PK, pubkey).putString(K_ADDR, hexaddr).apply();
                } catch (Exception ignored) {}
                if (done != null) done.run();
            }
            public void onError(String e) { if (done != null) done.run(); }
        });
    }
}
