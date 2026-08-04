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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Design.load(this);
        setContentView(R.layout.activity_main);

        View mainRoot = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), bottom);
            return insets;
        });

        blockNo = findViewById(R.id.blockNo);
        pairingBanner = findViewById(R.id.pairingBanner);
        findViewById(R.id.openNodeBtn).setOnClickListener(x -> openNodeApp());

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
        registerNotifyReceiver();
        ensureContract();
        identity.ensure(() -> {
            comms.setup(null);   // derive comms identity → pins identity.commsId
            scanner.loadKeys(this::requestReload);
        });
    }

    /**
     * Authenticated inbound message (seal + signature valid). Phase 5: verify the sender is a party
     * to the referenced bet (against on-chain pinned commsid) and store it. Phase 6 dispatches
     * SETTLE_* to the settle engine.
     */
    private boolean onCommsMessage(OpenlyMessage m, JSONObject coin) {
        Bet bet = betByNonce(m.ref);
        if (bet == null) return false;                        // unknown bet — drop
        boolean senderIsParty = m.from.equals(bet.ownercommsid)
                || m.from.equals(bet.countercommsid)
                || m.from.equals(bet.arbcommsid);
        if (!senderIsParty) return false;                     // spoofed sender — drop
        boolean fresh = db.insertMessageIfNew(m, true);
        if (fresh) {
            if (OpenlyMessage.SETTLE_PROPOSE.equals(m.type)) settle.onInboundPropose(m);
            ui.post(this::refreshCurrent);
        }
        return fresh;
    }

    public Bet betByNonce(String nonce) {
        if (nonce == null || nonce.isEmpty()) return null;
        for (Bet b : scanner.matched) if (nonce.equalsIgnoreCase(b.nonce)) return b;
        for (Bet b : scanner.open) if (nonce.equalsIgnoreCase(b.nonce)) return b;
        return null;
    }

    /** Toast-ish status line via the block header (kept minimal for Phase 3). */
    public void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Refresh the currently visible page. */
    public void refreshCurrent() { onScanned(); }

    /** Light detent haptic for the stake slider. */
    public void performHapticFeedback() {
        if (pager != null) pager.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    private void onScanned() {
        BaseView v = pagerAdapter.viewAt(pager.getCurrentItem());
        v.refresh();
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
                    blockNo.setText("#" + currentBlock);
                } catch (Exception ignored) {}
                if (scanner != null) scanner.scan(currentBlock);
                if (comms != null && comms.ready()) comms.scan(currentBlock);
                for (int i = 0; i < pagerAdapter.getCount(); i++) pagerAdapter.viewAt(i).onNewBlock();
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
