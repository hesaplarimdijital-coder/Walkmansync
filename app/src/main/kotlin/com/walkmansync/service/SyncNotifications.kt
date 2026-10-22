package com.walkmansync.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.walkmansync.R
import com.walkmansync.core.model.ScanPhase
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncState
import com.walkmansync.core.model.TransferOutcome
import com.walkmansync.core.model.TransferProgress
import com.walkmansync.core.model.TransferSummary
import java.util.Locale

/**
 * Bildirim kanalları ve içerikleri. Saf üretim sınıfıdır; yayınlama/zamanlama [SyncForegroundService]'tedir.
 * POST_NOTIFICATIONS reddedilmiş olsa da çağrılar zararsızdır (bildirim yalnızca görünmez).
 */
internal class SyncNotifications(context: Context) {
    private val ctx: Context = context.applicationContext
    private val manager: NotificationManager = requireNotNull(ctx.getSystemService(NotificationManager::class.java))

    fun ensureChannels() {
        val compat = NotificationManagerCompat.from(ctx)
        compat.createNotificationChannel(
            NotificationChannelCompat.Builder(SyncServiceContract.CHANNEL_PROGRESS, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(ctx.getString(R.string.svc_channel_progress_name))
                .setDescription(ctx.getString(R.string.svc_channel_progress_desc))
                .setShowBadge(false)
                .build(),
        )
        compat.createNotificationChannel(
            NotificationChannelCompat.Builder(SyncServiceContract.CHANNEL_RESULT, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(ctx.getString(R.string.svc_channel_result_name))
                .setDescription(ctx.getString(R.string.svc_channel_result_desc))
                .build(),
        )
    }

    /** startForeground için ilk bildirim (5 sn kuralı: durumu beklemeden hazır). */
    fun initial(): Notification =
        progressBuilder()
            .setContentTitle(ctx.getString(R.string.svc_notif_scanning_title))
            .setContentText(ctx.getString(R.string.svc_notif_preparing))
            .setProgress(0, 0, true)
            .addAction(cancelAction())
            .build()

    fun notifyProgress(notification: Notification) {
        manager.notify(SyncServiceContract.NOTIFICATION_ID_PROGRESS, notification)
    }

    fun progress(state: SyncState): Notification? = when (state) {
        is SyncState.Scanning -> scanning(state)
        is SyncState.Transferring -> transferring(state.progress, paused = false)
        is SyncState.Paused -> transferring(state.progress, paused = true)
        else -> null
    }

    fun postResult(state: SyncState) {
        val n = result(state) ?: return
        manager.notify(SyncServiceContract.NOTIFICATION_ID_RESULT, n)
    }

    fun postTimeout() {
        val n = NotificationCompat.Builder(ctx, SyncServiceContract.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(ctx.getString(R.string.svc_timeout_title))
            .setContentText(ctx.getString(R.string.svc_timeout_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(R.string.svc_timeout_text)))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        manager.notify(SyncServiceContract.NOTIFICATION_ID_RESULT, n)
    }

    // ---- ilerleme --------------------------------------------------------------------------------

    private fun scanning(state: SyncState.Scanning): Notification {
        val p = state.progress
        val text = when (p.phase) {
            ScanPhase.LOADING_INDEX -> ctx.getString(R.string.svc_scan_loading_index)
            ScanPhase.LISTING_TARGET -> ctx.getString(R.string.svc_scan_listing_target)
            ScanPhase.CLEANING_STALE_PARTS -> ctx.getString(R.string.svc_scan_cleaning)
            ScanPhase.LISTING_SOURCE -> ctx.getString(R.string.svc_scan_listing_source)
            ScanPhase.READING_TAGS ->
                if (p.itemsTotal > 0) ctx.getString(R.string.svc_scan_reading_tags_count, p.itemsDone, p.itemsTotal)
                else ctx.getString(R.string.svc_scan_reading_tags)
            ScanPhase.PLANNING -> ctx.getString(R.string.svc_scan_planning)
        }
        val builder = progressBuilder()
            .setContentTitle(ctx.getString(R.string.svc_notif_scanning_title))
            .setContentText(text)
            .addAction(cancelAction())
        val fraction = p.fraction
        if (p.phase == ScanPhase.READING_TAGS && fraction != null) {
            builder.setProgress(100, (fraction * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun transferring(p: TransferProgress, paused: Boolean): Notification {
        val processed = p.completedItems + p.failedItems
        val builder = progressBuilder()
            .setContentTitle(
                ctx.getString(if (paused) R.string.svc_notif_paused_title else R.string.svc_notif_transferring_title),
            )
            .setProgress(100, (p.fraction * 100).toInt(), false)
        if (paused) {
            builder.setContentText(ctx.getString(R.string.svc_notif_paused_text, processed, p.totalItems))
            builder.addAction(action(R.string.svc_action_resume, SyncServiceContract.ACTION_RESUME, SyncServiceContract.REQUEST_RESUME))
        } else {
            val text = if (p.bytesPerSecond > 0) {
                ctx.getString(
                    R.string.svc_notif_transfer_text, processed, p.totalItems,
                    Formatter.formatFileSize(ctx, p.bytesPerSecond),
                )
            } else {
                ctx.getString(R.string.svc_notif_transfer_text_no_speed, processed, p.totalItems)
            }
            builder.setContentText(text)
            p.etaMillis?.let { builder.setSubText(ctx.getString(R.string.svc_notif_eta, formatDuration(it))) }
            builder.addAction(action(R.string.svc_action_pause, SyncServiceContract.ACTION_PAUSE, SyncServiceContract.REQUEST_PAUSE))
        }
        builder.addAction(cancelAction())
        return builder.build()
    }

    private fun progressBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, SyncServiceContract.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())

    private fun cancelAction() =
        action(R.string.svc_action_cancel, SyncServiceContract.ACTION_CANCEL, SyncServiceContract.REQUEST_CANCEL)

    private fun action(titleRes: Int, action: String, request: Int): NotificationCompat.Action {
        val intent = Intent(ctx, SyncForegroundService::class.java).setAction(action)
        // PendingIntent.getService: servis çalışmıyorsa ön plana geçmeden yalnızca komutu iletir.
        val pending = PendingIntent.getService(
            ctx, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, ctx.getString(titleRes), pending).build()
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            ctx, SyncServiceContract.REQUEST_OPEN_APP, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ---- sonuç -----------------------------------------------------------------------------------

    private fun result(state: SyncState): Notification? = when (state) {
        is SyncState.Completed -> completed(state.summary)
        is SyncState.Failed -> failed(state)
        else -> null
    }

    private fun completed(s: TransferSummary): Notification {
        val (title, text) = when (s.outcome) {
            TransferOutcome.COMPLETED -> ctx.getString(R.string.svc_result_done_title) to
                ctx.getString(R.string.svc_result_done_text, s.copiedItems, Formatter.formatFileSize(ctx, s.copiedBytes))
            TransferOutcome.COMPLETED_WITH_ERRORS -> ctx.getString(R.string.svc_result_errors_title) to
                ctx.getString(R.string.svc_result_errors_text, s.copiedItems, s.failures.size + (s.notAttempted))
            TransferOutcome.CANCELLED -> ctx.getString(R.string.svc_result_cancelled_title) to
                ctx.getString(R.string.svc_result_cancelled_text, s.copiedItems)
            TransferOutcome.ABORTED -> ctx.getString(R.string.svc_result_failed_title) to errorText(s.fatalError)
        }
        return resultBuilder(title, text).setCategory(NotificationCompat.CATEGORY_STATUS).build()
    }

    private fun failed(state: SyncState.Failed): Notification =
        resultBuilder(ctx.getString(R.string.svc_result_failed_title), errorText(state.error))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()

    private fun resultBuilder(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, SyncServiceContract.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())

    private fun errorText(error: SyncError?): String = ctx.getString(
        when (error) {
            is SyncError.VolumeMissing -> R.string.svc_err_volume_missing
            is SyncError.VolumeMismatch -> R.string.svc_err_volume_mismatch
            is SyncError.PermissionLost -> R.string.svc_err_permission
            is SyncError.VolumeReadOnly -> R.string.svc_err_read_only
            is SyncError.InsufficientSpace -> R.string.svc_err_space
            is SyncError.IoFailure -> R.string.svc_err_io
            is SyncError.IndexUnsupported -> R.string.svc_err_index_unsupported
            else -> R.string.svc_err_unexpected
        },
    )

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
