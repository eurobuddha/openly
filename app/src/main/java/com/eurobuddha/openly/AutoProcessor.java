package com.eurobuddha.openly;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The background brain. Given current bets + my keys, auto-advances ONLY:
 *   1. Refresh my open orders + matched bets I'm party to at age ≥ REFRESH_AGE (keep them inside the
 *      1024-block visibility horizon) — UNLESS the bet is flagged wanttimeout (refresh resets the
 *      timeout clock).
 *   2. Claim the timeout refund on a flagged bet once its coin age > its timeout.
 *
 * Never auto-cosigns, never auto-arbitrates, never auto-proposes — those are human decisions.
 * A per-coin cooldown (REPOST_AFTER blocks) stops competing re-posts. istransaction:false is async
 * mining, not failure; outcomes reconcile from the next chain scan.
 */
public class AutoProcessor {

    public static final int REFRESH_AGE = 600;     // blocks (~half the 1024 horizon)
    private static final int REPOST_AFTER = 4;      // per-coin cooldown in blocks
    private static final String PREFS = "openly_wanttimeout";

    private final BetScanner scanner;
    private final OpenlyTxn txn;
    private final SharedPreferences wantTimeout;
    private final Map<String, Integer> postedAt = new HashMap<>();
    private boolean busy = false;

    public AutoProcessor(Context ctx, BetScanner scanner, OpenlyTxn txn) {
        this.scanner = scanner;
        this.txn = txn;
        this.wantTimeout = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isFlagged(String nonce) { return wantTimeout.getBoolean(nonce, false); }
    public void flagTimeout(String nonce, boolean on) { wantTimeout.edit().putBoolean(nonce, on).apply(); }

    private int busySince = 0;   // tip block when busy was set — watchdog against a hung node callback

    /** Called after each board scan. */
    public void process(int tipBlock) {
        // Watchdog: if an in-flight op hasn't completed within a few blocks (its Done never fired
        // because the node hung), release the latch so processing resumes.
        if (busy && tipBlock - busySince >= REPOST_AFTER + 2) busy = false;
        if (busy) return;
        // prune cooldown to coins we still see
        Set<String> live = new java.util.HashSet<>();
        for (Bet b : scanner.open) live.add(b.coinid);
        for (Bet b : scanner.matched) live.add(b.coinid);
        postedAt.keySet().retainAll(live);

        for (Bet b : scanner.open) {
            if (!b.isMine) continue;
            if (cooling(b.coinid, tipBlock)) continue;
            if (b.ageBlocks >= REFRESH_AGE) { doRefresh(b, b.ownerpk, tipBlock); return; }
        }
        for (Bet b : scanner.matched) {
            boolean mine = b.isMine || b.isMyCounter;
            if (!mine) continue;
            if (cooling(b.coinid, tipBlock)) continue;
            boolean flagged = isFlagged(b.nonce);
            if (flagged && b.ageBlocks > b.timeout) {
                doTimeout(b, tipBlock);
                return;
            }
            if (!flagged && b.ageBlocks >= REFRESH_AGE) {
                doRefresh(b, b.isMine ? b.ownerpk : b.counterpk, tipBlock);
                return;
            }
        }
    }

    private boolean cooling(String coinid, int tip) {
        Integer at = postedAt.get(coinid);
        return at != null && tip - at < REPOST_AFTER;
    }

    private void doRefresh(Bet b, String pk, int tip) {
        busy = true; busySince = tip;
        postedAt.put(b.coinid, tip);
        txn.refresh(b, pk, new OpenlyTxn.Done() {
            public void ok() { busy = false; }
            public void fail(String m) { busy = false; }
        });
    }

    private void doTimeout(Bet b, int tip) {
        busy = true; busySince = tip;
        postedAt.put(b.coinid, tip);
        txn.timeout(b, new OpenlyTxn.Done() {
            public void ok() { busy = false; }
            public void fail(String m) { busy = false; }
        });
    }
}
