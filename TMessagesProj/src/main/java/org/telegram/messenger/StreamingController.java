package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import android.os.PowerManager;

public class StreamingController {

    private static volatile StreamingController instance;

    // Video state
    public volatile File    videoFile;
    public volatile String  videoTitle    = "";
    public volatile boolean hasNext       = false;
    public volatile boolean hasPrev       = false;
    public volatile long    duration      = 0;    // ms - total duration
    public volatile long    position      = 0;    // ms - last known browser position
    public volatile int     version       = 0;    // increments on every video change
    public volatile float   speed         = 1.0f;
    public volatile String  fileName      = "";   // for FileLoader priority
    public volatile int     currentAccount = 0;

    // Listeners called when browser clicks next/prev
    public volatile Runnable nextListener;
    public volatile Runnable prevListener;

    // Called when browser seeks - triggers download priority
    public volatile SeekListener seekListener;
    public interface SeekListener { void onSeek(long positionMs); }

    // Called when stop is requested - resume phone from browser position
    public volatile Runnable stopListener;

    private LocalStreamServer    server;
    private Context              appContext;
    private PowerManager.WakeLock wakeLock;

    private static final int    NOTIF_ID   = 7721;
    private static final String CHANNEL_ID = "stream_service";
    private static final String ACTION_STOP = "com.telegram.STREAM_STOP";

    public static StreamingController getInstance() {
        if (instance == null) synchronized (StreamingController.class) {
            if (instance == null) instance = new StreamingController();
        }
        return instance;
    }

    public boolean isStreaming() {
        return server != null && server.isAlive();
    }

    public void startStreaming(Context ctx, File file, String title, long durationMs,
                               boolean next, boolean prev, float spd, String fn, int account,
                               Runnable onNext, Runnable onPrev,
                               SeekListener onSeek, Runnable onStop) {
        appContext    = ctx.getApplicationContext();
        videoFile     = file;
        videoTitle    = title != null ? title : "video";
        duration      = durationMs;
        hasNext       = next;
        hasPrev       = prev;
        speed         = spd;
        fileName      = fn != null ? fn : "";
        currentAccount = account;
        nextListener  = onNext;
        prevListener  = onPrev;
        seekListener  = onSeek;
        stopListener  = onStop;
        position      = 0;
        version       = 0;

        if (server != null) { server.stop(); server = null; }
        // Keep CPU alive so next/prev commands work when screen is off
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Momo:streaming");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(3 * 60 * 60 * 1000L);
        try {
            server = new LocalStreamServer();
            server.start(30000, false);
            showNotification(title);
        } catch (IOException e) {
            server = null;
        }
    }

    public void updateVideo(File file, String title, long durationMs,
                            boolean next, boolean prev, float spd, String fn,
                            Runnable onNext, Runnable onPrev,
                            SeekListener onSeek, Runnable onStop) {
        videoFile  = file;
        videoTitle = title != null ? title : "video";
        duration   = durationMs;
        hasNext    = next;
        hasPrev    = prev;
        speed      = spd;
        if (fn != null) fileName = fn;
        if (onNext != null) nextListener = onNext;
        if (onPrev != null) prevListener = onPrev;
        if (onSeek != null) seekListener = onSeek;
        if (onStop != null) stopListener = onStop;
        position   = 0;
        version++;
        updateNotification(title);
    }

    public void stopStreaming() {
        if (stopListener != null) {
            Runnable sl = stopListener;
            stopListener = null;
            AndroidUtilities.runOnUIThread(sl);
        }
        if (server != null) { server.stop(); server = null; }
        if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); }
        if (appContext != null) {
            NotificationManager nm = (NotificationManager)
                appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        }
    }

    public void onBrowserSeek(long posMs) {
        syncPosition(posMs);
        // Trigger download priority at this offset
        if (!fileName.isEmpty() && duration > 0 && videoFile != null) {
            long fileSize = videoFile.length();
            long byteOffset = (long)(fileSize * ((double) posMs / duration));
            FileLoader.getInstance(currentAccount).setStreamPriorityOffset(fileName, byteOffset);
        }
        if (seekListener != null) seekListener.onSeek(posMs);
    }

    /** Called by both browser seek and periodic updatepos to keep phone's
     *  saved-position stores in sync with where the browser is playing. */
    public void syncPosition(long posMs) {
        position = posMs;
        if (videoTitle == null || videoTitle.isEmpty() || duration <= 0) return;
        // Convert ms → 0-1 float (same scale as media_saved_pos)
        float prog = (float) posMs / (float) duration;
        if (prog <= 0 || prog >= 0.999f) return;
        // Write to short-term in-memory map (same key the app uses: getFileNameFast)
        final String key = videoTitle;
        AndroidUtilities.runOnUIThread(() -> {
            org.telegram.ui.PhotoViewer pv = org.telegram.ui.PhotoViewer.hasInstance()
                ? org.telegram.ui.PhotoViewer.getInstance() : null;
            if (pv != null) pv.saveBrowserPosition(key, prog);
        });
    }

    public String getUrl() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return "http://" + a.getHostAddress() + ":39154";
                    }
                }
            }
        } catch (Exception ignored) {}
        return "http://localhost:39154";
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void showNotification(String title) {
        if (appContext == null) return;
        NotificationManager nm = (NotificationManager)
            appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Stream Server", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        nm.notify(NOTIF_ID, buildNotif(title));
    }

    private void updateNotification(String title) {
        if (appContext == null || !isStreaming()) return;
        NotificationManager nm = (NotificationManager)
            appContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotif(title));
    }

    private Notification buildNotif(String title) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;
        Intent stopI = new Intent(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getBroadcast(appContext, 0, stopI, flags);
        return new Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("📡 " + title)
            .setContentText("Streaming • " + getUrl())
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build();
    }

    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (ACTION_STOP.equals(intent.getAction())) {
                getInstance().stopStreaming();
            }
        }
    }
}
