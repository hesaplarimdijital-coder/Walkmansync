// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
// Normalizasyon indeks dosyasına KALICI olarak yazılır; davranış değişirse VERSION artırılmalıdır.
package com.walkmansync.core.index

import com.walkmansync.core.model.AudioTags
import java.text.Normalizer
import java.util.Locale

/**
 * Etiket tabanlı tekilleştirme anahtarı:
 * `v1|<sanatçı>|<albüm>|<disk>|<parça>|<başlık>|<süreKovası>`
 *
 * - sanatçı = [AudioTags.folderArtist] (albüm sanatçısı ?: sanatçı), albüm boşsa "".
 * - disk/parça yoksa 0.
 * - süre kovası = round(durationMs / [DURATION_BUCKET_MS]); süre yoksa "-".
 * - Sanatçı VEYA başlık yoksa anahtar ÜRETİLMEZ (yanlış pozitif riski) → [of] null döner.
 *
 * Eşleştirme: kova sınırına düşen süreler için [candidates] komşu kovaları (k-1, k, k+1) da üretir;
 * indeks araması bu adaylardan herhangi biriyle yapılır.
 */
@JvmInline
value class ContentKey(val value: String) {
    override fun toString(): String = value

    /** Bu anahtar ve süre kovası ±1 olan komşuları (süre yoksa yalnızca kendisi). */
    fun candidates(): List<ContentKey> {
        val cut = value.lastIndexOf('|')
        if (cut < 0) return listOf(this)
        val bucket = value.substring(cut + 1).toLongOrNull() ?: return listOf(this)
        val head = value.substring(0, cut + 1)
        return listOf(this, ContentKey(head + (bucket - 1)), ContentKey(head + (bucket + 1)))
    }

    companion object {
        const val VERSION = "v1"
        const val DURATION_BUCKET_MS = 2_000L

        private val COMBINING = Regex("\\p{M}+")
        private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

        fun of(tags: AudioTags?): ContentKey? {
            if (tags == null) return null
            val artist = normalize(tags.folderArtist) ?: return null
            val title = normalize(tags.title) ?: return null
            val album = normalize(tags.album) ?: ""
            val disc = tags.discNumber?.takeIf { it > 0 } ?: 0
            val track = tags.trackNumber?.takeIf { it > 0 } ?: 0
            val bucket = tags.durationMs?.takeIf { it > 0 }
                ?.let { ((it + DURATION_BUCKET_MS / 2) / DURATION_BUCKET_MS).toString() } ?: "-"
            return ContentKey("$VERSION|$artist|$album|$disc|$track|$title|$bucket")
        }

        /**
         * Karşılaştırma normalizasyonu: NFKD → birleşik işaretleri (aksan) sil → Türkçe 'ı'→'i' →
         * küçük harf (Locale.ROOT) → harf/rakam dışı her dizi tek boşluk → trim. Boş → null.
         * "Beyoncé" ≡ "beyonce", "AC/DC" ≡ "ac dc", "Işık" ≡ "isik".
         */
        fun normalize(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD)
            val s = COMBINING.replace(decomposed, "")
                .replace('ı', 'i').replace('İ', 'I')
                .lowercase(Locale.ROOT)
            return NON_ALNUM.replace(s, " ").trim().ifEmpty { null }
        }
    }
}
