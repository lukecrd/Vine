package it.vine.monitor;
import android.app.*;
import android.content.Intent;
import android.os.*;
import androidx.core.app.NotificationCompat;

public class KeepAliveService extends Service {
    private static final String CHANNEL_ID = "vine_keep_alive";
    private PowerManager.WakeLock wakeLock;
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Servizio Vine", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VineMonitor::KeepAwakeLock");
        wakeLock.setReferenceCounted(false);
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!wakeLock.isHeld()) wakeLock.acquire();
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Vine — Monitoraggio attivo")
                .setContentText("Attivo anche in standby (ogni 60s)")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true).setContentIntent(pi).build();
        startForeground(1, n);
        return START_STICKY;
    }
    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
