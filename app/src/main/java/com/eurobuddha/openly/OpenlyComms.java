package com.eurobuddha.openly;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsScanner;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.LocalEcCryptoProvider;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.Opened;
import com.eurobuddha.comms.Sodium;

import com.goterl.lazysodium.LazySodium;

/**
 * Openly's messaging orchestrator over the vendored comms module.
 *
 *  - Identity is HKDF-derived from the Minima vault seed (context "openly"), read once and wiped.
 *    The publicId (boxPk||signPk) becomes this node's commsid, pinned on-chain in bet state.
 *  - Sends are sealed (X25519 sealed box + Ed25519 sig) into state[99] of a 1-nano coin at the
 *    Openly channel address, behind SignGate.
 *  - The scanner (NEWBLOCK-driven, adaptive depth) opens inbound blobs; authenticated messages are
 *    handed to a {@link Sink}. Sender authentication against the on-chain party commsid happens in
 *    the sink (needs current bet state), so the router here only guarantees seal+signature validity.
 */
public class OpenlyComms {

    public interface Sink {
        /** An opened, signature-valid message. Return true if it produced a NEW stored item. */
        boolean onMessage(OpenlyMessage m, JSONObject coin);
    }

    private final MainActivity act;
    private final NodeApi node;
    private final OpenlyDb db;
    private final Sink sink;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private LazySodium ls;
    private CommsIdentity identity;
    private LocalEcCryptoProvider crypto;
    private CommsScanner scanner;
    private boolean ready = false;

    public OpenlyComms(MainActivity act, NodeApi node, OpenlyDb db, Sink sink) {
        this.act = act;
        this.node = node;
        this.db = db;
        this.sink = sink;
    }

    public boolean ready() { return ready; }
    public String myId() { return identity != null ? identity.publicId() : ""; }

    /** Derive the comms identity from the vault seed, then build the crypto + scanner. */
    public void setup(Runnable done) {
        node.cmd("vault action:seed", new NodeApi.Cb() {
            public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (ikm.isEmpty()) { if (done != null) done.run(); return; }
                deriveIdentity(ikm, done);
            }
            public void onError(String m) { if (done != null) done.run(); }
        });
    }

    private void deriveIdentity(String ikm, Runnable done) {
        io.execute(() -> {
            try {
                ls = Sodium.get();
                byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                CommsIdentity id = CommsIdentity.fromSeed(ls, seed, "openly");
                LocalEcCryptoProvider cp = new LocalEcCryptoProvider(ls, id);
                ui.post(() -> {
                    identity = id;
                    crypto = cp;
                    act.identity.commsId = id.publicId();   // pin into bet state ports 10/11/16
                    scanner = new CommsScanner(node, crypto,
                            new CommsScanner.MetaStore() {
                                public String getMeta(String k, String def) { return db.getMeta(k, def); }
                                public void setMeta(String k, String v) { db.setMeta(k, v); }
                            },
                            OpenlyContract.MAIL_ADDR, this::route,
                            (ok, newCount) -> {},
                            true);
                    ready = true;
                    if (done != null) done.run();
                });
            } catch (Exception e) {
                ui.post(() -> { if (done != null) done.run(); });
            }
        });
    }

    private static final String TAG = "OpenlyComms";

    /** Scan the channel for inbound messages (call on each block; the scanner self-throttles). */
    public void scan(int block) {
        if (scanner != null) scanner.scan(block);
    }

    /**
     * Force the NEXT scan to look back a wide window by resetting the scanner's tip bookmark. Recovers
     * proposals that arrived while the app was backgrounded (the forward-only scanner would otherwise
     * miss them once its tip advanced). Idempotent — dedup on messages.randomid. Call on resume / when
     * a settlement is pending.
     */
    private long lastDeep = 0;
    public void deepRescan(int block) {
        long now = System.currentTimeMillis();
        if (now - lastDeep < 15000) return;    // throttle — avoid repeated heavy decrypt on resume/tab
        lastDeep = now;
        String tag = OpenlyContract.MAIL_ADDR.length() > 10
                ? OpenlyContract.MAIL_ADDR.substring(2, 10) : OpenlyContract.MAIL_ADDR;
        // Look back a small bounded window (shared mail address can be large — keep the reply well
        // under the node's 256 KB IPC limit and decryption cheap).
        int back = block > 25 ? block - 25 : 0;
        db.setMeta("scanned_tip_" + tag, String.valueOf(back));
        Log.d(TAG, "deepRescan: tip→" + back + " at block " + block);
        scan(block);
    }

    /** Router callback from the scanner: seal opened + signature verified. Dedup + hand to sink. */
    private boolean route(String coinid, Opened opened, JSONObject coin) {
        if (opened == null || !opened.valid) return false;
        OpenlyMessage m = OpenlyMessage.fromWire(opened.plaintext, opened.fromPublicId);
        if (m == null || m.randomid.isEmpty() || m.ref.isEmpty()) return false;
        // Case-insensitive: on-chain comms ids are UPPERCASE hex, runtime Hex.to() is lowercase.
        boolean forMe = m.to.equalsIgnoreCase(myId());
        Log.d(TAG, "route: type=" + m.type + " ref=" + (m.ref.length() > 12 ? m.ref.substring(0, 12) : m.ref)
                + " forMe=" + forMe + " from=" + (m.from == null ? "?" : (m.from.length() > 12 ? m.from.substring(0, 12) : m.from)));
        if (!forMe) return false;                          // not addressed to me
        m.coinid = m.coinid == null || m.coinid.isEmpty() ? coinid : m.coinid;
        // sink does party-authentication (needs on-chain bet state) + storage + dispatch
        final boolean[] fresh = {false};
        // sink runs on the caller (scanner) thread; marshal DB + UI safely
        try { fresh[0] = sink.onMessage(m, coin); } catch (Exception ignored) {}
        return fresh[0];
    }

    /** Seal + send a message to a recipient publicId at the SHARED OPENLY channel (small messages). */
    public void send(String toPublicId, OpenlyMessage m, CommsTransport.SendCb cb) {
        sendTo(OpenlyContract.MAIL_ADDR, toPublicId, m, cb);
    }

    /** Seal + send to a recipient publicId at a SPECIFIC address — used for per-bet settlement blobs
     *  (posted to {@link OpenlyContract#settleAddr}) so the receiver reads a private, un-bloated address. */
    public void sendTo(String address, String toPublicId, OpenlyMessage m, CommsTransport.SendCb cb) {
        if (crypto == null) { cb.onFailed("comms not ready"); return; }
        m.from = myId();
        String blob = crypto.seal(toPublicId, m.toWire());
        if (blob == null) { cb.onFailed("seal failed"); return; }
        CommsTransport.postBlob(node, address, CommsTransport.MESSAGE_AMOUNT,
                CommsTransport.NATIVE, blob, null, cb);
    }

    /**
     * Targeted receive for one bet's settlement mailbox. Reads every coin at {@code address} (the
     * bet's {@link OpenlyContract#settleAddr}), opens state[99] with my box key, and routes any message
     * addressed to me — same dedup + sink path as the block scanner. Only the 1-2 proposal coins for
     * this bet live there, so an unbounded read is safe (no shared-address bloat / IPC-limit risk) and
     * recovers the proposal no matter how many blocks ago it landed. Idempotent (dedup on randomid).
     */
    public void scanSettleAddress(final String address) {
        if (crypto == null || address == null || address.isEmpty()) return;
        node.cmd("coinnotify action:add address:" + address, NodeApi.Cb.NOOP);
        // BOUNDED: re-declares pile a ~28 KB storestate coin at this per-bet address, so an unbounded
        // `coins address:` reply blows past the 256 KB Binder/IPC limit and force-closes the app. We only
        // need the latest proposal — read newest-first, capped, and stop at the first coin that opens for me.
        node.cmd("coins address:" + address + " order:desc depth:16", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                org.json.JSONArray arr = r.optJSONArray("response");
                if (arr == null) return;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject coin = arr.optJSONObject(i);
                    if (coin == null || coin.optBoolean("spent", false)) continue;
                    String blob = CommsScanner.statePort(coin, 99);
                    if (blob == null) continue;
                    Opened o = crypto.open(blob);
                    if (o == null || !o.valid) continue;
                    route(coin.optString("coinid", ""), o, coin);
                    break;   // newest proposal that opened for me — done (older ones are stale re-declares)
                }
            }
            public void onError(String e) { Log.d(TAG, "scanSettleAddress err: " + e); }
        });
    }
}
