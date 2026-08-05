package com.eurobuddha.openly;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    // Nonces I have just accepted/filled locally. The fill spends the phase-0 coin, but the chain
    // still reports it unspent until the fill mines (~1-2 min), so it would otherwise linger in
    // Markets + My Bets→Open. Suppress those nonces from `open` optimistically until the matching
    // phase-1 coin actually appears (then the entry self-clears — see scan()).
    private final Set<String> pendingMatched = new HashSet<>();
    /** Nonces flagged disputed via an on-chain marker at my payout address (I am the arbiter). */
    public final Set<String> disputedNonces = new HashSet<>();
    private int tipBlock = 0;

    // Parse coins off the UI thread — a mainnet response can be large, and each coin costs a 64-key
    // ownership check. Only the tiny list-swap + listener run on main. A guard drops overlapping scans.
    private final ExecutorService parseIo = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean scanning = false;

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
        if (scanning) return;         // drop overlapping scans — a slow parse can't pile up
        scanning = true;
        this.tipBlock = tip;
        node.cmd("coins address:" + OpenlyContract.ADDR, new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                final JSONArray arr = r.optJSONArray("response");
                parseIo.execute(() -> {                       // parse off the UI thread
                    List<Bet> o = new ArrayList<>();
                    List<Bet> m = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject cj = arr.optJSONObject(i);
                            if (cj == null || cj.optBoolean("spent", false)) continue;
                            BetCoin bc = BetCoin.from(cj);
                            Bet b = Bet.from(bc, myKeys, tipBlock);
                            if (!b.isWellFormed()) continue;   // skip state-less orphans (unspendable, not real bets)
                            if (b.phase == 0) o.add(b);
                            else if (b.phase == 1) m.add(b);
                        }
                    }
                    // A LIVE (phase-1) bet supersedes any OPEN (phase-0) coin with the same nonce — a
                    // taken bet must not also show as open. `matched` nonces confirm a fill landed, so
                    // clear those from the optimistic set; then drop from `open` anything superseded
                    // (already matched) or optimistically pending (I just tapped Accept).
                    Set<String> live = new HashSet<>();
                    for (Bet b : m) if (b.nonce != null) live.add(b.nonce);
                    pendingMatched.removeAll(live);            // fill confirmed → stop optimistic hiding
                    List<Bet> oFiltered = new ArrayList<>(o.size());
                    for (Bet b : o) {
                        if (b.nonce != null && (live.contains(b.nonce) || pendingMatched.contains(b.nonce))) continue;
                        oFiltered.add(b);
                    }
                    ui.post(() -> {                            // swap + notify on main (cheap)
                        open.clear(); open.addAll(oFiltered);
                        matched.clear(); matched.addAll(m);
                        scanning = false;
                        if (listener != null) listener.onScanned();
                    });
                });
            }
            public void onError(String e) {
                scanning = false;
                if (listener != null) listener.onScanned();
            }
        });
    }

    /**
     * Scan my own payout address for on-chain dispute markers (1-nano coins carrying a bet nonce in
     * port 50). Populates {@link #disputedNonces} so ArbiterView can show which cases were disputed
     * — the arbiter must NOT resolve a matched bet unless a party actually disputed it.
     */
    public void scanDisputes(String myPayoutAddr) {
        if (myPayoutAddr == null || myPayoutAddr.isEmpty()) return;
        // depth-bounded: dispute markers are recent, and an unbounded query at a reused address can
        // exceed the node's 256 KB IPC limit.
        node.cmd("coins address:" + myPayoutAddr + " depth:400", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                Set<String> found = new HashSet<>();
                JSONArray arr = r.optJSONArray("response");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject cj = arr.optJSONObject(i);
                        if (cj == null || cj.optBoolean("spent", false)) continue;
                        BetCoin bc = BetCoin.from(cj);
                        String nonce = bc.at(OpenlyTxn.DISPUTE_NONCE_PORT);
                        if (!nonce.isEmpty()) found.add(nonce);
                    }
                }
                disputedNonces.clear();
                disputedNonces.addAll(found);
            }
            public void onError(String e) {}
        });
    }

    /** I just accepted/filled this bet — hide its open coin immediately until the phase-1 coin lands. */
    public void markFilled(String nonce) { if (nonce != null && !nonce.isEmpty()) pendingMatched.add(nonce); }

    public boolean isMyKey(String pk) { return myKeys.contains(pk); }
    public int keyCount() { return myKeys.size(); }
}
