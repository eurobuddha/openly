package com.eurobuddha.openly;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import com.eurobuddha.comms.NodeApi;

/**
 * Foreground keep-alive so matched bets get refreshed (and flagged bets time out) even when the app
 * is backgrounded and the OEM would otherwise freeze it. Runs the same block-driven pipeline as the
 * Activity, but defers entirely while {@link MainActivity#FOREGROUND} is true (single driver, casino
 * handoff pattern). WorkManager (registered by the Activity) relaunches this if the OS kills it.
 */
public class OpenlyService extends Service {

    private static final String CH = "openly_bg";
    private NodeApi node;
    private Identity identity;
    private OpenlyTxn txn;
    private BetScanner scanner;
    private AutoProcessor auto;
    private BroadcastReceiver notify;
    private int block = 0;

    @Override public void onCreate() {
        super.onCreate();
        try {
            ServiceCompat.startForeground(this, 1, buildNotification(),
                    Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
        } catch (Throwable t) {
            // Android 14/15 refuse startForeground() when it is (re)started from the background, or once
            // the dataSync daily runtime budget is spent ("Time limit already exhausted"). Left unguarded
            // this threw out of onCreate → "Openly keeps stopping", and START_STICKY retried it into an
            // hourly crash loop. Never crash: stop cleanly. Background catch-up is done by
            // OpenlyRefreshWorker (periodic WorkManager), which is not subject to the FGS time limit.
            android.util.Log.w("OpenlyService", "startForeground refused; stopping service", t);
            stopSelf();
            return;
        }
        node = new NodeApi(this, enabled -> {});
        identity = new Identity(this, node);
        txn = new OpenlyTxn(node, identity);
        scanner = new BetScanner(node, () -> auto.process(block));
        auto = new AutoProcessor(this, scanner, txn);
        node.cmd("runscript script:\"" + OpenlyContract.SCRIPT + "\"", NodeApi.Cb.NOOP);
        node.cmd("newscript trackall:false clean:true script:\"" + OpenlyContract.SCRIPT + "\"", NodeApi.Cb.NOOP);
        node.cmd("coinnotify action:add address:" + OpenlyContract.ADDR, NodeApi.Cb.NOOP);
        identity.ensure(() -> scanner.loadKeys(this::tick));
        registerNotify();
    }

    private void registerNotify() {
        notify = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { tick(); }
        };
        IntentFilter f = new IntentFilter(MainActivity.NODE_PKG + ".NOTIFY");
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.registerReceiver(this, notify, f, ContextCompat.RECEIVER_EXPORTED);
        } else {
            registerReceiver(notify, f);
        }
    }

    private void tick() {
        if (MainActivity.FOREGROUND) return;   // the Activity is driving; stay idle
        node.cmd("block", new NodeApi.Cb() {
            public void onResult(JSONObject r) {
                try { block = r.getJSONObject("response").getInt("block"); } catch (Exception ignored) {}
                scanner.scan(block);   // → auto.process on scan callback
            }
            public void onError(String e) {}
        });
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Openly background",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, CH)
                .setContentTitle("Openly")
                .setContentText("Keeping your bets alive")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setOngoing(true)
                .build();
    }

    // NOT_STICKY: never let the OS resurrect this from the background (that recreate hits the
    // startForeground restriction → crash). WorkManager owns durable background catch-up now.
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }

    /** Android 14 (API 34): the dataSync FGS runtime cap was reached — stop cleanly BEFORE the system
     *  throws ForegroundServiceDidNotStopInTimeException and crashes us. */
    @Override public void onTimeout(int startId) { stopCleanly(); }

    /** Android 15 (API 35): same, with the fgsType parameter. */
    @Override public void onTimeout(int startId, int fgsType) { stopCleanly(); }

    private void stopCleanly() {
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); } catch (Throwable ignored) {}
        stopSelf();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        super.onDestroy();
        if (notify != null) try { unregisterReceiver(notify); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
    }

    /** Convenience: start as a foreground data-sync service. */
    public static void start(Context c) {
        Intent i = new Intent(c, OpenlyService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }
}
