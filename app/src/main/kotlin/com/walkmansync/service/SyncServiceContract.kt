// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.service

/**
 * `SyncForegroundService` (B1) sözleşmesi. Servis bir "canlı tutma kabuğudur": işi yürütmez, yalnızca
 * süreç ön plandayken coordinator'ın (uygulama kapsamında çalışan) işinin kesilmemesini sağlar ve
 * bildirimi günceller.
 *
 * Yaşam döngüsü:
 *  - ACTION_START: `onStartCommand` içinde 5 sn dolmadan
 *    `ServiceCompat.startForeground(this, NOTIFICATION_ID_PROGRESS, n, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`.
 *    `SyncController.state` toplanır; bildirim ≤ 1/sn güncellenir. `Transferring` süresince kısmi WakeLock
 *    (etiket [WAKE_LOCK_TAG], zaman aşımı [WAKE_LOCK_TIMEOUT_MS]) tutulur, Paused/bitişte bırakılır.
 *  - state busy olmaktan çıkınca: Completed/Failed ise CHANNEL_RESULT'a sonuç bildirimi (NOTIFICATION_ID_RESULT),
 *    sonra `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()`.
 *  - ACTION_PAUSE / ACTION_RESUME / ACTION_CANCEL: bildirim düğmelerinden; SyncController'a iletilir. Servis
 *    çalışmıyorken gelirse yalnızca iletilir, ön plana geçilmez (startService ile değil PendingIntent.getService).
 *  - `onTimeout(startId, fgsType)` (API 35 dataSync süre sınırı): cancel() + stopSelf().
 *  - START_NOT_STICKY: süreç ölürse servis yeniden başlatılmaz (yarım .part sonraki taramada temizlenir).
 */
object SyncServiceContract {
    const val ACTION_START = "com.walkmansync.action.START"
    const val ACTION_PAUSE = "com.walkmansync.action.PAUSE"
    const val ACTION_RESUME = "com.walkmansync.action.RESUME"
    const val ACTION_CANCEL = "com.walkmansync.action.CANCEL"

    /** Süren işlem (düşük önem, sessiz). */
    const val CHANNEL_PROGRESS = "sync_progress"

    /** Tamamlandı / hata (varsayılan önem). */
    const val CHANNEL_RESULT = "sync_result"

    const val NOTIFICATION_ID_PROGRESS = 1001
    const val NOTIFICATION_ID_RESULT = 1002

    /** PendingIntent istek kodları (benzersiz). */
    const val REQUEST_OPEN_APP = 10
    const val REQUEST_PAUSE = 11
    const val REQUEST_RESUME = 12
    const val REQUEST_CANCEL = 13

    const val WAKE_LOCK_TAG = "WalkmanSync:transfer"
    const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
}
