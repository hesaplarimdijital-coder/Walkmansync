package com.walkmansync.media

import android.app.Application
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.walkmansync.core.fs.RandomAccessSource
import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.tags.TagFallback
import java.io.IOException

/**
 * Saf ayrıştırıcının bulamadığı etiketler için platform yedeği: [MediaMetadataRetriever].
 * Kaynak, `MediaDataSource` ile doğrudan [RandomAccessSource] üzerinden okunur (fd gerekmez);
 * kaynağı kapatmaz, hiçbir hata fırlatmaz.
 */
class MediaMetadataTagFallback(
    @Suppress("unused") private val app: Application,
    private val logger: Logger,
) : TagFallback {

    override fun read(source: RandomAccessSource, format: AudioFormat): AudioTags? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(RandomAccessMediaDataSource(source))
            val tags = AudioTags(
                artist = retriever.text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                title = retriever.text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                trackNumber = positiveNumber(retriever.text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNumber = positiveNumber(retriever.text(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                durationMs = retriever.text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 },
            )
            if (tags.isEmpty) null else tags
        } catch (e: Exception) {
            // Bozuk/desteklenmeyen dosya: yedek sessizce vazgeçer (sözleşme: fırlatmaz).
            logger.d(TAG, "Yedek etiket okuma başarısız ($format): ${e.javaClass.simpleName}")
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                logger.d(TAG, "Retriever kapatılamadı: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun MediaMetadataRetriever.text(key: Int): String? = extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }

    /** "3/12" → 3; sıfır, negatif ya da sayı olmayan değer → null. */
    private fun positiveNumber(raw: String?): Int? =
        raw?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private companion object {
        const val TAG = "TagFallback"
    }
}

/** [RandomAccessSource]'u `MediaDataSource`'a bağlar; [close] altındaki kaynağı kapatmaz (sahibi çağıran). */
private class RandomAccessMediaDataSource(private val source: RandomAccessSource) : MediaDataSource() {

    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        return source.readAt(position, buffer, offset, size)
    }

    @Throws(IOException::class)
    override fun getSize(): Long = source.size

    override fun close() = Unit
}
