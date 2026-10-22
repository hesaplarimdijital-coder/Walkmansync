// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.sync

import android.content.Intent
import android.net.Uri
import com.walkmansync.core.model.BindResult
import com.walkmansync.core.model.RemovableVolume
import com.walkmansync.core.model.SyncState
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.workflow.VolumeSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * UI ile senkronizasyon katmanı arasındaki TEK köprü. UI (ViewModel, B2) yalnızca bunu görür; servis,
 * coordinator ve registry'yi doğrudan kullanmaz.
 *
 * Uygulama (B1): `DefaultSyncController(app: Application, coordinator: SyncCoordinator, registry: VolumeRegistry)`.
 * Tekil örnek `AppGraph.syncController` üzerinden alınır ([com.walkmansync.WalkmanSyncApp.graph]).
 *
 * Davranış:
 *  - [state] = `SyncCoordinator.state` (aynı StateFlow). Kartlar/izinler hazır değilse AwaitingVolumes.
 *  - [scan] ve [startTransfer] coordinator kabul ederse `SyncForegroundService`'i ACTION_START ile başlatır
 *    (`ContextCompat.startForegroundService`). Servis state'i izler, busy bitince kendini durdurur.
 *  - [pause]/[resume]/[cancel]/[dismiss] doğrudan coordinator'a iletilir.
 *  - Tüm komutlar ana thread'den çağrılabilir, bloklamaz; kabul edilmezse false.
 */
interface SyncController {
    val state: StateFlow<SyncState>
    val volumes: StateFlow<VolumeSnapshot>
    val mountedVolumes: StateFlow<List<RemovableVolume>>

    fun scan(): Boolean

    /** [selection] = seçilen [com.walkmansync.core.model.SyncItem.id] kümesi (PlanReady durumundaki plana göre). */
    fun startTransfer(selection: Set<String>): Boolean

    fun pause(): Boolean
    fun resume(): Boolean
    fun cancel(): Boolean
    fun dismiss(): Boolean

    /** Kart seçimi: bkz. VolumeRegistry.createPickerIntent. */
    fun createPickerIntent(role: VolumeRole, volume: VolumeId?): Intent

    /**
     * Kart bağlama (VolumeRegistry.bind). `state.isBusy` iken çağrılmamalıdır: UI busy'de kart seçimini
     * devre dışı bırakır; yine de çağrılırsa coordinator çift değişimini görür ve işi iptal eder.
     */
    suspend fun bindVolume(role: VolumeRole, treeUri: Uri, expected: VolumeId?): BindResult

    suspend fun unbindVolume(role: VolumeRole)

    /**
     * Kart listesini yeniden okur. [afterUsbAttach]: etkinlik USB_DEVICE_ATTACHED ile açıldı/öne geldi; birim
     * henüz bağlanmamış olabileceğinden gecikmeli yeniden denemelerle okunur (VolumeRegistry.refresh(withRetries)).
     */
    fun refreshVolumes(afterUsbAttach: Boolean = false)
}
