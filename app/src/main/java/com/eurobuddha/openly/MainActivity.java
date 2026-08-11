package com.eurobuddha.openly;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;

import com.eurobuddha.comms.NodeApi;

/**
 * Single-activity shell (family pattern: ViewPager + TabLayout, BaseView pages, no Fragments).
 *
 * Phase 1 scope: pair with the node, register the V4 contract, show live block height +
 * balance, host the tab pages. Contract reads/writes land in later phases.
 *
 * Refresh is push-driven: the node's NOTIFY broadcast (NEWBLOCK / NEWBALANCE) drives a
 * 400 ms-debounced reload — no timers, no polling (utxo/casino pattern).
 */
public class MainActivity extends AppCompatActivity {

    public static final String NODE_PKG = "org.minimarex.minimacore";

    /** The Activity currently in the foreground — the AutoProcessor/Service handoff reads this. */
    public static volatile boolean FOREGROUND = false;

    private NodeApi node;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ViewPager pager;
    private TabLayout tabs;
    private MainPager pagerAdapter;
    private TextView blockNo;
    private View pairingBanner;

    private BroadcastReceiver notifyReceiver;
    private boolean paired = false;
    private int currentBlock = 0;

    // ---- Openly state (read by views) ----
    public String contractAddr = OpenlyContract.ADDR;
    public String balance = "—";
    public String usdtBalance = "0";
    public BetScanner scanner;
    public Identity identity;
    public OpenlyTxn txn;
    public OpenlyDb db;
    public OpenlyComms comms;
    public SettleEngine settle;
    public AutoProcessor auto;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.load(this);
        Sfx.init(this);
        setContentView(R.layout.activity_main);

        View mainRoot = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            // Top clears the status bar; bottom clears the nav bar (or the keyboard when it's up).
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime));
            return insets;
        });

        blockNo = findViewById(R.id.blockNo);
        pairingBanner = findViewById(R.id.pairingBanner);
        findViewById(R.id.openNodeBtn).setOnClickListener(x -> openNodeApp());

        // Header: theme toggle restyles the whole app (no recreate) — nice for testing both looks.
        TextView designToggle = findViewById(R.id.designToggle);
        designToggle.setOnClickListener(x -> { Design.toggle(this); styleChrome(); renderAll(); });
        styleChrome();

        pager = findViewById(R.id.pager);
        tabs = findViewById(R.id.tabs);

        BaseView[] views = new BaseView[]{
                new MarketsView(this),
                new MyBetsView(this),
                new CreateView(this),
                new ArbiterView(this),
                new HistoryView(this)
        };
        String[] titles = {"MARKETS", "MY BETS", "POST", "ARBITER", "HISTORY"};
        pagerAdapter = new MainPager(views, titles);
        pager.setAdapter(pagerAdapter);
        pager.setOffscreenPageLimit(5);
        tabs.setupWithViewPager(pager);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            public void onTabSelected(TabLayout.Tab tab) {
                BaseView v = pagerAdapter.viewAt(tab.getPosition());
                v.refresh();
                v.onShown();
            }
            public void onTabUnselected(TabLayout.Tab tab) {}
            public void onTabReselected(TabLayout.Tab tab) {
                pagerAdapter.viewAt(tab.getPosition()).refresh();
            }
        });

        node = new NodeApi(this, enabled -> ui.post(() -> setPaired(enabled)));
        scanner = new BetScanner(node, () -> ui.post(this::onScanned));
        identity = new Identity(this, node);
        txn = new OpenlyTxn(node, identity);
        db = new OpenlyDb(this);
        // Persist disputes + "arbiter called" across the marker's depth window and app restarts (db meta).
        scanner.setDisputeStore(new BetScanner.DisputeStore() {
            public java.util.Set<String> load() { return csvToSet(db.getMeta("disputes", "")); }
            public void save(java.util.Set<String> nonces) { db.setMeta("disputes", setToCsv(nonces)); }
        });
        arbiterCalled.addAll(csvToSet(db.getMeta("arbcalled", "")));
        scanner.withdrawnNonces.addAll(csvToSet(db.getMeta("withdrawn", "")));
        comms = new OpenlyComms(this, node, db, this::onCommsMessage);
        settle = new SettleEngine(this);
        auto = new AutoProcessor(this, scanner, txn);
        registerNotifyReceiver();
        ensureContract();
        identity.ensure(() -> {
            comms.setup(null);   // derive comms identity → pins identity.commsId
            scanner.loadKeys(this::requestReload);
        });
        try { OpenlyService.start(this); } catch (Exception ignored) {}

        installStatusStrip();
        showVersion();

        // First-run onboarding overlay on top of the content frame.
        android.widget.FrameLayout onb = new android.widget.FrameLayout(this);
        ((android.view.ViewGroup) findViewById(android.R.id.content)).addView(onb,
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        Onboarding.showIfNeeded(this, onb);
    }

    /**
     * Authenticated inbound message (seal + signature valid). Phase 5: verify the sender is a party
     * to the referenced bet (against on-chain pinned commsid) and store it. Phase 6 dispatches
     * SETTLE_* to the settle engine.
     */
    private boolean onCommsMessage(OpenlyMessage m, JSONObject coin) {
        Bet bet = betByNonce(m.ref);
        if (bet == null) {
            android.util.Log.d("OpenlyComms", "sink: no bet for nonce " + m.ref);
            return false;                                     // unknown bet — drop
        }
        // Case-insensitive: on-chain comms ids are UPPERCASE, runtime/decoded ones are lowercase.
        boolean senderIsParty = m.from.equalsIgnoreCase(bet.ownercommsid)
                || m.from.equalsIgnoreCase(bet.countercommsid)
                || m.from.equalsIgnoreCase(bet.arbcommsid);
        android.util.Log.d("OpenlyComms", "sink: type=" + m.type + " party=" + senderIsParty);
        if (!senderIsParty) return false;                     // spoofed sender — drop
        boolean fresh = db.insertMessageIfNew(m, true);
        if (fresh) {
            if (OpenlyMessage.SETTLE_PROPOSE.equals(m.type)) settle.onInboundPropose(m);
            else if (OpenlyMessage.SETTLE_REJECT.equals(m.type)) {
                // Today SETTLE_REJECT means exactly one thing: the counterparty escalated to the arbiter
                // (self-settle is the only non-arbiter path, so a rejection == a summons). Flip me to AT
                // ARBITER too and tell me. NOTE: if REJECT is ever reused for "let's renegotiate the
                // outcome" without the arbiter, split that into its own message type — don't overload this.
                setArbiterCalled(m.ref, true);
                ui.post(() -> toast("The other side called the arbiter — awaiting their decision"));
            } else if (OpenlyMessage.DISPUTE_WITHDRAW.equals(m.type)) {
                // A party withdrew. Party side: back to self-settle. Arbiter side: disarm — suppress this
                // nonce so the durable union can't re-show it as resolvable.
                setArbiterCalled(m.ref, false);
                if (scanner != null && m.ref != null) {
                    scanner.withdrawnNonces.add(m.ref);
                    scanner.disputedNonces.remove(m.ref);
                    db.setMeta("withdrawn", setToCsv(scanner.withdrawnNonces));
                }
                ui.post(() -> toast("Arbiter withdrawn — settle it directly again"));
            }
            ui.post(this::refreshCurrent);
        }
        return fresh;
    }

    private boolean amArbiterOfLiveBet() {
        for (Bet b : scanner.matched) if (b.isMyArb) return true;
        return false;
    }

    public Bet betByNonce(String nonce) {
        if (nonce == null || nonce.isEmpty()) return null;
        for (Bet b : scanner.matched) if (nonce.equalsIgnoreCase(b.nonce)) return b;
        for (Bet b : scanner.open) if (nonce.equalsIgnoreCase(b.nonce)) return b;
        return null;
    }

    /** True when the pinned betting identity isn't owned by the node (reseed/replace) → new bets blocked. */
    public volatile boolean identityOrphaned = false;
    private TextView identityBanner;

    // ---- feedback: a persistent one-line status strip + rolling log ----
    private TextView statusStrip;
    private final java.util.ArrayDeque<String> logBuffer = new java.util.ArrayDeque<>();

    private void installStatusStrip() {
        LinearLayout root = findViewById(R.id.main);
        if (root == null) return;
        statusStrip = new TextView(this);
        statusStrip.setTextSize(11);
        statusStrip.setTypeface(Design.mono());
        statusStrip.setTextColor(Design.DIM());
        statusStrip.setText("Ready");
        statusStrip.setSingleLine(true);
        statusStrip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusStrip.setBackgroundColor(Design.SURFACE2());
        int px = Design.dp(this, 16), py = Design.dp(this, 6);
        statusStrip.setPadding(px, py, px, py);
        statusStrip.setOnClickListener(v -> showLogDialog());
        // Insert just under the header (index 1), above the pairing banner / tabs.
        root.addView(statusStrip, 1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Identity-mismatch banner (index 2): high-visibility, sticky (never overwritten by toasts),
        // shown only when the pinned identity isn't owned by the node. Blocks new bets.
        identityBanner = new TextView(this);
        identityBanner.setTextSize(12);
        identityBanner.setTypeface(Design.sansBold());
        identityBanner.setTextColor(0xFFFFFFFF);
        identityBanner.setBackgroundColor(Design.NEG());
        identityBanner.setPadding(px, Design.dp(this, 10), px, Design.dp(this, 10));
        identityBanner.setText("⚠  Your node's seed no longer matches this app — new bets are blocked. "
                + "Recover any stuck bets via the arbiter or timeout, then reinstall Openly.");
        identityBanner.setVisibility(View.GONE);
        root.addView(identityBanner, 2, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void updateIdentityBanner() {
        if (identityBanner != null) identityBanner.setVisibility(identityOrphaned ? View.VISIBLE : View.GONE);
    }

    private void showVersion() {
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        TextView sub = findViewById(R.id.brandSub);
        if (sub != null && !ver.isEmpty()) sub.setText("PROPOSE ANYTHING · v" + ver);
    }

    private void showLogDialog() {
        StringBuilder sb = new StringBuilder();
        for (String s : logBuffer) sb.append(s).append("\n");
        androidx.appcompat.app.AlertDialog.Builder b = new androidx.appcompat.app.AlertDialog.Builder(this);
        b.setTitle("Activity").setMessage(sb.length() == 0 ? "No activity yet" : sb.toString())
                .setPositiveButton("Close", null).show();
    }

    /** Persistent feedback: updates the status strip + rolling log (tap the strip for history). */
    public void toast(String msg) {
        if (statusStrip != null) {
            statusStrip.setText(msg);
            Design.pulse(statusStrip, Design.ACCENT());
        }
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        logBuffer.addFirst(ts + "  " + msg);
        while (logBuffer.size() > 40) logBuffer.removeLast();
    }

    /** Refresh the currently visible page. */
    public void refreshCurrent() { onScanned(); }

    /** Light detent haptic for the stake slider. */
    public void performHapticFeedback() {
        if (pager != null) pager.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    // coinids already auto-cancelled as superseded, so we never re-issue a cancel for the same coin.
    private final java.util.Set<String> autoCancelled = new java.util.HashSet<>();

    private long lastSettleScan = 0;

    private void onScanned() {
        if (scanner != null && identity != null) {
            identityOrphaned = scanner.identityOrphaned(identity.pubkey);
            updateIdentityBanner();
        }
        if (auto != null) auto.process(currentBlock);
        autoCancelSuperseded();
        scanPendingSettlements();
        detectSettlements();
        detectExternalSettlements();
        autoSettleAgreed();
        checkPendingPosts();
        reconcileArbiterSets();
        BaseView v = pagerAdapter.viewAt(pager.getCurrentItem());
        v.refresh();
    }

    // ---- settlement payoff reveal ----
    private final java.util.Map<String, Bet> settleWatch = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> settleOutcome = new java.util.HashMap<>();
    private final java.util.Set<String> settleShown = new java.util.HashSet<>();

    /** Remember an outcome I declared, so when this matched bet later settles on-chain I reveal the result. */
    public void watchSettlement(Bet b, int outcome) {
        if (b == null || b.nonce == null) return;
        settleWatch.put(b.nonce, b);
        settleOutcome.put(b.nonce, outcome);
    }

    // ---- pending post/counter confirmation (staged "submitted → confirming → live" feedback) ----
    /** A just-posted bet/counter awaiting its coin to appear on-chain. */
    public static class PendingPost {
        public final String nonce, proposition, kind;   // kind: POST | COUNTER
        public final int side, blockPosted;
        public final java.math.BigDecimal stake, want;
        PendingPost(String n, String p, int s, java.math.BigDecimal st, java.math.BigDecimal w, int blk, String k) {
            nonce = n; proposition = p; side = s; stake = st; want = w; blockPosted = blk; kind = k;
        }
    }
    private final java.util.Map<String, PendingPost> pendingPosts = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Runnable> pendingConfirm = new java.util.HashMap<>();

    /** Register a just-broadcast post/counter; onConfirm (optional) fires once the coin lands. */
    public void addPendingPost(String nonce, String prop, int side, java.math.BigDecimal stake,
                               java.math.BigDecimal want, String kind, Runnable onConfirm) {
        if (nonce == null || nonce.isEmpty()) return;
        pendingPosts.put(nonce, new PendingPost(nonce, prop, side, stake, want, currentBlock, kind));
        if (onConfirm != null) pendingConfirm.put(nonce, onConfirm);
        toast(("COUNTER".equals(kind) ? "Counter" : "Bet") + " submitted — confirming on-chain…");
    }

    public boolean isPendingPost(String nonce) { return nonce != null && pendingPosts.containsKey(nonce); }
    public java.util.List<PendingPost> pendingPostList() { return new java.util.ArrayList<>(pendingPosts.values()); }

    /** Each scan: a pending nonce that now has a coin on-chain is CONFIRMED; give up after ~25 blocks. */
    private void checkPendingPosts() {
        if (pendingPosts.isEmpty() || scanner == null) return;
        java.util.Iterator<java.util.Map.Entry<String, PendingPost>> it = pendingPosts.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, PendingPost> e = it.next();
            PendingPost p = e.getValue();
            if (betByNonce(e.getKey()) != null) {                 // landed on-chain
                it.remove();
                Runnable cb = pendingConfirm.remove(e.getKey());
                if (cb != null) cb.run();
                Sfx.lock();
                toast(("COUNTER".equals(p.kind) ? "Counter" : "Bet") + " live on-chain ✓");
            } else if (currentBlock - p.blockPosted > 25) {       // gave up waiting
                it.remove();
                pendingConfirm.remove(e.getKey());
                toast("A bet didn't confirm within ~25 blocks — check My Bets");
            }
        }
    }

    // ---- auto-settle on mutual agreement + change-your-mind ----
    private final java.util.Set<String> autoCosignTried = new java.util.HashSet<>();
    private final java.util.Set<String> redeclaring = new java.util.HashSet<>();

    private static String outcomeWord(int o) { return o == 2 ? "VOID" : o == 1 ? "TRUE" : "FALSE"; }

    /** Both parties declared the SAME outcome → co-sign + post automatically. No second tap, and it fires
     *  again on the next launch's first scan (state is persisted in the proposals table), so a party never
     *  has to re-declare. Whoever posts first spends the coin; the other's post fails harmlessly and its
     *  live→gone transition is classified into History by {@link #detectExternalSettlements}. */
    private void autoSettleAgreed() {
        if (scanner == null || db == null || settle == null) return;
        for (Bet b : new java.util.ArrayList<>(scanner.matched)) {
            if (b == null || b.nonce == null || !(b.isMine || b.isMyCounter)) continue;
            // Never auto-settle a bet whose answer the user is actively changing — they may be moving
            // AWAY from the outcome that's currently persisted/agreed.
            if (autoCosignTried.contains(b.nonce) || isSettling(b.nonce) || isRedeclaring(b.nonce)) continue;
            Integer mine = db.myDeclaredOutcome(b.nonce);
            OpenlyDb.Proposal in = db.inboundProposal(b.nonce);
            if (mine == null || in == null || in.hex == null || in.hex.isEmpty()) continue;
            if (in.outcome != mine.intValue()) continue;      // disagreement → arbiter / manual path
            autoCosignTried.add(b.nonce);
            settleShown.add(b.nonce);                          // show "confirming", suppress the manual reveal
            final Bet fb = b; final int outcome = in.outcome;
            toast("Both agreed " + outcomeWord(outcome) + " — settling on-chain…");
            settle.accept(b, new SettleEngine.Cb() {
                public void ok() { onSettleAgreed(fb, outcome); }
                public void fail(String m) { settleShown.remove(fb.nonce); }   // coin likely spent by them; classifier records it
            });
        }
    }

    /** Change-your-mind: while a bet is live and unsettled, a party may re-declare a different outcome
     *  (the new signed proposal supersedes the old). */
    public boolean isRedeclaring(String nonce) { return nonce != null && redeclaring.contains(nonce); }
    public void setRedeclaring(String nonce, boolean on) {
        if (nonce == null) return;
        if (on) redeclaring.add(nonce); else redeclaring.remove(nonce);
    }

    /** I just co-signed (Agreed): reveal the payoff immediately (still confirming) and suppress the dup. */
    public void onSettleAgreed(Bet b, int outcome) {
        if (b == null || b.nonce == null) return;
        // Reveal optimistically now (confirming), but persist History only when the coin actually spends —
        // detectSettlements records it on confirm. Avoids a false terminal row if the co-sign never mines.
        watchSettlement(b, outcome);
        settleShown.add(b.nonce);           // I'm revealing now; suppress the confirm-time re-reveal
        new SettleResult(this, b, outcome, false).show();
    }

    /** Persist a terminal result so it appears in HISTORY (rich: path + exact money in/out).
     *  Idempotent (REPLACE on nonce). path: SELF · ARBITER · TIMEOUT. */
    private void recordSettlement(Bet b, int outcome, String path) {
        int mySide = Payouts.mySide(b);
        java.math.BigDecimal in = Payouts.moneyIn(b, outcome, path);
        java.math.BigDecimal out = Payouts.myLock(b);
        java.math.BigDecimal net = Num.sub(in, out);
        int result;
        if (SettleResult.TIMEOUT.equals(path)) result = 4;              // REFUNDED
        else if (outcome == 2) result = 2;                             // VOID
        else result = (outcome == mySide) ? 1 : 0;                     // WON / LOST
        // `amount` is a PRESENTATIONAL signed string (uses U+2212 minus) — HistoryView only concatenates
        // it. The numeric values live in moneyin/moneyout; never BigDecimal-parse this column.
        String amount = (net.signum() >= 0 ? "+" : "−") + Num.plain(net.abs());
        db.addHistory(b.nonce, b.proposition, result, amount, mySide, path,
                Num.plain(in), Num.plain(out), b.tokenid, System.currentTimeMillis());
        clearArbiterState(b.nonce);         // terminal → drop persisted AT-ARBITER + withdrawn flags
    }

    /** Each scan, prune the persisted dispute/withdrawn sets to still-matched bets — so a settled case
     *  doesn't linger in meta once the arbiter has no live case left (finishDisputes stops running then). */
    private void reconcileArbiterSets() {
        if (scanner == null || db == null) return;
        java.util.Set<String> live = new java.util.HashSet<>();
        for (Bet b : scanner.matched) if (b.nonce != null) live.add(b.nonce);
        if (scanner.disputedNonces.retainAll(live)) db.setMeta("disputes", setToCsv(scanner.disputedNonces));
        if (scanner.withdrawnNonces.retainAll(live)) db.setMeta("withdrawn", setToCsv(scanner.withdrawnNonces));
    }

    /** Drop all per-nonce arbiter bookkeeping (persisted) once a bet reaches a terminal state. */
    private void clearArbiterState(String nonce) {
        setArbiterCalled(nonce, false);
        if (scanner != null && nonce != null && scanner.withdrawnNonces.remove(nonce))
            db.setMeta("withdrawn", setToCsv(scanner.withdrawnNonces));
    }

    // Nonces for which I've summoned the arbiter this session → the live card shows "AT ARBITER".
    private final java.util.Set<String> arbiterCalled = new java.util.HashSet<>();
    public void markArbiterCalled(String nonce) { setArbiterCalled(nonce, true); }
    public boolean isArbiterCalled(String nonce) { return nonce != null && arbiterCalled.contains(nonce); }
    /** Set/clear AT-ARBITER for a bet and persist it (survives restart; used by both the caller and the
     *  counterparty on SETTLE_REJECT, and cleared on withdraw). */
    public void setArbiterCalled(String nonce, boolean on) {
        if (nonce == null || nonce.isEmpty()) return;
        if (on) arbiterCalled.add(nonce); else arbiterCalled.remove(nonce);
        if (db != null) db.setMeta("arbcalled", setToCsv(arbiterCalled));
    }

    private static java.util.Set<String> csvToSet(String csv) {
        java.util.Set<String> s = new java.util.HashSet<>();
        if (csv != null) for (String p : csv.split(",")) { String t = p.trim(); if (!t.isEmpty()) s.add(t); }
        return s;
    }
    private static String setToCsv(java.util.Set<String> s) {
        StringBuilder b = new StringBuilder();
        for (String x : s) { if (b.length() > 0) b.append(','); b.append(x); }
        return b.toString();
    }

    // My committed envelope pick per bet (0/1/2) → survives the per-block card rebuild so a torn-open
    // reveal + its Settle/Call action isn't reset every ~50s.
    private final java.util.Map<String, Integer> revealedPick = new java.util.HashMap<>();
    public void setRevealedPick(String nonce, int pick) { if (nonce != null && !nonce.isEmpty()) revealedPick.put(nonce, pick); }
    public Integer revealedPick(String nonce) { return nonce == null ? null : revealedPick.get(nonce); }

    /** True once I've agreed/declared this bet's settlement (revealed once) — so the UI shows "confirming"
     *  rather than re-offering the Settle button while the co-sign is still mining. */
    public boolean isSettling(String nonce) { return nonce != null && settleShown.contains(nonce); }

    /** Arbiter's own reveal after ruling — shows the payout + their 10% fee (confirming on-chain). */
    public void arbiterResolvedReveal(Bet b, int outcome) {
        String side = outcome == 1 ? "TRUE" : "FALSE";
        java.math.BigDecimal fee = Payouts.fee(b);
        java.math.BigDecimal winnerGets = Num.sub(b.amount, fee);
        toast("Ruled " + side + " · your fee +" + Num.plain(fee) + " M incoming");
        new android.app.AlertDialog.Builder(this)
                .setTitle("You ruled " + side)
                .setMessage("Winner paid " + Num.plain(winnerGets) + " M\n"
                        + "Your arbiter fee +" + Num.plain(fee) + " M (incoming)\n\nConfirming on-chain…")
                .setPositiveButton("Done", null).show();
    }

    /** Record a cancelled/superseded open bet in HISTORY (stake returned, no win/loss). */
    public void recordCancelled(Bet b) {
        if (b == null || b.nonce == null) return;
        java.math.BigDecimal lock = Payouts.myLock(b);
        db.addHistory(b.nonce, b.proposition, 3 /*CANCELLED*/, "0", Payouts.mySide(b),
                "CANCEL", Num.plain(lock), Num.plain(lock), b.tokenid, System.currentTimeMillis());
    }

    /** Send a per-bet chat message (small, over the shared channel), stored locally as sent. */
    public void sendChat(Bet b, String text) {
        if (comms == null || !comms.ready() || b == null || text == null || text.trim().isEmpty()) return;
        String their = b.isMine ? b.countercommsid : b.ownercommsid;
        if (their == null || their.isEmpty()) { toast("no chat channel for this bet"); return; }
        long now = System.currentTimeMillis();
        OpenlyMessage m = new OpenlyMessage();
        m.type = OpenlyMessage.CHAT; m.ref = b.nonce; m.to = their; m.date = now;
        m.statement = text.trim();
        m.randomid = "0x" + Long.toHexString(now) + "z" + Integer.toHexString(text.hashCode() & 0xffff);
        m.from = comms.myId();
        m.raw = new String(m.toWire(), java.nio.charset.StandardCharsets.UTF_8);   // so chatFor can read the text
        db.insertMessageIfNew(m, false);      // show my own message immediately
        comms.send(their, m, noopSend());
        refreshCurrent();
    }

    /**
     * Arbiter → BOTH parties, IDENTICAL. One message, broadcast to the owner and the counter (each its
     * own seal + randomid), plus a single local copy for the arbiter's own view. There is deliberately
     * no per-party path — the content is the same by construction. Requires the bet to have pinned this
     * arbiter's comms id (port 11) so the parties can authenticate the sender.
     */
    public void sendArbiterChat(Bet b, String text) {
        if (comms == null || !comms.ready() || b == null || text == null || text.trim().isEmpty()) return;
        if (b.arbcommsid == null || b.arbcommsid.isEmpty() || "0".equals(b.arbcommsid)) {
            toast("This bet was posted without an arbiter comms id — chat unavailable"); return;
        }
        // Fairness: never message only one side. If either party's comms id is missing, send to NEITHER.
        boolean haveO = b.ownercommsid != null && !b.ownercommsid.isEmpty();
        boolean haveC = b.countercommsid != null && !b.countercommsid.isEmpty();
        if (!haveO || !haveC) { toast("Can't reach both parties yet — message not sent"); return; }

        String msg = text.trim();
        long now = System.currentTimeMillis();
        db.insertMessageIfNew(arbChatMsg(b, msg, comms.myId(), "L", now), false);   // local copy for my view
        comms.send(b.ownercommsid,   arbChatMsg(b, msg, b.ownercommsid,   "O", now), arbSendCb("a party"));
        comms.send(b.countercommsid, arbChatMsg(b, msg, b.countercommsid, "C", now), arbSendCb("a party"));
        toast("Sending to both parties…");
        refreshCurrent();
    }

    private com.eurobuddha.comms.CommsTransport.SendCb arbSendCb(final String who) {
        return new com.eurobuddha.comms.CommsTransport.SendCb() {
            public void onSent(String t) {}
            public void onFailed(String e) { ui.post(() -> toast("Arbiter message to " + who + " failed — not delivered to both")); }
        };
    }

    /** A CHAT message with an explicit recipient + a per-recipient distinct randomid (the DB primary key
     *  and the route filter both key on it, so each side stores its own copy of the identical text). */
    private OpenlyMessage arbChatMsg(Bet b, String msg, String to, String tag, long now) {
        OpenlyMessage m = new OpenlyMessage();
        m.type = OpenlyMessage.CHAT; m.ref = b.nonce; m.to = to; m.date = now; m.statement = msg;
        m.from = comms.myId();
        m.randomid = "0x" + Long.toHexString(now) + tag + Integer.toHexString(msg.hashCode() & 0xffff);
        m.raw = new String(m.toWire(), java.nio.charset.StandardCharsets.UTF_8);
        return m;
    }

    private com.eurobuddha.comms.CommsTransport.SendCb noopSend() {
        return new com.eurobuddha.comms.CommsTransport.SendCb() {
            public void onSent(String t) {}
            public void onFailed(String e) {}
        };
    }

    /** A watched matched bet has left the board (coin spent) → it settled: reveal the payoff once. */
    private void detectSettlements() {
        if (settleWatch.isEmpty() || scanner == null) return;
        java.util.Set<String> live = new java.util.HashSet<>();
        for (Bet b : scanner.matched) if (b.nonce != null) live.add(b.nonce);
        java.util.Iterator<String> it = settleWatch.keySet().iterator();
        while (it.hasNext()) {
            String nonce = it.next();
            if (live.contains(nonce)) continue;                 // still matched — not settled yet
            Bet b = settleWatch.get(nonce);
            Integer o = settleOutcome.get(nonce);
            if (b != null && o != null) {
                recordSettlement(b, o, SettleResult.SELF);          // idempotent (REPLACE) — records on confirm
                if (settleShown.add(nonce)) new SettleResult(this, b, o, true).show();   // reveal once
            }
            it.remove();
            settleOutcome.remove(nonce);
        }
    }

    // ---- external settlement detection (a matched bet I'm party to that left the board) ----
    // I never guess a win/loss. I build candidate outcomes only from what I RELIABLY know — a persisted
    // self-settle proposal outcome (survives restart), an arbiter I summoned, or the coin having aged
    // past its timeout — then CONFIRM against the specific expected payout at my address, requiring the
    // coin to be created at/after the block I last saw the bet live (rejects stale/unrelated coins).
    // On a transient query error or no match yet I RETRY across scans rather than concluding anything;
    // only an arbiter-loss (provable ABSENCE of the win payout, after retries) or an exhausted-retry
    // "settled — outcome unknown" is recorded without a positive amount. History is the persistent dedup.
    private final java.util.Map<String, Bet> myMatchedSeen = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> lastSeenBlock = new java.util.HashMap<>();
    private final java.util.Map<String, Bet> classifyBets = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> classifyRetry = new java.util.HashMap<>();
    private final java.util.Set<String> classifyInFlight = new java.util.HashSet<>();
    private static final int CLASSIFY_MAX_RETRY = 5;

    private void detectExternalSettlements() {
        if (scanner == null || db == null) return;
        java.util.Set<String> liveMine = new java.util.HashSet<>();
        for (Bet b : scanner.matched) {
            if ((b.isMine || b.isMyCounter) && b.nonce != null) {
                liveMine.add(b.nonce);
                myMatchedSeen.put(b.nonce, b);
                lastSeenBlock.put(b.nonce, currentBlock);
            }
        }
        java.util.Iterator<java.util.Map.Entry<String, Bet>> it = myMatchedSeen.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, Bet> e = it.next();
            if (liveMine.contains(e.getKey())) continue;    // still live
            String nonce = e.getKey();
            it.remove();                                    // left the board = terminal
            if (settleShown.contains(nonce) || db.hasHistory(nonce)) { lastSeenBlock.remove(nonce); continue; }
            classifyBets.put(nonce, e.getValue());
            classifyRetry.put(nonce, 0);
        }
        for (String nonce : new java.util.ArrayList<>(classifyBets.keySet())) classifyOne(nonce);
    }

    private static final class Cand {
        final String path; final int outcome; final java.math.BigDecimal expect;
        Cand(String p, int o, java.math.BigDecimal e) { path = p; outcome = o; expect = e; }
    }

    private void classifyOne(final String nonce) {
        final Bet b = classifyBets.get(nonce);
        if (b == null) { clearClassify(nonce); return; }
        if (classifyInFlight.contains(nonce) || node == null) return;

        final java.util.List<Cand> cands = new java.util.ArrayList<>();
        Integer selfOut = db.proposalOutcome(nonce);
        if (selfOut != null)
            cands.add(new Cand(SettleResult.SELF, selfOut, Payouts.moneyIn(b, selfOut, SettleResult.SELF)));
        if (isArbiterCalled(nonce)) {
            int mySide = Payouts.mySide(b);
            cands.add(new Cand(SettleResult.ARBITER, mySide, Payouts.moneyIn(b, mySide, SettleResult.ARBITER))); // win = pot−fee
            cands.add(new Cand(SettleResult.ARBITER, mySide == 1 ? 0 : 1, java.math.BigDecimal.ZERO));           // loss = nothing
        }
        if (b.timeout > 0 && b.ageBlocks > b.timeout)
            cands.add(new Cand(SettleResult.TIMEOUT, 2, Payouts.myLock(b)));

        final String myAddr = b.isMine ? b.owneraddr : b.counteraddr;
        final int seenBlock = lastSeenBlock.containsKey(nonce) ? lastSeenBlock.get(nonce) : 0;
        if (cands.isEmpty() || myAddr == null || myAddr.isEmpty()) { retryOrNeutral(nonce, b, cands); return; }

        classifyInFlight.add(nonce);
        node.cmd("coins address:" + myAddr + " order:desc depth:60", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                classifyInFlight.remove(nonce);
                final java.util.Set<String> amounts = new java.util.HashSet<>();
                JSONArray arr = r.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject cj = arr.optJSONObject(i);
                    if (cj == null || cj.optBoolean("spent", false)) continue;
                    int created;
                    try { created = Integer.parseInt(cj.optString("created", String.valueOf(seenBlock))); }
                    catch (Exception ex) { created = seenBlock; }        // unparseable → in-window, never drop the real coin
                    if (created < seenBlock) continue;                   // reject stale/unrelated coins
                    // Token coins report value in `tokenamount` (raw `amount` ~1e-37); native uses `amount`.
                    // Normalize through Num.plain so it matches the token-scale expected candidate strings.
                    String ta = cj.optString("tokenamount", "");
                    String raw = !ta.isEmpty() ? ta : cj.optString("amount", "");
                    try { amounts.add(Num.plain(Num.of(raw))); } catch (Exception ex) { if (!raw.isEmpty()) amounts.add(raw); }
                }
                ui.post(() -> {
                    for (Cand c : cands) {
                        if (c.expect.signum() <= 0) continue;            // absence-based (arbiter loss) handled in retryOrNeutral
                        if (amounts.contains(Num.plain(c.expect))) { finishClassify(nonce, b, c.outcome, c.path); return; }
                    }
                    retryOrNeutral(nonce, b, cands);
                });
            }
            public void onError(String e) { classifyInFlight.remove(nonce); /* transient → retry next scan, conclude nothing */ }
        });
    }

    private void retryOrNeutral(String nonce, Bet b, java.util.List<Cand> cands) {
        int r = classifyRetry.containsKey(nonce) ? classifyRetry.get(nonce) : 0;
        if (r < CLASSIFY_MAX_RETRY) { classifyRetry.put(nonce, r + 1); return; }
        for (Cand c : cands) if (c.expect.signum() == 0) { finishClassify(nonce, b, c.outcome, c.path); return; } // arbiter loss
        recordSettledUnknown(b);
        clearClassify(nonce);
    }

    private void finishClassify(String nonce, Bet b, int outcome, String path) {
        if (!db.hasHistory(nonce) && settleShown.add(nonce)) {
            recordSettlement(b, outcome, path);
            new SettleResult(this, b, outcome, true, path).show();
        }
        clearClassify(nonce);
        refreshCurrent();
    }

    /** Couldn't determine the outcome from any reliable source — record neutrally, never a false win/loss. */
    private void recordSettledUnknown(Bet b) {
        if (b == null || b.nonce == null || db.hasHistory(b.nonce)) return;
        settleShown.add(b.nonce);
        java.math.BigDecimal lock = Payouts.myLock(b);
        db.addHistory(b.nonce, b.proposition, 5 /*SETTLED, outcome unknown*/, "?", Payouts.mySide(b),
                "SETTLED", Num.plain(lock), Num.plain(lock), b.tokenid, System.currentTimeMillis());
        clearArbiterState(b.nonce);   // terminal → drop persisted arbiter flags even on the unknown path
        toast("A bet settled — check History");
    }

    private void clearClassify(String nonce) {
        classifyBets.remove(nonce); classifyRetry.remove(nonce);
        lastSeenBlock.remove(nonce); classifyInFlight.remove(nonce);
    }

    /**
     * For every matched bet I'm party to that has no received settlement proposal yet, read its per-bet
     * settlement mailbox ({@link OpenlyContract#settleAddr}). This is the reliable receive path: only
     * that bet's 1-2 proposal coins live there, so it finds the counterparty's proposal regardless of
     * how many blocks ago it landed — unlike the bounded shared-channel scan. Throttled.
     */
    private void scanPendingSettlements() {
        if (comms == null || !comms.ready() || scanner == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSettleScan < 8000) return;
        lastSettleScan = now;
        for (Bet b : scanner.matched) {
            if (!(b.isMine || b.isMyCounter)) continue;
            if (db.inboundProposal(b.nonce) != null) continue;   // already received + verified
            comms.scanSettleAddress(OpenlyContract.settleAddr(b.nonce));
        }
    }

    /**
     * "Taken → just live": once I'm party to a LIVE (matched) bet on a proposition, my OWN leftover
     * OPEN bets on that same proposition are cancelled automatically and their locked funds returned.
     * Only my own bets (I can sign the owner-cancel), once per coin, hidden from the board immediately.
     */
    private void autoCancelSuperseded() {
        if (scanner.matched.isEmpty() || scanner.open.isEmpty()) return;
        java.util.Set<String> liveProps = new java.util.HashSet<>();
        for (Bet b : scanner.matched)
            if ((b.isMine || b.isMyCounter) && b.proposition != null && !b.proposition.isEmpty())
                liveProps.add(b.proposition);
        if (liveProps.isEmpty()) return;
        for (final Bet o : scanner.open) {
            if (!o.isMine || o.proposition == null || !liveProps.contains(o.proposition)) continue;
            if (!autoCancelled.add(o.coinid)) continue;              // once per coin
            scanner.markFilled(o.nonce);                             // drop it from the board now too
            toast("Cancelled your other open bet on this proposition · " + Num.plain(o.ownerBet()) + " M returning");
            txn.cancel(o, new OpenlyTxn.Done() {
                public void ok() { recordCancelled(o); refreshCurrent(); }
                public void fail(String m) { autoCancelled.remove(o.coinid); toast("Auto-cancel failed: " + m); }
            });
        }
    }

    private void renderAll() {
        for (int i = 0; i < pagerAdapter.getCount(); i++) pagerAdapter.viewAt(i).refresh();
    }

    /** Recolor the XML header chrome from Design tokens so the theme toggle restyles everything. */
    private void styleChrome() {
        View root = findViewById(R.id.main);
        if (root != null) root.setBackgroundColor(Design.BG());
        TextView brand = findViewById(R.id.brandTitle);
        TextView sub = findViewById(R.id.brandSub);
        TextView tgl = findViewById(R.id.designToggle);
        if (brand != null) { brand.setTextColor(Design.ACCENT()); brand.setText("Openly"); }
        if (sub != null) { sub.setTextColor(Design.DIM()); sub.setText("PROPOSE ANYTHING"); }
        if (tgl != null) tgl.setTextColor(Design.ACCENT());
        if (blockNo != null) blockNo.setTextColor(Design.DIM());
        // The tab bar, pager, status strip and page backgrounds are all painted in Java (the XML shell
        // hardcodes NOIR @color/*), so the theme toggle must repaint them too — otherwise only the header
        // + cards change and the rest stays dark.
        if (tabs != null) {
            tabs.setBackgroundColor(Design.BG());
            tabs.setTabTextColors(Design.DIM(), Design.ACCENT());
            tabs.setSelectedTabIndicatorColor(Design.ACCENT());
        }
        if (pager != null) pager.setBackgroundColor(Design.BG());
        if (statusStrip != null) {
            statusStrip.setBackgroundColor(Design.SURFACE2());
            statusStrip.setTextColor(Design.DIM());
        }
        if (pagerAdapter != null) {
            for (int i = 0; i < pagerAdapter.getCount(); i++) {
                View pv = pagerAdapter.viewAt(i).getRoot();
                if (pv != null) pv.setBackgroundColor(Design.BG());
            }
        }
        updateHeaderStat();
    }

    private String lastShownBal = "—";
    private int lastShownBlock = 0;
    private void updateHeaderStat() {
        if (blockNo == null) return;
        String bal = "—".equals(balance) ? "—" : safeBal(balance);
        String head = bal + " M";
        try { if (usdtBalance != null && new java.math.BigDecimal(usdtBalance).signum() > 0)
                  head += "  ·  " + safeBal(usdtBalance) + " mxU"; } catch (Exception ignored) {}
        blockNo.setText(head + "  ·  #" + currentBlock);
        boolean balChanged = !bal.equals(lastShownBal) && !"—".equals(lastShownBal) && !"—".equals(bal);
        boolean blockChanged = lastShownBlock != 0 && currentBlock != lastShownBlock;
        lastShownBal = bal;
        lastShownBlock = currentBlock;
        // Money moving gets the accent pulse; otherwise a quiet block-tick heartbeat (never both at once).
        if (balChanged) Design.pulse(blockNo, Design.ACCENT());
        else if (blockChanged) Design.pulse(blockNo, Design.DIM());
    }

    private static String safeBal(String b) {
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(b);
            return d.setScale(2, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        } catch (Exception e) { return b; }
    }

    /** Switch tabs programmatically (e.g. jump to MY BETS after posting a proposition). */
    public void goToTab(int i) { if (pager != null) pager.setCurrentItem(i, true); }

    public NodeApi node() { return node; }
    public int block() { return currentBlock; }

    // ---------------------------------------------------------------- contract
    private void ensureContract() {
        // runscript parse verify → newscript trackall:false → coinnotify the shared board.
        node.cmd("runscript script:\"" + OpenlyContract.SCRIPT + "\"", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                node.cmd("newscript trackall:false clean:true script:\"" + OpenlyContract.SCRIPT + "\"",
                    new NodeApi.Cb() {
                        public void onResult(JSONObject r2) {
                            node.cmd("coinnotify action:add address:" + OpenlyContract.ADDR, NodeApi.Cb.NOOP);
                            node.cmd("coinnotify action:add address:" + OpenlyContract.MAIL_ADDR, NodeApi.Cb.NOOP);
                        }
                        public void onError(String e) {}
                    });
            }
            public void onError(String e) {}
        });
    }

    // ---------------------------------------------------------------- reload
    private final Runnable reloadTask = this::reload;

    public void requestReload() {
        ui.removeCallbacks(reloadTask);
        ui.postDelayed(reloadTask, 400);
    }

    private void reload() {
        node.cmd("block", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                try {
                    currentBlock = r.getJSONObject("response").getInt("block");
                    updateHeaderStat();
                } catch (Exception ignored) {}
                if (scanner != null) {
                    scanner.scan(currentBlock);
                    // Only an arbiter of a live bet needs the dispute scan — and its query is bounded.
                    // (Running it for everyone every block issued an unbounded coins query that could
                    // exceed the node's 256 KB IPC limit → TransactionTooLargeException → app drop.)
                    if (identity != null && identity.ready() && amArbiterOfLiveBet()) {
                        java.util.Set<String> arbAddrs = new java.util.HashSet<>();
                        if (identity.hexaddr != null && !identity.hexaddr.isEmpty()) arbAddrs.add(identity.hexaddr);
                        // Also scan each arbitrated bet's own arbaddr — a bet may pin an older arbiter key
                        // (still node-owned) that differs from the current identity address.
                        for (Bet b : scanner.matched)
                            if (b.isMyArb && b.arbaddr != null && !b.arbaddr.isEmpty()) arbAddrs.add(b.arbaddr);
                        scanner.scanDisputes(arbAddrs);
                    }
                }
                if (comms != null && comms.ready()) comms.scan(currentBlock);
                // NOTE: no periodic `coins sendable:true` here — on a large wallet that reply blows the
                // 256 KB IPC limit and destabilises the app. Spent funding coins self-expire from reuse.
                // Only the visible page repaints on a block; the others refresh when selected
                // (onShown) — rebuilding all four view trees every block was blocking the UI thread.
                pagerAdapter.viewAt(pager.getCurrentItem()).onNewBlock();
            }
            public void onError(String e) {}
        });
        node.cmd("balance", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                try {
                    JSONArray arr = r.getJSONArray("response");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject t = arr.getJSONObject(i);
                        String tid = t.optString("tokenid");
                        if ("0x00".equals(tid)) balance = t.optString("sendable", "0");
                        else if (Util.MXUSDT_TOKENID.equalsIgnoreCase(tid)) usdtBalance = t.optString("sendable", "0");
                    }
                } catch (Exception ignored) {}
                updateHeaderStat();
                BaseView v = pagerAdapter.viewAt(pager.getCurrentItem());
                v.refresh();
            }
            public void onError(String e) {}
        });
    }

    // ---------------------------------------------------------------- pairing
    private void setPaired(boolean enabled) {
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) requestReload();
    }

    private void openNodeApp() {
        Intent i = getPackageManager().getLaunchIntentForPackage(NODE_PKG);
        if (i != null) startActivity(i);
    }

    // ---------------------------------------------------------------- NOTIFY
    private void registerNotifyReceiver() {
        notifyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!MinimaAPI.checkMinimaID(c, intent)) return;
                requestReload();
            }
        };
        IntentFilter f = new IntentFilter(NODE_PKG + ".NOTIFY");
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(this, notifyReceiver, f, ContextCompat.RECEIVER_EXPORTED);
        } else {
            registerReceiver(notifyReceiver, f);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        FOREGROUND = true;
        requestReload();
        // Recover any settlement proposals that arrived while backgrounded (deep comms rescan).
        if (comms != null && comms.ready()) comms.deepRescan(currentBlock);
    }

    @Override protected void onPause() {
        super.onPause();
        FOREGROUND = false;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (notifyReceiver != null) unregisterReceiver(notifyReceiver);
        if (node != null) node.onDestroy();
        Sfx.release();
    }
}
