// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.model

/**
 * UI'nın gözlemlediği tek durum (SyncController.state). Geçişler:
 *
 * ```
 * Idle ──volumes ok──▶ Idle(ready)       AwaitingVolumes ◀── kart/izin eksik (her durumdan, aktarım hariç)
 * Idle ──scan()──▶ Scanning ──▶ PlanReady ──startTransfer()──▶ Transferring ⇄ Paused
 *                     │                                            │
 *                     └──hata──▶ Failed                            ├──▶ Completed(summary)  (bitti/iptal/kısmi)
 *                                                                  └──▶ Failed(ölümcül)    (summary ile)
 * Completed / Failed / PlanReady ──scan()──▶ Scanning ;  ──dismiss()──▶ Idle
 * ```
 */
sealed interface SyncState {

    /** Boşta. [pair] doluysa kartlar hazır ve tarama başlatılabilir. */
    data class Idle(val pair: VolumePair?) : SyncState

    /** Kaynak ve/veya hedef kart kayıtlı değil, takılı değil ya da izni yok. */
    data class AwaitingVolumes(
        val sourceStatus: VolumeStatus,
        val targetStatus: VolumeStatus,
    ) : SyncState

    data class Scanning(val pair: VolumePair, val progress: ScanProgress) : SyncState

    data class PlanReady(val plan: SyncPlan) : SyncState

    data class Transferring(val plan: SyncPlan, val progress: TransferProgress) : SyncState

    /** Duraklatıldı; açık dosya tanıtıcısı tutulmaz (bkz. TransferRunner duraklatma kuralları). */
    data class Paused(val plan: SyncPlan, val progress: TransferProgress) : SyncState

    /** Aktarım bitti (COMPLETED / COMPLETED_WITH_ERRORS / CANCELLED). */
    data class Completed(val summary: TransferSummary) : SyncState

    /**
     * Ölümcül hata. [recoverable]=true: kullanıcı kartı yeniden takıp/izin verip tekrar tarayabilir.
     * Aktarım sırasında olduysa [summary] o ana kadarki sonucu taşır.
     */
    data class Failed(
        val error: SyncError,
        val recoverable: Boolean,
        val summary: TransferSummary? = null,
    ) : SyncState

    /** Durumun kartlar üzerinde işlem yürüttüğü (servis ön planda olmalı) durumlar. */
    val isBusy: Boolean get() = this is Scanning || this is Transferring || this is Paused
}

/** Bir rolün kart durumu (AwaitingVolumes ve kart seçim ekranı için). */
sealed interface VolumeStatus {
    /** Bu rol için kart seçilmedi. */
    data object Unbound : VolumeStatus
    /** Kayıtlı ama şu an takılı değil. */
    data class NotMounted(val binding: VolumeBinding) : VolumeStatus
    /** Takılı ama kalıcı URI izni kaybolmuş. */
    data class PermissionMissing(val binding: VolumeBinding) : VolumeStatus
    /**
     * Takılı ama salt-okunur bağlanmış (MOUNTED_READ_ONLY). Yalnızca HEDEF rolü için üretilir (kaynak zaten
     * yalnızca okunur, onun için salt-okunur birim [Ready]'dir). Hedefe yazılamayacağı için çift oluşmaz.
     */
    data class ReadOnly(val binding: VolumeBinding, val volume: RemovableVolume) : VolumeStatus

    /** Takılı, izinli, kullanıma hazır. */
    data class Ready(val binding: VolumeBinding, val volume: RemovableVolume) : VolumeStatus
    /** Takılı birimler arasında aynı UUID'den birden fazla var — güvenlik gereği kullanılamaz. */
    data class Conflict(val binding: VolumeBinding) : VolumeStatus

    val isReady: Boolean get() = this is Ready
}
