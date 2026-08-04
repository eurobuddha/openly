package com.eurobuddha.openly;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.eurobuddha.comms.NodeApi;

/**
 * Reads the public board (all coins at the contract address) and my wallet keys, parses
 * every coin into a {@link Bet}, and splits into OPEN (phase 0) and MATCHED (phase 1).
 *
 * Chain is the source of truth. The board is scanned only on a new block (never in a loop —
 * heavy IPC responses can crash the node). A node returns 0 coins at a shared address until it
 * tracks it, so MainActivity issues `coinnotify action:add address:ADDR` at startup.
 */
public class BetScanner {

    public interface Listener { void onScanned(); }

    private final NodeApi node;
    private final Listener listener;

    private final Set<String> myKeys = new HashSet<>();
    public final List<Bet> open = new ArrayList<>();
    public final List<Bet> matched = new ArrayList<>();
    private int tipBlock = 0;

    public BetScanner(NodeApi node, Listener listener) {
        this.node = node;
        this.listener = listener;
    }

    /** Load all wallet public keys once (isMine/isMyCounter/isMyArb depend on the full set). */
    public void loadKeys(Runnable done) {
        node.cmd("keys", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                try {
                    Object resp = r.opt("response");
                    JSONArray list = null;
                    if (resp instanceof JSONArray) list = (JSONArray) resp;
                    else if (resp instanceof JSONObject) list = ((JSONObject) resp).optJSONArray("keys");
                    if (list != null) {
                        for (int i = 0; i < list.length(); i++) {
                            JSONObject k = list.optJSONObject(i);
                            String pk = k != null ? k.optString("publickey", "") : list.optString(i, "");
                            if (!pk.isEmpty()) myKeys.add(pk);
                        }
                    }
                } catch (Exception ignored) {}
                if (done != null) done.run();
            }
            public void onError(String e) { if (done != null) done.run(); }
        });
    }

    public void scan(int tip) {
        this.tipBlock = tip;
        node.cmd("coins address:" + OpenlyContract.ADDR, new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                List<Bet> o = new ArrayList<>();
                List<Bet> m = new ArrayList<>();
                JSONArray arr = r.optJSONArray("response");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject cj = arr.optJSONObject(i);
                        if (cj == null || cj.optBoolean("spent", false)) continue;
                        BetCoin bc = BetCoin.from(cj);
                        Bet b = Bet.from(bc, myKeys, tipBlock);
                        if (b.phase == 0) o.add(b);
                        else if (b.phase == 1) m.add(b);
                    }
                }
                open.clear(); open.addAll(o);
                matched.clear(); matched.addAll(m);
                if (listener != null) listener.onScanned();
            }
            public void onError(String e) { if (listener != null) listener.onScanned(); }
        });
    }

    public boolean isMyKey(String pk) { return myKeys.contains(pk); }
    public int keyCount() { return myKeys.size(); }
}
