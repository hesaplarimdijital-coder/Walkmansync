// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.model

/** Tarama aşamaları, yürütme sırasıyla (UI'da sırayla gösterilir). */
enum class ScanPhase { LOADING_INDEX, LISTING_TARGET, CLEANING_STALE_PARTS, LISTING_SOURCE, READING_TAGS, PLANNING }

/**
 * Tarama ilerlemesi. Bilinmeyen toplamlar 0'dır. Yayın sıklığı en fazla ~10/sn (bkz. ARCHITECTURE).
 *
 * @property itemsDone Aşamaya göre: listelenen dosya sayısı ya da okunan etiket sayısı.
 * @property itemsTotal Biliniyorsa toplam (READING_TAGS'ta etiket okunacak dosya sayısı), yoksa 0.
 */
data class ScanProgress(
    val phase: ScanPhase,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    val directoriesVisited: Int = 0,
    val currentPath: String? = null,
) {
    /** 0..1 ya da belirsizse null. */
    val fraction: Float? get() = if (itemsTotal > 0) (itemsDone.toFloat() / itemsTotal).coerceIn(0f, 1f) else null
}

/**
 * Aktarım ilerlemesi (TransferRunner yayınlar). Bayt alanları o ana kadar hedefe YAZILAN baytlardır
 * (şu anki dosyanın yazılmış kısmı dahil).
 *
 * @property bytesPerSecond Kayan pencere (≈5 sn) ortalaması; ilk ölçüm öncesi 0.
 * @property etaMillis Kalan süre tahmini; hız 0 ya da duraklatılmışsa null.
 * @property currentItem Şu an kopyalanan öğe; dosyalar arası ve bitişte null.
 * @property currentItemBytes [currentItem]'ın yazılmış baytı (duraklat/devamda dosya baştan yazılır → sıfırlanır).
 * @property isPaused TransferControl.pause sonrası, açık dosya kapatılıp beklemeye geçildiğinde true.
 */
data class TransferProgress(
    val totalItems: Int,
    val completedItems: Int,
    val failedItems: Int,
    val totalBytes: Long,
    val completedBytes: Long,
    val bytesPerSecond: Long,
    val etaMillis: Long?,
    val currentItem: SyncItem?,
    val currentItemBytes: Long,
    val startedAtMillis: Long,
    val isPaused: Boolean = false,
) {
    val remainingItems: Int get() = (totalItems - completedItems - failedItems).coerceAtLeast(0)
    val fraction: Float get() = if (totalBytes > 0) (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    companion object {
        fun initial(items: List<SyncItem>, startedAtMillis: Long) = TransferProgress(
            totalItems = items.size, completedItems = 0, failedItems = 0,
            totalBytes = items.sumOf { it.size }, completedBytes = 0, bytesPerSecond = 0,
            etaMillis = null, currentItem = null, currentItemBytes = 0, startedAtMillis = startedAtMillis,
        )
    }
}

enum class TransferOutcome {
    /** Tüm seçili öğeler kopyalandı. */
    COMPLETED,
    /** Bitti ama bazı öğeler (dosya bazlı, ölümcül olmayan hatalarla) başarısız. */
    COMPLETED_WITH_ERRORS,
    /** Kullanıcı iptal etti (TransferControl.cancel). */
    CANCELLED,
    /** Ölümcül hata (kart çıktı, izin gitti, alan bitti…) nedeniyle durdu; [TransferSummary.fatalError] dolu. */
    ABORTED,
}

/** Tek öğenin başarısızlığı. */
data class ItemFailure(val itemId: String, val targetPath: String, val error: SyncError)

/**
 * Aktarım sonucu. [copiedItems] yalnızca .part→ad değişikliği ve boyut doğrulaması başarıyla biten
 * dosyalardır. [indexSaved] false ise bu kopyalar indekse yazılamamıştır (sonraki taramada hedef
 * taraması ALREADY_ON_TARGET ile yine tekrar kopyalamayı önler).
 */
data class TransferSummary(
    val outcome: TransferOutcome,
    val totalItems: Int,
    val copiedItems: Int,
    val copiedBytes: Long,
    val failures: List<ItemFailure>,
    val notAttempted: Int,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val fatalError: SyncError? = null,
    val indexSaved: Boolean,
) {
    val elapsedMillis: Long get() = (finishedAtMillis - startedAtMillis).coerceAtLeast(0)
}
