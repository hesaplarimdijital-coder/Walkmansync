package com.walkmansync.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.walkmansync.WalkmanSyncApp
import com.walkmansync.core.model.SyncState
import com.walkmansync.sync.SyncController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Canlı tutma kabuğu (bkz. [SyncServiceContract]). İşi yürütmez: iş coordinator'ın uygulama kapsamındaki
 * coroutine'inde koşar; bu servis yalnızca sürecin ön planda kalmasını ve bildirimi sağlar. Bildirim
 * izni (API 33+) reddedilmiş olsa da servis ve iş çalışır, yalnızca bildirim görünmez.
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: SyncNotifications
    private lateinit var controller: SyncController
    private var collector: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var foregroundStarted = false
    private var tracking = false
    private var sawBusy = false
    private var latestStartId = 0
    private var lastNotifyAt = 0L
    private var lastNotifiedKind: Class<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = SyncNotifications(this)
        notifications.ensureChannels()
        controller = WalkmanSyncApp.graph(this).syncController
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SyncServiceContract.ACTION_PAUSE -> forward(startId) { controller.pause() }
            SyncServiceContract.ACTION_RESUME -> forward(startId) { controller.resume() }
            SyncServiceContract.ACTION_CANCEL -> forward(startId) { controller.cancel() }
            else -> {
                latestStartId = startId
                // Bir START yalnızca kabul edilmiş bir scan/startTransfer'dan sonra gelir → sonuç bildirilebilir.
                sawBusy = true
                startTracking()
            }
        }
        return START_NOT_STICKY
    }

    /** Bildirim düğmelerinden gelen komutu iletir; servis izlemiyorsa (ön planda değilse) kendini kapatır. */
    private inline fun forward(startId: Int, command: () -> Boolean) {
        // En son startId izlenir: finish() içindeki stopSelf(latestStartId) daha yeni bir komut gelmişse
        // servisi durduramaz ve servis ön plan dışında asılı kalırdı.
        latestStartId = startId
        command()
        if (!tracking) stopSelf(startId)
    }

    private fun startTracking() {
        if (!foregroundStarted) {
            try {
                ServiceCompat.startForeground(
                    this,
                    SyncServiceContract.NOTIFICATION_ID_PROGRESS,
                    notifications.initial(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                foregroundStarted = true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (API 31+) / SecurityException: iş yine de sürer.
                WalkmanSyncApp.graph(this).logger.e(TAG, "startForeground başarısız", e)
                stopSelf(latestStartId)
                return
            }
        }
        if (tracking) return
        tracking = true
        collector = scope.launch { controller.state.collect { render(it) } }
    }

    private fun render(state: SyncState) {
        when (state) {
            is SyncState.Scanning, is SyncState.Transferring, is SyncState.Paused -> {
                sawBusy = true
                updateWakeLock(hold = state is SyncState.Transferring)
                notifyProgress(state)
            }
            is SyncState.Completed, is SyncState.Failed -> {
                if (sawBusy) notifications.postResult(state)
                finish()
            }
            else -> finish() // Idle / AwaitingVolumes / PlanReady
        }
    }

    /** Bildirim ≤ 1/sn; durum türü değişince (ör. Duraklat) hemen güncellenir. */
    private fun notifyProgress(state: SyncState) {
        val now = SystemClock.elapsedRealtime()
        val kind = state.javaClass
        if (kind == lastNotifiedKind && now - lastNotifyAt < MIN_NOTIFY_INTERVAL_MS) return
        val notification = notifications.progress(state) ?: return
        lastNotifiedKind = kind
        lastNotifyAt = now
        notifications.notifyProgress(notification)
    }

    private fun finish() {
        collector?.cancel()
        collector = null
        tracking = false
        updateWakeLock(hold = false)
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf(latestStartId)
    }

    /** Android 15+: dataSync süre sınırı doldu. 5 sn içinde işi durdurup servisi kapatmak ZORUNLU. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        WalkmanSyncApp.graph(this).logger.w(TAG, "FGS zaman aşımı (tip=$fgsType)")
        when (controller.state.value) {
            is SyncState.Transferring -> controller.pause() // dosya kapanır, ilerleme korunur; kullanıcı devam ettirir
            is SyncState.Scanning -> controller.cancel()
            else -> Unit
        }
        notifications.postTimeout()
        finish()
        stopSelf(startId)
    }

    private fun updateWakeLock(hold: Boolean) {
        if (!hold) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            return
        }
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, SyncServiceContract.WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(SyncServiceContract.WAKE_LOCK_TIMEOUT_MS)
        }
    }

    override fun onDestroy() {
        updateWakeLock(hold = false)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SyncService"
        const val MIN_NOTIFY_INTERVAL_MS = 1_000L
    }
}
