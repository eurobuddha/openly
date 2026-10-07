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
import com.eurobuddha.comms.MaximaTransport;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.Opened;
import com.eurobuddha.comms.Sodium;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    // --- Maxima (fast off-chain path) ---
    private final MaximaTransport maxima;
    /** Peer commsId (lowercase) → their last-known Maxima MxG address, learned from any inbound msg. */
    private final Map<String, String> peerMx = new ConcurrentHashMap<>();
    /** randomids sent over Maxima awaiting the peer app's MX_ACK; if none arrives, an on-chain copy is posted. */
    private final Set<String> pendingAck = ConcurrentHashMap.newKeySet();

    private static final CommsTransport.SendCb NOOP_SEND = new CommsTransport.SendCb() {
        public void onSent(String t) {}
        public void onFailed(String e) {}
    };

    public OpenlyComms(MainActivity act, NodeApi node, OpenlyDb db, Sink sink) {
        this.act = act;
        this.node = node;
        this.db = db;
        this.sink = sink;
        this.maxima = new MaximaTransport(node);
    }

    /** Refresh Maxima availability + my address. Call on setup and each block (two cheap node calls). */
    public void refreshMaxima() { if (maxima != null) maxima.refresh(); }
    public boolean maximaAvailable() { return maxima != null && maxima.available(); }

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
                    refreshMaxima();                 // prime Maxima availability + my MxG address
                    if (done != null) done.run();
                });
            } catch (Throwable e) {
                // Throwable, not Exception: Sodium.get() loads libsodium via JNA and can throw
                // UnsatisfiedLinkError (an Error, not an Exception) on a device where the .so won't
                // load (missing ABI / page-alignment). Catching only Exception let that escape on the
                // io thread → uncaught → app crash at startup. Degrade gracefully instead.
                android.util.Log.e(TAG, "comms identity init failed (crypto unavailable)", e);
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

    /** Router callback from the on-chain scanner: seal opened + signature verified. Dedup + hand to sink. */
    private boolean route(String coinid, Opened opened, JSONObject coin) {
        if (opened == null || !opened.valid) return false;
        OpenlyMessage m = OpenlyMessage.fromWire(opened.plaintext, opened.fromPublicId);
        if (m == null || m.randomid.isEmpty() || m.ref.isEmpty()) return false;
        m.coinid = m.coinid == null || m.coinid.isEmpty() ? coinid : m.coinid;
        return dispatch(m, coin);
    }

    /** Shared handling for an authenticated inbound message from EITHER transport (on-chain or Maxima):
     *  learn the peer's Maxima address, swallow ACKs, else hand to the sink (which does party-auth + dedup). */
    private boolean dispatch(OpenlyMessage m, JSONObject coin) {
        // Case-insensitive: on-chain comms ids are UPPERCASE hex, runtime Hex.to() is lowercase.
        boolean forMe = m.to != null && m.to.equalsIgnoreCase(myId());
        Log.d(TAG, "dispatch: type=" + m.type + " forMe=" + forMe
                + " from=" + (m.from == null ? "?" : (m.from.length() > 12 ? m.from.substring(0, 12) : m.from)));
        if (!forMe) return false;                          // not addressed to me
        // Learn/refresh the peer's Maxima address from any authenticated inbound → future sends go Maxima-first.
        if (m.from != null && m.maxaddr != null && !m.maxaddr.isEmpty()) peerMx.put(m.from.toLowerCase(), m.maxaddr);
        // An MX_ACK means the peer's app received a Maxima message → cancel our pending on-chain fallback.
        // Never sinks/stores; the acked randomid unpredictable (sealed), so a forged ACK can't suppress anything.
        if (OpenlyMessage.MX_ACK.equals(m.type)) { if (m.statement != null) pendingAck.remove(m.statement); return false; }
        // sink does party-authentication (needs on-chain bet state) + storage + dispatch
        final boolean[] fresh = {false};
        try { fresh[0] = sink.onMessage(m, coin); } catch (Exception ignored) {}
        return fresh[0];
    }

    /** Seal + send a message to a recipient publicId at the SHARED OPENLY channel (small messages). */
    public void send(String toPublicId, OpenlyMessage m, CommsTransport.SendCb cb) {
        sendTo(OpenlyContract.MAIL_ADDR, toPublicId, m, cb);
    }

    /** Seal + send to a recipient publicId. Maxima-first when it's connected AND we know the peer's MxG
     *  address; otherwise (or on Maxima failure) the sealed blob is posted on-chain at {@code address}
     *  (per-bet {@link OpenlyContract#settleAddr} or the shared MAIL_ADDR) exactly as before. The blob is
     *  IDENTICAL on both transports, so encryption/auth/dedup (randomid) are unchanged. */
    public void sendTo(String address, String toPublicId, OpenlyMessage m, CommsTransport.SendCb cb) {
        if (crypto == null) { cb.onFailed("comms not ready"); return; }
        m.from = myId();
        if (maxima != null) m.maxaddr = maxima.myAddress();   // let the peer learn where to reach me over Maxima
        final String blob = crypto.seal(toPublicId, m.toWire());
        if (blob == null) { cb.onFailed("seal failed"); return; }
        final String rid = m.randomid;
        final Runnable onChain = () -> CommsTransport.postBlob(node, address, CommsTransport.MESSAGE_AMOUNT,
                CommsTransport.NATIVE, blob, null, cb);
        final String peer = toPublicId == null ? null : peerMx.get(toPublicId.toLowerCase());
        if (maxima != null && maxima.available() && peer != null && !peer.isEmpty()) {
            maxima.send(peer, "0x" + blob, new MaximaTransport.DeliverCb() {
                public void onResult(boolean delivered, String msgid) {
                    if (!delivered) { onChain.run(); return; }   // peer node offline → on-chain guarantees it lands
                    cb.onSent(msgid == null ? "" : msgid);       // node accepted → confirm to the caller now
                    armAckFallback(rid, address, blob);          // silent on-chain copy if the peer APP never acks
                }
                public void onError(String e) { onChain.run(); }
            });
        } else {
            onChain.run();                                       // Maxima absent / peer unknown → on-chain, as today
        }
    }

    /** After a Maxima delivered:true, wait ~8s for the peer app's MX_ACK; if none, post the same sealed
     *  blob on-chain (durability for the "peer node online but Openly app closed" gap). Same randomid, so
     *  the peer de-dupes if it later sees both. */
    private void armAckFallback(final String randomid, final String address, final String blob) {
        if (randomid == null || randomid.isEmpty()) return;
        pendingAck.add(randomid);
        ui.postDelayed(() -> {
            if (pendingAck.remove(randomid)) {   // still pending → no ack arrived
                Log.d(TAG, "maxima ack timeout → on-chain fallback for " + randomid);
                CommsTransport.postBlob(node, address, CommsTransport.MESSAGE_AMOUNT,
                        CommsTransport.NATIVE, blob, null, NOOP_SEND);
            }
        }, 8000);
    }

    /**
     * Handle an inbound MAXIMA NOTIFY event (application:openly). Opens the SAME sealed blob, routes it
     * through the shared {@link #dispatch} path (dedup + sink), and — for a real message addressed to me
     * — sends an MX_ACK back over Maxima so the sender can skip its on-chain fallback.
     */
    public void handleMaximaEvent(final JSONObject data) {
        if (crypto == null || data == null) return;
        if (!"openly".equals(data.optString("application", ""))) return;
        final String blob = data.optString("data", "");
        if (blob.isEmpty()) return;
        io.execute(() -> {
            final Opened o;
            try { o = crypto.open(blob); } catch (Throwable t) { return; }   // not for me / malformed
            if (o == null || !o.valid) return;
            final OpenlyMessage m = OpenlyMessage.fromWire(o.plaintext, o.fromPublicId);
            if (m == null || m.randomid == null || m.randomid.isEmpty() || m.ref == null || m.ref.isEmpty()) return;
            ui.post(() -> {
                boolean isAck = OpenlyMessage.MX_ACK.equals(m.type);
                dispatch(m, null);
                if (!isAck && m.to != null && m.to.equalsIgnoreCase(myId()) && m.from != null && !m.from.isEmpty())
                    sendAck(m);
            });
        });
    }

    /** Best-effort Maxima receipt so the sender needn't fall back on-chain. Sealed to the sender's commsId. */
    private void sendAck(OpenlyMessage orig) {
        if (maxima == null || !maxima.available()) return;
        String peer = peerMx.get(orig.from.toLowerCase());
        if (peer == null || peer.isEmpty()) return;
        OpenlyMessage ack = new OpenlyMessage();
        ack.type = OpenlyMessage.MX_ACK;
        ack.ref = orig.ref;
        ack.to = orig.from;
        ack.from = myId();
        ack.maxaddr = maxima.myAddress();
        ack.statement = orig.randomid;                       // the randomid we are acknowledging
        ack.date = System.currentTimeMillis();
        ack.randomid = "0xACK" + (orig.randomid.startsWith("0x") ? orig.randomid.substring(2) : orig.randomid);
        String blob = crypto.seal(orig.from, ack.toWire());
        if (blob == null) return;
        maxima.send(peer, "0x" + blob, new MaximaTransport.DeliverCb() {
            public void onResult(boolean d, String id) {}
            public void onError(String e) {}
        });
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
