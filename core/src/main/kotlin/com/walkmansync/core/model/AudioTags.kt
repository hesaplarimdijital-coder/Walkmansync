// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.model

/**
 * Bir ses dosyasından okunan etiketler. Tüm alanlar isteğe bağlıdır; ayrıştırıcılar boş/yalnızca
 * boşluktan oluşan değerleri `null` olarak döndürür ve metni `trim()` eder (başka normalizasyon YAPMAZ;
 * karşılaştırma için normalizasyon [com.walkmansync.core.index.ContentKey] içindedir).
 *
 * @property trackNumber "3/12" gibi değerlerde yalnızca 3. Sıfır/negatif → null.
 * @property discNumber "1/2" → 1. Sıfır/negatif → null.
 * @property durationMs Akış başlıklarından hesaplanan süre (ms); hesaplanamazsa null.
 */
data class AudioTags(
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val title: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long? = null,
) {
    /** Klasörleme için sanatçı: albüm sanatçısı öncelikli (derleme albümleri tek klasörde kalsın). */
    val folderArtist: String? get() = albumArtist ?: artist

    val isEmpty: Boolean
        get() = artist == null && albumArtist == null && album == null && title == null &&
            trackNumber == null && discNumber == null && durationMs == null

    /**
     * Eksik alanları [other] ile doldurur (bu nesnedeki dolu alanlar korunur). Ayrıştırıcı + yedek
     * okuyucu sonuçlarını birleştirmek için.
     */
    fun mergedWith(other: AudioTags?): AudioTags = if (other == null) this else AudioTags(
        artist = artist ?: other.artist,
        albumArtist = albumArtist ?: other.albumArtist,
        album = album ?: other.album,
        title = title ?: other.title,
        trackNumber = trackNumber ?: other.trackNumber,
        discNumber = discNumber ?: other.discNumber,
        durationMs = durationMs ?: other.durationMs,
    )
}
