package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;

public class StreamingController {

    private static volatile StreamingController instance;

    // Video state
    public volatile File    videoFile;
    public volatile String  videoTitle    = "";
    public volatile boolean hasNext       = false;
    public volatile boolean hasPrev       = false;
    public volatile long    duration      = 0;
    public volatile long    position      = 0;
    public volatile int     version       = 0;
    public volatile float   speed         = 1.0f;
    public volatile String  fileName      = "";
    public volatile int     currentAccount = 0;
    public volatile org.telegram.tgnet.TLRPC.Document document = null;
    public volatile Object  parentObject   = null;

    public volatile Runnable       nextListener;
    public volatile Runnable       prevListener;
    public volatile SeekListener   seekListener;
    public volatile Runnable       stopListener;
    public interface SeekListener { void onSeek(long positionMs); }

    private LocalStreamServer    server;
    private Context              appContext;
    private PowerManager.WakeLock wakeLock;

    private static final int    NOTIF_ID       = 7721;
    private static final String CHANNEL_ID     = "stream_service";
    public  static final String ACTION_STOP    = "com.telegram.STREAM_STOP";
    public  static final String ACTION_NEXT    = "com.telegram.STREAM_NEXT";
    public  static final String ACTION_PREV    = "com.telegram.STREAM_PREV";

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
                               org.telegram.tgnet.TLRPC.Document doc, Object parent,
                               Runnable onNext, Runnable onPrev,
                               SeekListener onSeek, Runnable onStop) {
        appContext     = ctx.getApplicationContext();
        videoFile      = file;
        videoTitle     = title != null ? title : "video";
        duration       = durationMs;
        hasNext        = next;
        hasPrev        = prev;
        speed          = spd;
        fileName       = fn != null ? fn : "";
        currentAccount = account;
        document       = doc;
        parentObject   = parent;
        nextListener   = onNext;
        prevListener   = onPrev;
        seekListener   = onSeek;
        stopListener   = onStop;
        position       = 0;
        version        = 0;

        if (server != null) { server.stop(); server = null; }

        // Keep CPU alive for next/prev commands when screen is off
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
        } catch (IOException e) {
            server = null;
            return;
        }

        // Start foreground service so app survives being swiped from recents
        Intent serviceIntent = new Intent(appContext, StreamService.class);
        serviceIntent.putExtra("title", videoTitle);
        serviceIntent.putExtra("url", getUrl());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(serviceIntent);
        } else {
            appContext.startService(serviceIntent);
        }
    }

    public void updateVideo(File file, String title, long durationMs,
                            boolean next, boolean prev, float spd, String fn,
                            org.telegram.tgnet.TLRPC.Document doc, Object parent,
                            Runnable onNext, Runnable onPrev,
                            SeekListener onSeek, Runnable onStop) {
        videoFile  = file;
        videoTitle = title != null ? title : "video";
        duration   = durationMs;
        hasNext    = next;
        hasPrev    = prev;
        speed      = spd;
        if (fn != null) fileName = fn;
        if (doc != null) { document = doc; parentObject = parent; }
        if (onNext != null) nextListener = onNext;
        if (onPrev != null) prevListener = onPrev;
        if (onSeek != null) seekListener = onSeek;
        if (onStop != null) stopListener = onStop;
        position = 0;
        version++;
        // Update notification title
        Intent i = new Intent(appContext, StreamService.class);
        i.putExtra("title", videoTitle);
        i.putExtra("url", getUrl());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(i);
        } else {
            appContext.startService(i);
        }
    }

    public void stopStreaming() {
        if (stopListener != null) {
            Runnable sl = stopListener;
            stopListener = null;
            AndroidUtilities.runOnUIThread(sl);
        }
        if (server != null) { server.stop(); server = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        // Stop the foreground service
        if (appContext != null) {
            appContext.stopService(new Intent(appContext, StreamService.class));
        }
    }

    public void onBrowserSeek(long posMs) {
        position = posMs;
        if (!fileName.isEmpty() && duration > 0 && videoFile != null) {
            long fileSize = videoFile.length();
            long byteOffset = (long)(fileSize * ((double) posMs / duration));
            FileLoader.getInstance(currentAccount).setStreamPriorityOffset(fileName, byteOffset);
        }
        if (seekListener != null) seekListener.onSeek(posMs);
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

    // ── Foreground Service — survives app removal from recents ────────────────

    public static class StreamService extends Service {

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            ensureChannel();
            String title = intent != null ? intent.getStringExtra("title") : "";
            String url   = intent != null ? intent.getStringExtra("url")   : "";
            if (title == null) title = "video";
            if (url   == null) url   = "";
            startForeground(NOTIF_ID, buildNotif(this, title, url));
            return START_STICKY; // Restart if killed
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            // App swiped from recents — keep service alive, do NOT stop
            // Just update notification to reflect state
            super.onTaskRemoved(rootIntent);
        }

        @Override
        public IBinder onBind(Intent intent) { return null; }

        @Override
        public void onDestroy() {
            super.onDestroy();
            // If service is destroyed (e.g. Stop button), clean up
            StreamingController sc = StreamingController.getInstance();
            if (sc.isStreaming()) sc.stopStreaming();
        }

        private void ensureChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Stream Server", NotificationManager.IMPORTANCE_LOW);
                ch.setSound(null, null);
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(ch);
            }
        }
    }

    private static Notification buildNotif(Context ctx, String title, String url) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            : PendingIntent.FLAG_UPDATE_CURRENT;

        Intent stopI = new Intent(ACTION_STOP).setClass(ctx, Receiver.class);
        Intent nextI = new Intent(ACTION_NEXT).setClass(ctx, Receiver.class);
        Intent prevI = new Intent(ACTION_PREV).setClass(ctx, Receiver.class);

        PendingIntent stopPi = PendingIntent.getBroadcast(ctx, 0, stopI, flags);
        PendingIntent nextPi = PendingIntent.getBroadcast(ctx, 1, nextI, flags);
        PendingIntent prevPi = PendingIntent.getBroadcast(ctx, 2, prevI, flags);

        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("📡 " + title)
            .setContentText("Streaming • " + url)
            .setOngoing(true)
            .setShowWhen(false);

        StreamingController sc = getInstance();
        if (sc.hasPrev) b.addAction(android.R.drawable.ic_media_previous, "Prev", prevPi);
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi);
        if (sc.hasNext) b.addAction(android.R.drawable.ic_media_next, "Next", nextPi);

        return b.build();
    }

    // ── Broadcast Receiver — handles notification buttons ─────────────────────

    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            StreamingController sc = getInstance();
            if (ACTION_STOP.equals(action)) {
                sc.stopStreaming();
            } else if (ACTION_NEXT.equals(action)) {
                if (sc.nextListener != null)
                    AndroidUtilities.runOnUIThread(sc.nextListener);
            } else if (ACTION_PREV.equals(action)) {
                if (sc.prevListener != null)
                    AndroidUtilities.runOnUIThread(sc.prevListener);
            }
        }
    }
}
