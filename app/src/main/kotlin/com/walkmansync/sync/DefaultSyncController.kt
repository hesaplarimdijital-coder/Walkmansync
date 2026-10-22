package com.walkmansync.sync

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.walkmansync.core.model.BindResult
import com.walkmansync.core.model.RemovableVolume
import com.walkmansync.core.model.SyncState
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.workflow.SyncCoordinator
import com.walkmansync.core.workflow.VolumeSnapshot
import com.walkmansync.service.SyncForegroundService
import com.walkmansync.service.SyncServiceContract
import com.walkmansync.storage.VolumeRegistry
import kotlinx.coroutines.flow.StateFlow

/** UI ile coordinator/registry/servis arasındaki tek köprü (bkz. [SyncController]). */
class DefaultSyncController(
    private val app: Application,
    private val coordinator: SyncCoordinator,
    private val registry: VolumeRegistry,
) : SyncController {

    override val state: StateFlow<SyncState> = coordinator.state
    override val volumes: StateFlow<VolumeSnapshot> = registry.snapshot
    override val mountedVolumes: StateFlow<List<RemovableVolume>> = registry.mounted

    override fun scan(): Boolean = coordinator.scan().also { if (it) startService() }

    override fun startTransfer(selection: Set<String>): Boolean =
        coordinator.startTransfer(selection).also { if (it) startService() }

    override fun pause(): Boolean = coordinator.pause()

    /** Servis (ör. Android 15 zaman aşımıyla) kapanmış olabilir; devamda ön plan yeniden kurulur. */
    override fun resume(): Boolean = coordinator.resume().also { if (it) startService() }

    override fun cancel(): Boolean = coordinator.cancel()
    override fun dismiss(): Boolean = coordinator.dismiss()

    override fun createPickerIntent(role: VolumeRole, volume: VolumeId?): Intent =
        registry.createPickerIntent(role, volume)

    override suspend fun bindVolume(role: VolumeRole, treeUri: Uri, expected: VolumeId?): BindResult =
        registry.bind(role, treeUri, expected)

    override suspend fun unbindVolume(role: VolumeRole) = registry.unbind(role)

    override fun refreshVolumes(afterUsbAttach: Boolean) = registry.refresh(withRetries = afterUsbAttach)

    private fun startService() {
        val intent = Intent(app, SyncForegroundService::class.java).setAction(SyncServiceContract.ACTION_START)
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (e: RuntimeException) {
            // Arka plandan başlatma kısıtı vb.: iş coordinator'ın kapsamında zaten sürer, yalnızca ön plan kabuğu yok.
            android.util.Log.w("WS/SyncController", "Ön plan servisi başlatılamadı", e)
        }
    }
}
