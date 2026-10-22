// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.workflow

import com.walkmansync.core.fs.GuardedTargetTree
import com.walkmansync.core.fs.SourceTree
import com.walkmansync.core.fs.TargetTree
import com.walkmansync.core.model.ScanProgress
import com.walkmansync.core.model.SyncPlan
import com.walkmansync.core.model.SyncState
import com.walkmansync.core.model.VolumeBinding
import com.walkmansync.core.model.VolumePair
import com.walkmansync.core.model.VolumeStatus
import kotlinx.coroutines.flow.StateFlow

/** Kaynak + hedef rol durumlarının anlık görüntüsü (VolumeRegistry üretir). */
data class VolumeSnapshot(
    val source: VolumeStatus,
    val target: VolumeStatus,
) {
    /**
     * İki rol de Ready ise doğrulanmış çift; aksi hâlde null. [VolumePair] init bloğu UUID kurallarını
     * zorlar; ihlal (ör. iki rol aynı UUID) → null (VolumeRegistry bu durumu zaten Conflict olarak raporlar).
     */
    val pair: VolumePair? by lazy {
        val s = source as? VolumeStatus.Ready ?: return@lazy null
        val t = target as? VolumeStatus.Ready ?: return@lazy null
        runCatching {
            VolumePair(s.binding.volume, t.binding.volume, s.binding.treeDocId, t.binding.treeDocId)
        }.getOrNull()
    }

    companion object {
        val Unbound = VolumeSnapshot(VolumeStatus.Unbound, VolumeStatus.Unbound)
    }
}

/**
 * Kayıtlı bağlamadan ağaç açan port. Uygulama (B1): app'teki `SafVolumeRegistry` (VolumeRegistry bunu genişletir).
 *
 * Her çağrıda (önbelleğe ALINMAZ — kart değişmiş olabilir) şunlar doğrulanır, aksi hâlde SyncException:
 *  - UUID şu an takılı ve tekil → değilse VolumeMissing / VolumeMismatch.
 *  - Kalıcı URI izni hâlâ var (kaynak: okuma; hedef: okuma+yazma) → değilse PermissionLost.
 *  - `treeDocId` öneki == binding.volume → değilse VolumeMismatch.
 * Dönen ağaç kaynak tutmaz (Closeable değil), işlem süresince kullanılır ve sonra bırakılır.
 * Hedef dönüş tipi [GuardedTargetTree]'dir: append-only korumasız bir hedef ağaç tip düzeyinde verilemez.
 */
interface TreeOpener {
    suspend fun openSource(binding: VolumeBinding): SourceTree
    suspend fun openTarget(binding: VolumeBinding): GuardedTargetTree
}

/**
 * Tarama iş akışı. Uygulama (B1): `DefaultScanWorkflow(indexStore, treeScanner, deltaEngine, clock,
 * dispatchers, logger)`, core. Aşamalar ([com.walkmansync.core.model.ScanPhase] sırası):
 *  1. LOADING_INDEX: `indexStore.load(target)`:
 *       Loaded → kullan (+INDEX_RECOVERED_FROM_BACKUP); Missing → boş; Foreign → boş + FOREIGN_INDEX_IGNORED;
 *       Corrupt → boş + INDEX_RESET_CORRUPT; Unsupported → SyncException(IndexUnsupported).
 *  2. LISTING_TARGET: `treeScanner.scan(target, "MUSIC")`.
 *  3. CLEANING_STALE_PARTS: hedef taramasındaki `partFiles` + `TargetLayout.indexPartPathFor(root)` silinir.
 *  4. LISTING_SOURCE: `treeScanner.scan(source, null)`.
 *  5-6. READING_TAGS / PLANNING: `deltaEngine.computePlan(...)`.
 *  7. `targetKeysChanged` ise `indexStore.save(target, plan.baseIndex)` (başarısızlık uyarı olarak yutulur).
 *     Foreign/Corrupt/Unsupported durumunda kaydetme YAPILMAZ (yabancı indeksin üzerine aktarımdan önce yazılmaz).
 */
interface ScanWorkflow {
    suspend fun scan(
        pair: VolumePair,
        source: SourceTree,
        target: TargetTree,
        onProgress: (ScanProgress) -> Unit,
    ): SyncPlan
}

/**
 * Durum makinesi — UI'nın tek gerçek kaynağı. Uygulama (B1): `DefaultSyncCoordinator(volumes:
 * StateFlow<VolumeSnapshot>, opener: TreeOpener, scanWorkflow: ScanWorkflow, transferRunner: TransferRunner,
 * clock: Clock, logger: Logger, scope: CoroutineScope)`, core. Geçişler [SyncState] belgesindeki gibidir.
 *
 * Kurallar:
 *  - Aynı anda en fazla BİR iş (tarama ya da aktarım) — tek `Job` + `Mutex`. Busy iken scan/startTransfer false döner.
 *  - Komutlar bloklamaz, herhangi bir thread'den çağrılabilir; geçersiz durumda `false` döner ve durum değişmez.
 *  - [volumes] izlenir: busy iken çiftin bir rolü Ready olmaktan çıkarsa iş iptal edilir →
 *    Failed(VolumeMissing/PermissionLost, recoverable = true). Busy değilken: çift varsa Idle(pair), yoksa
 *    AwaitingVolumes; PlanReady/Completed/Failed durumunda çift DEĞİŞİRSE plan düşürülür.
 *  - Scanning ilerlemesi ≤ 10/sn yayılır.
 *  - [cancel] Scanning'de taramayı iptal eder → Idle(pair); Transferring/Paused'da TransferControl.cancel →
 *    Completed(CANCELLED).
 *  - [startTransfer] yalnızca PlanReady'de; seçim `plan.resolveSelection` ile çözülür; boş seçim → false.
 *  - Ölümcül hata: Failed(error, recoverable = error !is IndexUnsupported && error !is Unexpected, summary).
 */
interface SyncCoordinator {
    val state: StateFlow<SyncState>

    fun scan(): Boolean
    fun startTransfer(selection: Set<String>): Boolean
    fun pause(): Boolean
    fun resume(): Boolean
    fun cancel(): Boolean

    /** Completed / Failed / PlanReady → Idle(pair) ya da AwaitingVolumes. */
    fun dismiss(): Boolean
}
