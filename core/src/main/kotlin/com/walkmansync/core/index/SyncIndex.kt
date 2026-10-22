// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.index

import com.walkmansync.core.fs.TargetTree
import com.walkmansync.core.model.VolumeId
import java.util.Locale

/**
 * Hedef ağaç kökündeki `.walkman_sync_index.json` içeriği (yol: `TargetLayout.indexPathFor`). DEĞİŞMEZDİR; ekleme [withEntries] ile yeni
 * örnek üretir. Append-only: [entries] yalnızca büyür (aynı kaynak yolu için yeni kayıt eskisinin YERİNE
 * geçmez, listeye eklenir; aramalarda EN SON kayıt kazanır). Yalnızca [targetKeys] bir önbellektir ve
 * budanabilir.
 *
 * JSON şeması (sürüm 1) — anahtar adları SABİTTİR, codec (B2) bunları birebir kullanır:
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "app": "WalkmanSync",
 *   "targetVolume": "ABCD-1234",
 *   "lastSourceVolume": "1A2B-3C4D",          // null olabilir
 *   "createdAt": 1767225600000,
 *   "updatedAt": 1767225600000,
 *   "entries": [
 *     { "src": "Download/a.flac", "srcSize": 31457280, "srcMtime": 1767225600000,
 *       "dst": "MUSIC/Artist/Album/01 - A.flac", "dstSize": 31457280,
 *       "key": "v1|artist|album|1|1|a|120",       // null olabilir
 *       "at": 1767225600000 }
 *   ],
 *   "targetKeys": [
 *     { "dst": "MUSIC/Old/x.mp3", "dstSize": 4000000, "key": "v1|…" }   // key null olabilir
 *   ]
 * }
 * ```
 * Codec kuralları: bilinmeyen anahtarlar YOK SAYILIR (ileri uyum); eksik zorunlu alan → [IndexFormatException];
 * sayılar Long; dizeler JSON kaçışlarıyla (\uXXXX dahil, vekil çiftler) UTF-8.
 */
data class SyncIndex(
    val schemaVersion: Int,
    val targetVolume: VolumeId,
    val lastSourceVolume: VolumeId?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val entries: List<IndexEntry>,
    val targetKeys: List<TargetKeyEntry>,
) {
    /** Kaynak yolu (büyük/küçük harf duyarsız) → en son kayıt. */
    private val bySourcePath: Map<String, IndexEntry> by lazy {
        val m = HashMap<String, IndexEntry>(entries.size * 2)
        for (e in entries) m[foldPath(e.sourcePath)] = e
        m
    }

    /** Hedef yolu (büyük/küçük harf duyarsız) → kayıt. */
    private val byTargetPath: Map<String, IndexEntry> by lazy {
        val m = HashMap<String, IndexEntry>(entries.size * 2)
        for (e in entries) m[foldPath(e.targetPath)] = e
        m
    }

    /** İçerik anahtarı → hedef yolları (kayıtlar + önbellek). */
    private val byContentKey: Map<ContentKey, List<String>> by lazy {
        val m = HashMap<ContentKey, MutableList<String>>()
        for (e in entries) e.contentKey?.let { m.getOrPut(it) { ArrayList(1) }.add(e.targetPath) }
        for (t in targetKeys) t.contentKey?.let { m.getOrPut(it) { ArrayList(1) }.add(t.targetPath) }
        m
    }

    private val targetKeyByPath: Map<String, TargetKeyEntry> by lazy {
        targetKeys.associateBy { foldPath(it.targetPath) }
    }

    fun findBySourcePath(sourcePath: String): IndexEntry? = bySourcePath[foldPath(sourcePath)]

    fun findByTargetPath(targetPath: String): IndexEntry? = byTargetPath[foldPath(targetPath)]

    /** [key] ya da süre-kovası komşularıyla eşleşen hedef yolları (bkz. [ContentKey.candidates]). */
    fun targetsWithContent(key: ContentKey): List<String> =
        key.candidates().flatMap { byContentKey[it].orEmpty() }.distinct()

    /** Hedef dosyası için önbellekteki etiket anahtarı; yol + boyut eşleşmezse null (önbellek geçersiz). */
    fun cachedTargetKey(targetPath: String, size: Long): TargetKeyEntry? =
        targetKeyByPath[foldPath(targetPath)]?.takeIf { it.targetSize == size }

    /** Yeni kayıtları sona ekler. */
    fun withEntries(added: List<IndexEntry>, nowMillis: Long, sourceVolume: VolumeId?): SyncIndex =
        copy(entries = entries + added, updatedAtMillis = nowMillis, lastSourceVolume = sourceVolume ?: lastSourceVolume)

    /** Önbelleği değiştirir (fark motoru: hedefte artık bulunmayanları budar, yeni okunanları ekler). */
    fun withTargetKeys(keys: List<TargetKeyEntry>): SyncIndex = copy(targetKeys = keys)

    companion object {
        const val CURRENT_SCHEMA = 1
        const val APP_ID = "WalkmanSync"

        /** Okunacak azami indeks dosyası boyutu; aşan dosya bozuk sayılır. */
        const val MAX_FILE_BYTES: Long = 64L * 1024 * 1024

        fun empty(target: VolumeId, nowMillis: Long): SyncIndex =
            SyncIndex(CURRENT_SCHEMA, target, null, nowMillis, nowMillis, emptyList(), emptyList())

        /**
         * Yol karşılaştırma katlaması: NFC (macOS/iOS kaynaklı NFD adlar) + küçük harf (FAT/exFAT büyük/küçük
         * harf duyarsızdır). Tüm yol karşılaştırmaları (delta, çakışma, indeks) bunu kullanır.
         */
        fun foldPath(path: String): String =
            java.text.Normalizer.normalize(path.trim('/'), java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}

/**
 * Bu uygulamanın kopyaladığı bir dosyanın kaydı.
 *
 * @property sourcePath Kaynak volume köküne göre yol ("Download/a.flac").
 * @property targetPath Hedefteki GERÇEK yol (ensureDirectory'nin döndürdüğü harf büyüklüğüyle).
 */
data class IndexEntry(
    val sourcePath: String,
    val sourceSize: Long,
    val sourceModified: Long,
    val targetPath: String,
    val targetSize: Long,
    val contentKey: ContentKey?,
    val syncedAtMillis: Long,
)

/** Uygulama dışından hedefe konmuş ses dosyasının önbelleklenmiş içerik anahtarı. */
data class TargetKeyEntry(
    val targetPath: String,
    val targetSize: Long,
    val contentKey: ContentKey?,
)

/** JSON biçim hatası (codec fırlatır). */
class IndexFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Saf Kotlin JSON codec (B2: `JsonIndexCodec`). Thread-safe, durumsuz. kotlinx.serialization YOK.
 */
interface IndexCodec {
    /** Kararlı çıktı (anahtar sırası yukarıdaki şemadaki gibi), UTF-8 yazılmak üzere. */
    fun encode(index: SyncIndex): String

    /**
     * @throws IndexFormatException biçim bozuk / zorunlu alan eksik / `schemaVersion` sayı değil.
     *   `schemaVersion` > [SyncIndex.CURRENT_SCHEMA] ise ayrıştırma DENENMEZ, [UnsupportedIndexVersionException].
     */
    fun decode(json: String): SyncIndex
}

class UnsupportedIndexVersionException(val schemaVersion: Int) :
    Exception("Desteklenmeyen indeks şeması: $schemaVersion")

/** [IndexStore.load] sonucu. */
sealed interface IndexLoadResult {
    /** Geçerli ve bu hedefe ait. [recoveredFromBackup]: ana dosya bozuk/eksikti, `.bak` kullanıldı. */
    data class Loaded(val index: SyncIndex, val recoveredFromBackup: Boolean) : IndexLoadResult

    /** Ne ana dosya ne yedek var (ilk kullanım). */
    data object Missing : IndexLoadResult

    /** Okunabildi ama `targetVolume` farklı (kart klonlanmış/başka karttan kopyalanmış). Yok sayılmalı. */
    data class Foreign(val foundVolume: String) : IndexLoadResult

    /** Ana dosya ve yedek bozuk. */
    data class Corrupt(val detail: String) : IndexLoadResult

    /** Daha yeni şema; bu sürüm dokunmamalı. */
    data class Unsupported(val schemaVersion: Int) : IndexLoadResult
}

/**
 * İndeksin hedef karttaki kalıcılığı. Uygulama (B1): `TargetIndexStore(codec: IndexCodec, logger)` —
 * core içinde, yalnızca [TargetTree] üzerinden.
 *
 * Atomik yazma protokolü ([save]):
 * (Tüm yollar `TargetLayout.index*PathFor(tree.root.relativePath)` ile.)
 *  1. Artık `INDEX.wsync.part` varsa sil; `tree.createIndexPart()`; UTF-8 yaz; `sync()`; kapat.
 *  2. Boyutu `resolve` ile doğrula (uyuşmazsa .part'ı sil, IOException).
 *  3. INDEX varsa: varsa `INDEX.bak`'ı sil → `INDEX`'i `INDEX.bak` yap.
 *  4. `INDEX.wsync.part`'ı `INDEX` yap.
 *  Herhangi bir adımda kopma: [load] sırası INDEX → INDEX.bak olduğundan her an en az bir geçerli sürüm kalır.
 *
 * Okuma ([load]): INDEX → (bozuk/yoksa) INDEX.bak. `targetVolume` != tree.volume → Foreign. Dosya
 * [SyncIndex.MAX_FILE_BYTES]'tan büyükse bozuk sayılır.
 *
 * Eşzamanlılık: Çağıranlar (ScanWorkflow, TransferRunner) aynı anda çalışmaz (SyncCoordinator kilidi);
 * uygulama yine de örnek başına bir `Mutex` ile kaydetmeleri sıralar.
 * Hatalar: birim hataları SyncException (VolumeMissing/PermissionLost/IoFailure) olarak yayılır.
 */
interface IndexStore {
    suspend fun load(tree: TargetTree): IndexLoadResult
    suspend fun save(tree: TargetTree, index: SyncIndex)
}
