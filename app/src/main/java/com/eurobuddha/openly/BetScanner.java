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
    /** Nonces a party WITHDREW from arbitration — the arbiter must not resolve these even though the
     *  on-chain marker still exists. Seeded/persisted by MainActivity; subtracted in finishDisputes. */
    public final Set<String> withdrawnNonces = new HashSet<>();

    /** Persists disputed nonces so a dispute survives the marker aging past the scan depth AND an app
     *  restart (backed by db meta in MainActivity). A dispute is a permanent fact until the bet settles. */
    public interface DisputeStore { Set<String> load(); void save(Set<String> nonces); }
    private DisputeStore disputeStore;
    public void setDisputeStore(DisputeStore s) {
        this.disputeStore = s;
        if (s != null) { Set<String> p = s.load(); if (p != null) disputedNonces.addAll(p); }
    }
    /** Nonces I (the arbiter) just resolved — optimistic "confirming" state until the coin is spent.
     *  Self-clears in scan() once the bet leaves scanner.matched (mirror of pendingMatched, inverted). */
    public final Set<String> resolvedNonces = new HashSet<>();
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

    /**
     * Load all wallet public keys (isMine/isMyCounter/isMyArb depend on the full set).
     * Keys are stored UPPER-CASED and compared upper-cased (see {@link Bet#from}) so a device always
     * recognises its own coins regardless of the hex case a given node build reports — the same
     * case-mismatch class that once dropped inbound comms. Only ADDS keys, never clears the set, so a
     * transient empty/errored `keys` response can't wipe a good set; a later call heals it.
     */
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
                            if (!pk.isEmpty()) myKeys.add(pk.toUpperCase());
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
        // Self-heal: if the one startup loadKeys returned empty/errored (a transient IPC hiccup), every
        // coin would read as not-mine forever ("No bets yet", no Cancel, no auto-supersede). Reload the
        // keys first, then scan — so a device recovers ownership of its own bets on the next block.
        if (myKeys.isEmpty()) {
            loadKeys(() -> doScan(tip));
            return;
        }
        doScan(tip);
    }

    private void doScan(int tip) {
        if (scanning) return;
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
                    final Set<String> live = new HashSet<>();
                    for (Bet b : m) if (b.nonce != null) live.add(b.nonce);
                    ui.post(() -> {                            // swap + notify on main (cheap)
                        // pendingMatched/resolvedNonces are ALSO mutated on the UI thread (markFilled/
                        // markResolved from button callbacks), so keep every access on this one thread to
                        // avoid a cross-thread HashSet race. The open filter reads pendingMatched, so it
                        // runs here too.
                        pendingMatched.removeAll(live);        // fill confirmed → stop optimistic hiding
                        resolvedNonces.retainAll(live);        // resolve confirmed (coin spent) → drop "confirming"
                        List<Bet> oFiltered = new ArrayList<>(o.size());
                        for (Bet b : o) {
                            if (b.nonce != null && (live.contains(b.nonce) || pendingMatched.contains(b.nonce))) continue;
                            oFiltered.add(b);
                        }
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
    public void scanDisputes(Set<String> addrs) {
        if (addrs == null || addrs.isEmpty()) return;
        // Scan EVERY address the arbiter might receive a dispute marker at — its current identity payout
        // address AND each arbitrated bet's on-chain arbaddr (port 3). These can differ: a party's bet
        // pins whatever arbiter address it was given, which may be an older key the node still owns
        // (isMyArb true) even after the app re-minted its identity. Scanning only the current address
        // would silently miss disputes on those bets. Depth-bounded to stay under the 256 KB IPC limit.
        final Set<String> found = java.util.Collections.synchronizedSet(new HashSet<>());
        final int[] pending = { addrs.size() };
        for (final String addr : addrs) {
            if (addr == null || addr.isEmpty()) { finishDisputes(pending, found); continue; }
            node.cmd("coins address:" + addr + " depth:400 megammr:true", new NodeApi.Cb() {
                public void onResult(JSONObject r) {
                    JSONArray arr = r.optJSONArray("response");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject cj = arr.optJSONObject(i);
                            if (cj == null || cj.optBoolean("spent", false)) continue;
                            String nonce = BetCoin.from(cj).at(OpenlyTxn.DISPUTE_NONCE_PORT);
                            if (!nonce.isEmpty()) found.add(nonce);
                        }
                    }
                    finishDisputes(pending, found);
                }
                public void onError(String e) { finishDisputes(pending, found); }
            });
        }
    }

    /** Commit the merged dispute set only after every address scan has returned. A dispute, once raised,
     *  stays until the bet settles: the marker coin can age past the scan depth, so UNION freshly-found
     *  markers with what we already knew (incl. persisted), then prune to nonces whose bet is still
     *  matched (a resolved bet leaves `matched`). Persist so it survives the depth window AND a restart. */
    private void finishDisputes(int[] pending, Set<String> found) {
        synchronized (pending) { if (--pending[0] > 0) return; }
        Set<String> durable = new HashSet<>(found);
        durable.addAll(disputedNonces);
        Set<String> live = new HashSet<>();
        for (Bet b : matched) if (b.nonce != null) live.add(b.nonce);
        durable.retainAll(live);
        durable.removeAll(withdrawnNonces);   // a withdrawn dispute is disarmed — arbiter must not resolve it
        disputedNonces.clear();
        disputedNonces.addAll(durable);
        if (disputeStore != null) disputeStore.save(durable);
    }

    /** I just accepted/filled this bet — hide its open coin immediately until the phase-1 coin lands. */
    public void markFilled(String nonce) { if (nonce != null && !nonce.isEmpty()) pendingMatched.add(nonce); }

    /** I (the arbiter) just resolved this bet — show it as "confirming" until the coin is spent. */
    public void markResolved(String nonce) { if (nonce != null && !nonce.isEmpty()) resolvedNonces.add(nonce); }

    private static String up(String s) { return s == null ? "" : s.toUpperCase(); }

    public boolean isMyKey(String pk) { return myKeys.contains(up(pk)); }
    public int keyCount() { return myKeys.size(); }

    /**
     * True when my pinned betting identity is provably NOT owned by the node I'm talking to — the node
     * was reseeded/replaced while this app kept its old identity, so bets posted under it can't be
     * settled or cancelled. Mirrors Atomix's IdentityWatch. An EMPTY key set is "cannot verify", never
     * a mismatch (a busy/locked node must not trip the guard), and there is deliberately NO self-heal —
     * silently re-minting would hide the fact that funds are stranded on a key the node can't derive.
     */
    public boolean identityOrphaned(String pinnedPk) {
        return !myKeys.isEmpty() && pinnedPk != null && !pinnedPk.isEmpty()
                && !myKeys.contains(up(pinnedPk));
    }
}
