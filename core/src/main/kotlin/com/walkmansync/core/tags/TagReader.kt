// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.tags

import com.walkmansync.core.fs.RandomAccessSource
import com.walkmansync.core.fs.ReadableTree
import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.model.FileEntry
import com.walkmansync.core.model.NodeHandle

/**
 * Bir biçim ailesi için saf Kotlin etiket okuyucu (B2: `com.walkmansync.core.tags.parsers` paketindeki
 * ayrıştırıcılar ve hepsini biçime göre yönlendiren `parsers.CompositeTagReader`).
 *
 * Kurallar:
 *  - BLOKLAYICIDIR (suspend değil); yalnızca IO dispatcher'da çağrılır.
 *  - [source]'u KAPATMAZ (sahibi çağıran). Konuma göre okur, durum tutmaz → thread-safe.
 *  - Bozuk/desteklenmeyen veride FIRLATMAZ, `null` ya da kısmi sonuç döner. Yalnızca
 *    [java.io.IOException] (G/Ç, kart çıktı) yayılır.
 *  - Büyük blokları (gömülü kapak, PADDING) okumadan atlar; dosya başına okunan toplam bayt
 *    [MAX_BYTES_READ] ile sınırlıdır. Uzunluk alanları [RandomAccessSource.size] ile doğrulanır.
 *  - Metin `trim()` edilir, boş → null. "3/12" → 3. Metin kodlamaları: ID3 (ISO-8859-1, UTF-16 BOM'lu/BOM'suz,
 *    UTF-8), Vorbis/MP4 (UTF-8).
 *
 * Kapsam (CompositeTagReader): MP3 (ID3v2.2/2.3/2.4 + ID3v1 + Xing/VBRI/CBR süre), FLAC (VORBIS_COMMENT +
 * STREAMINFO süre), M4A/ALAC (moov/udta/meta/ilst + mvhd süre), OGG Vorbis/OPUS (comment header + son
 * granule süre; Opus pre-skip), WAV (LIST/INFO + "id3 " chunk + fmt/data süre), DSF (ID3v2 işaretçisi +
 * süre), DFF (ID3 chunk / DIIN + süre), AAC/ADTS (ID3v2 başlık + kare sayımıyla yaklaşık süre).
 * LRC/M3U8/COVER için `null`.
 */
interface TagReader {
    /** Bu okuyucunun desteklediği biçimler. */
    val formats: Set<AudioFormat>

    fun read(source: RandomAccessSource, format: AudioFormat): AudioTags?

    companion object {
        /** Tek dosya için okunacak azami bayt. */
        const val MAX_BYTES_READ: Long = 4L * 1024 * 1024
    }
}

/**
 * Platform yedek okuyucusu (app: `com.walkmansync.media.MediaMetadataTagFallback(app, logger)` —
 * `MediaMetadataRetriever` + `MediaDataSource`, B2). Saf ayrıştırıcı null ya
 * da eksik (başlık VEYA süre yok) sonuç verdiğinde çağrılır. BLOKLAYICI; [source]'u kapatmaz; fırlatmaz.
 */
fun interface TagFallback {
    fun read(source: RandomAccessSource, format: AudioFormat): AudioTags?

    companion object {
        val None: TagFallback = TagFallback { _, _ -> null }
    }
}

/**
 * Fark motorunun kullandığı etiket okuma yüzeyi. Uygulama: `com.walkmansync.core.domain.DefaultTagService` (B2).
 *
 * Davranış:
 *  1. `tree.openRandomAccess(file.handle).use { }` — kaynağı servis açar ve kapatır.
 *  2. [TagReader] → eksikse [TagFallback] → `primary.mergedWith(fallback)`.
 *  3. Ses olmayan biçimde ya da hiçbir alan okunamazsa `null`.
 *
 * Hatalar: Dosyaya özgü her hata (bozuk veri, IOException, SourceGone) yutulur → `null` (günlüğe yazılır).
 * Yalnızca `CancellationException` ve ölümcül birim hataları (SyncException: VolumeMissing, PermissionLost)
 * yayılır. Eşzamanlı çağrılabilir (thread-safe).
 */
interface TagService {
    /** Kaynak ve hedef dosyalar için ortak (hedef: fark motoru 5. adım). */
    suspend fun <H : NodeHandle> read(tree: ReadableTree<H>, file: FileEntry<H>): AudioTags?
}
