// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.model

import com.walkmansync.core.index.ContentKey
import com.walkmansync.core.index.SyncIndex
import java.util.Locale

/** Bir dosyanın neden kopyalanacağı. */
enum class CopyReason {
    /** Yapılı klasörden yeni dosya; göreli yapı korunarak yerleştirildi. */
    NEW,
    /** Düz klasörden yeni dosya; etiketlere göre /MUSIC/{Artist}/{Album}/ altına yerleştirildi. */
    NEW_TAG_PLACED,
    /** Yardımcı dosya (.lrc / cover.jpg); bağlı olduğu ses dosyasını izler. */
    COMPANION,
    /** Normalde atlanacaktı; kullanıcı açıkça seçti ([SkippedItem.forceable]). */
    FORCED,
}

/**
 * Kopyalanmayacak dosyanın nedeni. "Zorlanabilir" olanlarda [SkippedItem.forceable] dolu olabilir.
 */
enum class SkipReason(val forceableByDefault: Boolean) {
    /** İndekste aynı kaynak yolu + boyut kaydı var. */
    ALREADY_SYNCED(false),
    /** İndekste aynı içerik anahtarı (sanatçı/albüm/disk/parça/başlık/süre) var — başka yoldan gelmiş kopya. */
    DUPLICATE_CONTENT(true),
    /** Hedefte planlanan yolda aynı boyutta dosya var ya da MUSIC altında aynı ad + boyutta dosya var. */
    ALREADY_ON_TARGET(false),
    /** Planlanan yolda (büyük/küçük harf duyarsız) FARKLI boyutta dosya var; append-only → üzerine yazılmaz. */
    TARGET_PATH_OCCUPIED(true),
    /** İndekste aynı kaynak yolu var ama boyut değişmiş; hedefteki eski sürüm korunur. */
    CHANGED_SINCE_SYNC(true),
    /** İndeks kaydı var ama hedef dosya kartta yok (kullanıcı Walkman'den silmiş); tekrar kopyalanmaz. */
    REMOVED_FROM_TARGET(true),
    /** Bu taramada aynı içerik anahtarlı başka bir kaynak dosya zaten planlandı. */
    DUPLICATE_IN_SOURCE(true),
    /** Hedef FAT32 (ya da bilinmiyor) ve dosya > 4 GiB - 1. FAT32'de zorlanamaz, UNKNOWN'da zorlanabilir. */
    TOO_LARGE_FOR_FAT32(false),
    /** Kaynak boyutu bilinmiyor (-1). */
    UNKNOWN_SIZE(false),
    /** 0 baytlık dosya. */
    EMPTY_FILE(false),
    /** Düz klasördeki .lrc/cover.jpg; eşleşen ses dosyası yok ve hedefte de bağlanacak yer yok. */
    ORPHAN_AUXILIARY(false),
}

/**
 * Plandaki tek kopyalama işi.
 *
 * @property id Plan içinde benzersiz, kararlı kimlik = [source].relativePath (UI seçimi bununla yapılır).
 * @property targetPath Hedef volume köküne göre tam yol ("MUSIC/…"). Temizlenmiş (sanitize),
 *   [TargetLayout.isValidTargetFilePath] koşulunu sağlar ve plandaki TÜM öğeler + zorlanabilir
 *   öğeler arasında büyük/küçük harf duyarsız benzersizdir.
 * @property tags Okunduysa etiketler (UI gösterimi + indeks içerik anahtarı).
 * @property contentKey İndekse yazılacak içerik anahtarı; yardımcı dosyalarda null.
 * @property companionOfId Yardımcı dosyanın bağlı olduğu ses öğesinin [id]'si; o öğe bu aktarımda
 *   BAŞARISIZ olursa yardımcı dosya da atlanır. Ses zaten hedefteyse null.
 */
data class SyncItem(
    val id: String,
    val source: SourceFile,
    val targetPath: String,
    val reason: CopyReason,
    val format: AudioFormat,
    val tags: AudioTags? = null,
    val contentKey: ContentKey? = null,
    val companionOfId: String? = null,
) {
    init {
        require(id == source.relativePath) { "SyncItem.id kaynak yolu olmalı: $id" }
        require(TargetLayout.isValidTargetFilePath(targetPath)) { "Geçersiz hedef yol: $targetPath" }
        require(source.size >= 0) { "Boyutu bilinmeyen dosya planlanamaz: ${source.relativePath}" }
    }

    val size: Long get() = source.size
    val isAuxiliary: Boolean get() = format.isAuxiliary
    val targetName: String get() = targetPath.substringAfterLast('/')
    val targetDir: String get() = targetPath.substringBeforeLast('/')
}

/**
 * Atlanan dosya. [forceable] doluysa kullanıcı seçebilir; seçildiğinde o öğe ([CopyReason.FORCED]) kopyalanır.
 */
data class SkippedItem(
    val source: SourceFile,
    val reason: SkipReason,
    val detail: String? = null,
    val forceable: SyncItem? = null,
) {
    val id: String get() = source.relativePath
}

/** Planla birlikte UI'ya gösterilecek uyarılar. */
enum class PlanWarning {
    /** Karttaki indeks başka bir volume UUID'si için yazılmış (klonlanmış kart); yok sayıldı. */
    FOREIGN_INDEX_IGNORED,
    /** Ana indeks bozuktu, yedekten (.bak) kurtarıldı. */
    INDEX_RECOVERED_FROM_BACKUP,
    /** İndeks ve yedeği okunamadı; boş indeksle başlandı (hedef taraması tekrarları yine önler). */
    INDEX_RESET_CORRUPT,
    /** Varsayılan seçimin toplam boyutu hedefteki boş alandan büyük. */
    INSUFFICIENT_SPACE_FOR_ALL,
    /** Hedef dosya sistemi tespit edilemedi; 4 GiB üstü dosyalar zorlanabilir atlandı. */
    TARGET_FS_UNKNOWN,
}

/**
 * Fark motorunun çıktısı. Değişmezdir; aktarım bitince (başarılı/iptal/hata) GEÇERSİZ sayılır ve
 * yeni aktarım için yeniden tarama gerekir.
 *
 * @property baseIndex Planın hesaplandığı indeks; aktarım yeni kayıtları bunun üzerine ekler.
 * @property sourceAudioCount Kaynakta bulunan desteklenen SES dosyası sayısı (istatistik).
 */
data class SyncPlan(
    val pair: VolumePair,
    val createdAtMillis: Long,
    val items: List<SyncItem>,
    val skipped: List<SkippedItem>,
    val baseIndex: SyncIndex,
    val targetFileSystem: TargetFileSystem,
    val targetFreeBytes: Long?,
    val warnings: Set<PlanWarning> = emptySet(),
    val sourceAudioCount: Int = 0,
) {
    init {
        require(baseIndex.targetVolume == pair.target) { "Plan indeksi hedef volume'a ait değil" }
        val all = items.map { it.id } + skipped.mapNotNull { s -> s.forceable?.id }
        require(all.size == all.toSet().size) { "Plan öğe kimlikleri benzersiz değil" }
        val paths = items.map { it.targetPath.lowercase(Locale.ROOT) } +
            skipped.mapNotNull { it.forceable?.targetPath?.lowercase(Locale.ROOT) }
        require(paths.size == paths.toSet().size) { "Plan hedef yolları (büyük/küçük harf duyarsız) benzersiz değil" }
    }

    /** UI'nın başlangıç seçimi: tüm [items]. */
    val defaultSelection: Set<String> get() = items.mapTo(LinkedHashSet()) { it.id }

    val totalBytes: Long get() = items.sumOf { it.size }

    /** Seçilebilir tüm öğeler (normal + zorlanabilir), plan sırasıyla. */
    val selectable: List<SyncItem> get() = items + skipped.mapNotNull { it.forceable }

    /**
     * UI seçimini aktarılacak öğe listesine çevirir. Bilinmeyen kimlikler yok sayılır. Sıra: önce
     * [items] sırası, sonra zorlanan öğeler. Bir yardımcı dosyanın bağlı olduğu ses seçilmemişse
     * [SyncItem.companionOfId] temizlenir (yardımcı tek başına kopyalanır).
     */
    fun resolveSelection(selectedIds: Set<String>): List<SyncItem> {
        val chosen = selectable.filter { it.id in selectedIds }
        val chosenIds = chosen.mapTo(HashSet()) { it.id }
        return chosen.map { item ->
            if (item.companionOfId != null && item.companionOfId !in chosenIds) item.copy(companionOfId = null) else item
        }
    }
}
