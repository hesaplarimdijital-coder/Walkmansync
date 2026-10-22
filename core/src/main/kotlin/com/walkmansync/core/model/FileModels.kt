// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.model

import java.util.Locale

/**
 * Bir ağaçtaki düğümün opak tutamacı. SAF uygulamasında değer SAF belge kimliğidir
 * ("UUID:göreli/yol"); testlerde yol olabilir. Değerin içeriğine core içinde ANLAM YÜKLENMEZ
 * (yalnızca SAF uygulaması ve [DocumentIds] ile doğrulama yapan kod yorumlar).
 *
 * Kaynak ve hedef tutamaçları AYRI tiplerdir: bir kaynak tutamacı hedef ağaca (ve tersi)
 * derleme zamanında verilemez.
 */
sealed interface NodeHandle {
    val id: String
}

@JvmInline
value class SourceHandle(override val id: String) : NodeHandle {
    override fun toString(): String = "src:$id"
}

@JvmInline
value class TargetHandle(override val id: String) : NodeHandle {
    override fun toString(): String = "dst:$id"
}

/**
 * Ağaç taramasıyla bulunan bir DOSYA (dizinler FileEntry olmaz).
 *
 * @property relativePath Birimin (volume) KÖKÜNE göre yol, '/' ayraçlı, baş/son '/' yok:
 *   kaynakta "Download/a.flac", hedefte "MUSIC/Artist/Album/01 - T.flac". Kullanıcının seçtiği
 *   ağaç kökü bir alt klasör olsa bile yol volume köküne göredir (SAF docId'sinden gelir), bu sayede
 *   kullanıcı kaynak klasörünü yeniden seçse de indeks anahtarları değişmez.
 * @property name Görünen ad (relativePath'in son parçası).
 * @property size Bayt; bilinmiyorsa -1 (SAF COLUMN_SIZE null). -1 olan dosya planlanmaz.
 * @property lastModified Epoch milisaniye; bilinmiyorsa 0.
 */
data class FileEntry<out H : NodeHandle>(
    val handle: H,
    val relativePath: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
) {
    /** Uzantıya/ada göre tanınan biçim; desteklenmiyorsa `null`. */
    val format: AudioFormat? get() = AudioFormat.fromFileName(name)

    /** relativePath'in dizin kısmı ("Download/a.flac" → "Download"; kökteki dosya → ""). */
    val parentPath: String get() = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
}

typealias SourceFile = FileEntry<SourceHandle>
typealias TargetFile = FileEntry<TargetHandle>

/**
 * Desteklenen ses ve yardımcı dosya biçimleri. Eşleşme büyük/küçük harf duyarsızdır.
 * [COVER] uzantıyla değil TAM dosya adıyla ("cover.jpg") eşleşir; başka .jpg dosyaları desteklenmez.
 */
enum class AudioFormat(
    val extensions: Set<String>,
    val isAudio: Boolean,
    /** Bilgi amaçlı MIME. Hedefte `.part` oluştururken KULLANILMAZ (bkz. TargetTree.createFile). */
    val mimeType: String,
) {
    FLAC(setOf("flac"), true, "audio/flac"),
    DSF(setOf("dsf"), true, "audio/x-dsf"),
    DFF(setOf("dff"), true, "audio/x-dff"),
    WAV(setOf("wav"), true, "audio/wav"),
    M4A(setOf("m4a"), true, "audio/mp4"),
    /** Apple Lossless; normalde .m4a içindedir, ".alac" uzantısı da kabul edilir (MP4 kapsayıcı). */
    ALAC(setOf("alac"), true, "audio/mp4"),
    MP3(setOf("mp3"), true, "audio/mpeg"),
    OGG(setOf("ogg"), true, "audio/ogg"),
    OPUS(setOf("opus"), true, "audio/opus"),
    AAC(setOf("aac"), true, "audio/aac"),
    LRC(setOf("lrc"), false, "application/octet-stream"),
    M3U8(setOf("m3u8"), false, "audio/x-mpegurl"),
    COVER(emptySet(), false, "image/jpeg");

    val isAuxiliary: Boolean get() = !isAudio

    companion object {
        /** Albüm kapağı için kabul edilen tek ad (karşılaştırma büyük/küçük harf duyarsız). */
        const val COVER_FILE_NAME = "cover.jpg"

        private val byExtension: Map<String, AudioFormat> =
            entries.flatMap { f -> f.extensions.map { it to f } }.toMap()

        /** "Song.FLAC" → FLAC, "Cover.JPG" → COVER, "a.jpg"/"a.txt"/"noext" → null. */
        fun fromFileName(name: String): AudioFormat? {
            if (name.equals(COVER_FILE_NAME, ignoreCase = true)) return COVER
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return null
            return byExtension[name.substring(dot + 1).lowercase(Locale.ROOT)]
        }

        /** Dosya adının uzantısı küçük harfle ("A.FLAC" → "flac"); yoksa "". */
        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
        }
    }
}
