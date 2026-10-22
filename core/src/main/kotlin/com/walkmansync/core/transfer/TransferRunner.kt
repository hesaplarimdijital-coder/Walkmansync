// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.transfer

import com.walkmansync.core.fs.SourceTree
import com.walkmansync.core.fs.TargetTree
import com.walkmansync.core.index.SyncIndex
import com.walkmansync.core.model.SyncItem
import com.walkmansync.core.model.SyncPlan
import com.walkmansync.core.model.TransferProgress
import com.walkmansync.core.model.TransferSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Aktarım ayarları (varsayılanlar bağlayıcıdır; testler küçültebilir). */
data class TransferConfig(
    /** Kopya tamponu. */
    val bufferSize: Int = 256 * 1024,
    /** İndeks her N başarılı dosyada bir kaydedilir… */
    val indexFlushEvery: Int = 10,
    /** …ya da son kayıttan bu yana bu kadar ms geçtiyse (hangisi önce gelirse). */
    val indexFlushIntervalMs: Long = 30_000,
    /** İlerleme yayın aralığı (en sık). */
    val progressIntervalMs: Long = 200,
    /** Hız hesabı kayan penceresi. */
    val speedWindowMs: Long = 5_000,
    /** Her dosyadan önce boş alan ≥ dosya boyutu + bu pay olmalı. */
    val freeSpaceMarginBytes: Long = 8L * 1024 * 1024,
) {
    init {
        require(bufferSize >= 4096 && indexFlushEvery >= 1 && progressIntervalMs >= 0 && speedWindowMs > 0)
    }
}

/**
 * @property items `plan.resolveSelection(selection)` sonucu, bu sırayla kopyalanır.
 * @property baseIndex Genellikle `plan.baseIndex`; yeni kayıtlar bunun üzerine eklenir.
 */
data class TransferRequest(
    val plan: SyncPlan,
    val items: List<SyncItem>,
    val source: SourceTree,
    val target: TargetTree,
    val baseIndex: SyncIndex = plan.baseIndex,
    val config: TransferConfig = TransferConfig(),
) {
    init {
        require(source.volume == plan.pair.source) { "Kaynak ağaç plandaki kaynak volume'a ait değil" }
        require(target.volume == plan.pair.target) { "Hedef ağaç plandaki hedef volume'a ait değil" }
    }
}

/** Aktarım akışının olayları. */
sealed interface TransferEvent {
    data class Progress(val progress: TransferProgress) : TransferEvent

    /** Her zaman SON olaydır ve tam bir kez yayılır (toplayıcı iptal edilmedikçe). */
    data class Finished(val summary: TransferSummary) : TransferEvent
}

/**
 * Duraklat/devam/iptal kontrolü. Thread-safe; herhangi bir thread'den çağrılabilir. Bir aktarım başına
 * yeni örnek oluşturulur (yeniden kullanılmaz).
 */
class TransferControl {
    private val _paused = MutableStateFlow(false)
    private val _cancelled = MutableStateFlow(false)

    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    val cancelled: StateFlow<Boolean> = _cancelled.asStateFlow()

    fun pause() {
        if (!_cancelled.value) _paused.value = true
    }

    fun resume() {
        _paused.value = false
    }

    /** İptal duraklatmayı da çözer (bekleyen runner uyanıp temizlik yapsın diye). */
    fun cancel() {
        _cancelled.value = true
        _paused.value = false
    }

    /** Duraklatılmışsa devam/iptal edilene kadar askıda kalır. Dönüşte iptal edildiyse true. */
    suspend fun awaitResumedOrCancelled(): Boolean {
        if (_paused.value) _paused.first { !it }
        return _cancelled.value
    }
}

/**
 * Atomik aktarım motoru. Uygulama (B1): `AtomicTransferRunner(indexStore: IndexStore, clock: Clock,
 * dispatchers: AppDispatchers, logger: Logger)`, core.
 *
 * [run] SOĞUK bir akış döndürür; toplama aktarımı başlatır. Toplayıcı tektir.
 *
 * Başlangıç:
 *  - Seçimin toplamı > `target.freeBytes()` (biliniyorsa) → hiçbir şey kopyalanmadan
 *    Finished(ABORTED, fatalError = InsufficientSpace).
 *
 * Her öğe için (sırayla, tek dosya aynı anda):
 *  1. `control.awaitResumedOrCancelled()`; iptalse döngü biter (CANCELLED).
 *  2. `item.companionOfId` başarısız olduysa öğe atlanır (`notAttempted`'e sayılır).
 *  3. `target.fileSystem().maxFileSize` < size → ItemFailure(FileTooLargeForFat32).
 *     Boş alan < size + freeSpaceMarginBytes → ölümcül InsufficientSpace.
 *  4. `dir = target.ensureDirectory(item.targetDir)` (GERÇEK yol döner). `target.list(dir)` içinde son ad
 *     (büyük/küçük harf duyarsız) varsa → ItemFailure(TargetExists) (ASLA üzerine yazılmaz). Aynı adlı
 *     `.wsync.part` artığı varsa önce silinir.
 *  5. `part = target.createFile(dir, partNameFor(name))`; `source.openInput` + `target.openOutput(part)`
 *     ikisi de `use { }` ile; tampon döngüsü; her tamponda iptal/duraklat kontrolü (`ensureActive()`).
 *     Duraklatmada: akışlar kapatılır, `.part` silinir, bekleme; devamda dosya BAŞTAN yazılır.
 *  6. `sync()`; kapat; okunan bayt == `item.size` değilse SourceGone/SizeMismatch; `target.resolve(partPath)`
 *     boyutu == item.size değilse SizeMismatch.
 *  7. `target.rename(part, name)`; hata (TargetExists dahil) → `.part` silinir, ItemFailure.
 *  8. IndexEntry(src yolu/boyut/mtime, GERÇEK hedef yol, boyut, item.contentKey, clock.nowMillis()) biriktirilir;
 *     TransferConfig'e göre `indexStore.save(target, base.withEntries(...))`.
 *
 * Hata sınıflandırması: IOException/SyncException(IoFailure) sonrası `target.isAvailable()` ve
 * `source.isAvailable()` sorgulanır; biri false → ölümcül VolumeMissing (o rol). Değilse dosyaya özgü
 * (ItemFailure, devam). PermissionLost, InsufficientSpace (ENOSPC) daima ölümcül.
 *
 * Bitiş (normal, iptal, ölümcül — hepsinde, `withContext(NonCancellable)` içinde):
 *  - Açık `.part` silinir (hedef erişilebilirse; değilse bir sonraki taramada temizlenir).
 *  - Biriken kayıtlar indekse kaydedilir (hedef erişilebilirse); başarısızlık → `indexSaved = false`.
 *  - Finished(summary) yayılır. Toplayıcı (coroutine) iptal edilirse temizlik yine yapılır, Finished yayılmaz.
 *
 * İlerleme: en sık `progressIntervalMs`'de bir + her dosya bitiminde; hız `speedWindowMs` kayan pencere,
 * ETA = kalan bayt / hız. Duraklatılmışken `isPaused = true` ilerleme yayılır.
 * Thread: G/Ç `dispatchers.io`'da (akış `flowOn` ya da `channelFlow` + withContext ile); toplayıcı herhangi bir
 * dispatcher'da olabilir.
 */
interface TransferRunner {
    fun run(request: TransferRequest, control: TransferControl): Flow<TransferEvent>
}
