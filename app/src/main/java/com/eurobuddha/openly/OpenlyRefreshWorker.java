package com.eurobuddha.openly;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.eurobuddha.comms.NodeApi;

/**
 * Periodic background catch-up (WorkManager). Runs ONE bounded {@code block → scan → auto.process}
 * cycle and exits — the crash-safe replacement for the perpetual {@code dataSync} foreground service,
 * which Android 14/15 caps at ~6h/day and then crash-loops (ForegroundServiceDidNotStop /
 * StartNotAllowed). WorkManager is not subject to that FGS time limit.
 *
 * Reuses the exact same pipeline the Activity/Service run — no new bet logic. Defers entirely to the
 * Activity when it is in the foreground (single-driver invariant, same as {@code OpenlyService.tick()}).
 * doWork() runs on a WorkManager background thread and blocks on a latch while the node's IPC callbacks
 * (which land on the main thread) complete the one scan cycle.
 */
public class OpenlyRefreshWorker extends Worker {

    // volatile: written on the WorkManager worker thread in doWork(), read on the main thread inside
    // the node.cmd callbacks (tick/scan listener).
    private volatile NodeApi node;
    private volatile BetScanner scanner;
    private volatile AutoProcessor auto;
    private volatile int block = 0;
    private final CountDownLatch done = new CountDownLatch(1);

    public OpenlyRefreshWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    @NonNull @Override public Result doWork() {
        if (MainActivity.FOREGROUND) return Result.success();   // the Activity is driving; stay idle
        Context ctx = getApplicationContext();
        node = new NodeApi(ctx, enabled -> {});
        try {
            Identity identity = new Identity(ctx, node);
            OpenlyTxn txn = new OpenlyTxn(node, identity);
            // listener fires after each scan (on the main thread): advance the pipeline, then release.
            scanner = new BetScanner(node, () -> { if (auto != null) auto.process(block); done.countDown(); });
            auto = new AutoProcessor(ctx, scanner, txn);
            // same registration the Activity/Service do, so a fresh process still sees the board.
            node.cmd("newscript trackall:false clean:true script:\"" + OpenlyContract.SCRIPT + "\"", NodeApi.Cb.NOOP);
            node.cmd("coinnotify action:add address:" + OpenlyContract.ADDR, NodeApi.Cb.NOOP);
            identity.ensure(() -> scanner.loadKeys(this::tick));
            done.await(60, TimeUnit.SECONDS);   // safety cap; the cycle is a handful of IPC round-trips
        } catch (Throwable t) {
            android.util.Log.w("OpenlyRefreshWorker", "cycle failed (contained)", t);
        } finally {
            if (node != null) node.onDestroy();
        }
        return Result.success();   // never retry-storm; the next period runs on schedule anyway
    }

    private void tick() {
        node.cmd("block", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                try { block = r.getJSONObject("response").getInt("block"); } catch (Exception ignored) {}
                scanner.scan(block);   // → listener: auto.process(block) + done.countDown()
            }
            public void onError(String e) { done.countDown(); }
        });
    }
}
