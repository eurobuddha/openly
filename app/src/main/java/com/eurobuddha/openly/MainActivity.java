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
    private MainPager pagerAdapter;
    private TextView blockNo;
    private View pairingBanner;

    private BroadcastReceiver notifyReceiver;
    private boolean paired = false;
    private int currentBlock = 0;

    // ---- Openly state (read by views) ----
    public String contractAddr = OpenlyContract.ADDR;
    public String balance = "—";
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
        TabLayout tabs = findViewById(R.id.tabs);

        BaseView[] views = new BaseView[]{
                new MarketsView(this),
                new MyBetsView(this),
                new CreateView(this),
                new ArbiterView(this)
        };
        String[] titles = {"MARKETS", "MY BETS", "POST", "ARBITER"};
        pagerAdapter = new MainPager(views, titles);
        pager.setAdapter(pagerAdapter);
        pager.setOffscreenPageLimit(4);
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

    private void onScanned() {
        if (auto != null) auto.process(currentBlock);
        BaseView v = pagerAdapter.viewAt(pager.getCurrentItem());
        v.refresh();
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
        updateHeaderStat();
    }

    private void updateHeaderStat() {
        if (blockNo == null) return;
        String bal = "—".equals(balance) ? "—" : safeBal(balance);
        blockNo.setText(bal + " M  ·  #" + currentBlock);
    }

    private static String safeBal(String b) {
        try {
            java.math.BigDecimal d = new java.math.BigDecimal(b);
            return d.setScale(2, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        } catch (Exception e) { return b; }
    }

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
                        scanner.scanDisputes(identity.hexaddr);
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
                        if ("0x00".equals(t.optString("tokenid"))) {
                            balance = t.optString("sendable", "0");
                            break;
                        }
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
    }
}
