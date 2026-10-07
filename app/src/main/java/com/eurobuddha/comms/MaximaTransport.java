package com.eurobuddha.comms;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Thin wrapper over the Minima node's built-in <b>Maxima</b>, reached through the SAME
 * {@link NodeApi} Broadcast-Intent IPC the rest of the app uses. Maxima is instant, off-chain,
 * free app-to-app messaging — the fast path when a relay host is connected; the app falls back to
 * the on-chain sealed-box transport otherwise.
 *
 * This class carries an already-sealed hex blob as the Maxima {@code data:} payload, so encryption,
 * authentication and de-dup are unchanged from the on-chain path — only the carrier differs. No
 * {@link SignGate} is needed: Maxima signs with the node's Maxima key internally and mints no coin.
 *
 * Command surface (identical to the proven mds/thunder pattern):
 *   maxima action:hosts                                   → response.hosts[].connected  (availability)
 *   maxima action:info                                    → response.contact (my MxG… address)
 *   maxima action:send to:&lt;MxG…&gt; application:openly data:0x&lt;hex&gt;  → response.delivered / msgid
 */
public class MaximaTransport {

    public interface DeliverCb {
        void onResult(boolean delivered, String msgid);
        void onError(String message);
    }

    private final NodeApi node;
    private volatile boolean connected = false;
    private volatile String myAddress = "";   // my current MxG… contact address

    public MaximaTransport(NodeApi node) { this.node = node; }

    /** Usable right now = a host is connected AND we know our own sendable address. */
    public boolean available() { return connected && !myAddress.isEmpty(); }
    public String myAddress() { return myAddress; }
    public boolean connected() { return connected; }

    /** Refresh the connected-host + my-address caches. Two cheap node calls; safe to run each block. */
    public void refresh() {
        node.cmd("maxima action:hosts", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                // "Maxima still starting up.." comes back as status:false → treat as not connected.
                if (!j.optBoolean("status", true)) { connected = false; return; }
                JSONObject r = j.optJSONObject("response");
                JSONArray hosts = r != null ? r.optJSONArray("hosts") : j.optJSONArray("response");
                boolean c = false;
                if (hosts != null) {
                    for (int i = 0; i < hosts.length(); i++) {
                        JSONObject h = hosts.optJSONObject(i);
                        if (h != null && h.optBoolean("connected", false)) { c = true; break; }
                    }
                }
                connected = c;
            }
            public void onError(String e) { connected = false; }
        });
        node.cmd("maxima action:info", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                if (!j.optBoolean("status", true)) return;   // keep last known address
                JSONObject r = j.optJSONObject("response");
                if (r != null) {
                    String c = r.optString("contact", "");
                    if (!c.isEmpty()) myAddress = c;
                }
            }
            public void onError(String e) { /* keep last known address */ }
        });
    }

    /** Send a sealed hex blob to a peer's MxG contact address for application:openly. */
    public void send(String toMx, String dataHex, DeliverCb cb) {
        // A contact is learned from a remote peer. Keep it a single command argument:
        // permit encoded keys, DNS/IP relay addresses and IPv6 zone syntax, never delimiters.
        if (toMx == null || !toMx.matches("[A-Za-z0-9@._:\\[\\]%-]+")) {
            cb.onError("invalid maxima address"); return;
        }
        if (dataHex == null) { cb.onError("invalid maxima data"); return; }
        final String data = dataHex.startsWith("0x") ? dataHex : "0x" + dataHex;
        if (!data.matches("0x(?:[0-9a-fA-F]{2})+")) { cb.onError("invalid maxima data"); return; }
        node.cmd("maxima action:send to:" + toMx + " application:openly data:" + data, new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                if (!j.optBoolean("status", true)) { cb.onError(j.optString("message", "maxima not ready")); return; }
                JSONObject r = j.optJSONObject("response");
                boolean delivered = (r != null && r.optBoolean("delivered", false)) || j.optBoolean("delivered", false);
                String msgid = r != null ? r.optString("msgid", "") : j.optString("msgid", "");
                cb.onResult(delivered, msgid);
            }
            public void onError(String e) { cb.onError(e); }
        });
    }
}
