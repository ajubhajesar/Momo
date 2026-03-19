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

public class StreamingController {

    private static volatile StreamingController instance;

    public volatile File    videoFile;
    public volatile String  videoTitle = "";
    public volatile boolean hasNext    = false;
    public volatile boolean hasPrev    = false;
    public volatile Runnable nextListener;
    public volatile Runnable prevListener;

    private LocalStreamServer server;
    private Context           appContext;

    private static final int    NOTIF_ID   = 7721;
    private static final String CHANNEL_ID = "stream_service";
    private static final String ACTION_STOP   = "com.telegram.STREAM_STOP";
    private static final String ACTION_REVOKE = "com.telegram.STREAM_REVOKE";

    public static StreamingController getInstance() {
        if (instance == null) synchronized (StreamingController.class) {
            if (instance == null) instance = new StreamingController();
        }
        return instance;
    }

    public boolean isStreaming() {
        return server != null && server.isAlive();
    }

    public void startStreaming(Context ctx, File file, String title,
                               boolean next, boolean prev,
                               Runnable onNext, Runnable onPrev) {
        appContext    = ctx.getApplicationContext();
        videoFile     = file;
        videoTitle    = title != null ? title : "video";
        hasNext       = next;
        hasPrev       = prev;
        nextListener  = onNext;
        prevListener  = onPrev;

        if (server != null) { server.stop(); server = null; }
        try {
            server = new LocalStreamServer();
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            showNotification(title);
        } catch (IOException e) {
            server = null;
        }
    }

    public void updateVideo(File file, String title, boolean next, boolean prev,
                            Runnable onNext, Runnable onPrev) {
        videoFile     = file;
        videoTitle    = title != null ? title : "video";
        hasNext       = next;
        hasPrev       = prev;
        nextListener  = onNext;
        prevListener  = onPrev;
        updateNotification(title);
    }

    public void stopStreaming() {
        if (server != null) { server.stop(); server = null; }
        if (appContext != null) {
            NotificationManager nm = (NotificationManager)
                appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        }
    }

    public String getUrl() {
        try {
            java.net.InetAddress addr = java.net.InetAddress.getLocalHost();
            java.net.NetworkInterface ni;
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                ni = ifaces.nextElement();
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

    // ── Notification ─────────────────────────────────────────────────────────────

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

        return new android.app.Notification.Builder(appContext, CHANNEL_ID)
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
