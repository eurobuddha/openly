package com.eurobuddha.openly;

import android.os.Handler;
import android.os.Looper;

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

    /** Scan the channel for inbound messages (call on each block; the scanner self-throttles). */
    public void scan(int block) {
        if (scanner != null) scanner.scan(block);
    }

    /** Router callback from the scanner: seal opened + signature verified. Dedup + hand to sink. */
    private boolean route(String coinid, Opened opened, JSONObject coin) {
        if (opened == null || !opened.valid) return false;
        OpenlyMessage m = OpenlyMessage.fromWire(opened.plaintext, opened.fromPublicId);
        if (m == null || m.randomid.isEmpty() || m.ref.isEmpty()) return false;
        if (!m.to.equals(myId())) return false;            // not addressed to me
        m.coinid = m.coinid == null || m.coinid.isEmpty() ? coinid : m.coinid;
        // sink does party-authentication (needs on-chain bet state) + storage + dispatch
        final boolean[] fresh = {false};
        // sink runs on the caller (scanner) thread; marshal DB + UI safely
        try { fresh[0] = sink.onMessage(m, coin); } catch (Exception ignored) {}
        return fresh[0];
    }

    /** Seal + send a message to a recipient publicId, behind SignGate (inside CommsTransport). */
    public void send(String toPublicId, OpenlyMessage m, CommsTransport.SendCb cb) {
        if (crypto == null) { cb.onFailed("comms not ready"); return; }
        m.from = myId();
        String blob = crypto.seal(toPublicId, m.toWire());
        if (blob == null) { cb.onFailed("seal failed"); return; }
        CommsTransport.postBlob(node, OpenlyContract.MAIL_ADDR, CommsTransport.MESSAGE_AMOUNT,
                CommsTransport.NATIVE, blob, null, cb);
    }
}
