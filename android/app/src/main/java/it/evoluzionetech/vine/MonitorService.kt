package it.evoluzionetech.vine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.webkit.WebSettings
import it.evoluzionetech.vine.core.Event
import it.evoluzionetech.vine.core.Monitor
import it.evoluzionetech.vine.core.VineCore
import it.evoluzionetech.vine.data.store
import it.evoluzionetech.vine.net.Http
import it.evoluzionetech.vine.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Foreground loop: scan, then ~60 s pause. Stops itself as soon as the monitor is no longer running. */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        alive = true
        channels(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vine:scan").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = statusNotification("Lettura in corso…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ONGOING, n)
        if (loop?.isActive != true) loop = scope.launch {
            val m = monitor(this@MonitorService)
            while (true) {
                wakeLock.acquire(70_000) // timeout: never left held
                val wait = try {
                    m.scan() ?: break // stopped or suspended
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) { // state not saveable (e.g. storage full): stop, never retry, never drop seen ASINs
                    getSystemService(NotificationManager::class.java).notify(
                        STORAGE,
                        Notification.Builder(this@MonitorService, CH_STATUS)
                            .setSmallIcon(R.drawable.ic_stat)
                            .setContentTitle("Vine · controlli fermati")
                            .setContentText("Impossibile salvare lo stato (memoria piena?). Libera spazio e premi Avvia.")
                            .build(),
                    )
                    break
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
                getSystemService(NotificationManager::class.java).notify(ONGOING, statusNotification(m.state().status))
                delay(wait)
            }
            stopSelf()
        }
        return START_NOT_STICKY // after a kill the UI shows "Fermo", resume is manual
    }

    override fun onDestroy() {
        alive = false
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun statusNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Vine · monitor attivo")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CH_STATUS = "status"
        private const val CH_ITEMS = "items"
        private const val ONGOING = 1
        private const val STORAGE = 2

        /** True while the service lives in this process; false after a restart or a forced stop. */
        @Volatile var alive = false

        private var instance: Monitor? = null

        fun monitor(context: Context): Monitor = synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                Monitor(
                    load = { app.store.data.first() },
                    save = { s -> app.store.updateData { s } },
                    fetch = { Http.get(desktopAgent(app), it) },
                    notify = { notifyItem(app, it) },
                )
            }.also { instance = it }
        }

        // Mobile Vine HTML has no "Visualizzazione di X–Y di Z risultati" count; the parser is verified on desktop HTML.
        private fun desktopAgent(context: Context): String {
            val chrome = Regex("Chrome/[\\d.]+").find(WebSettings.getDefaultUserAgent(context))?.value ?: "Chrome/130.0.0.0"
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        }

        fun channels(context: Context) = context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CH_STATUS, "Stato del monitor", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_ITEMS, "Nuovi prodotti Vine", NotificationManager.IMPORTANCE_HIGH),
            )
        )

        private fun notifyItem(context: Context, e: Event): Boolean {
            val nm = context.getSystemService(NotificationManager::class.java)
            val asin = VineCore.asin(e.id) ?: return false
            if (!nm.areNotificationsEnabled()) return false
            val open = PendingIntent.getActivity(
                context, asin.hashCode(),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.amazon.it/dp/$asin")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val generic = Notification.Builder(context, CH_ITEMS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Nuovo prodotto Vine")
                .build()
            val n = Notification.Builder(context, CH_ITEMS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Nuovo prodotto Vine")
                .setContentText(e.title)
                .setStyle(Notification.BigTextStyle().bigText(e.title))
                .setSubText("Tocca per aprire il prodotto")
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(generic)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            return try {
                nm.notify("product-$asin", 0, n)
                true
            } catch (x: SecurityException) {
                false
            }
        }
    }
}
