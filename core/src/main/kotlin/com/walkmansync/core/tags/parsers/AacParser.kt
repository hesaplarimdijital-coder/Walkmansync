package com.walkmansync.core.tags.parsers

import com.walkmansync.core.model.AudioTags

/** AAC ADTS: başta ID3v2 varsa etiketler; süre kare sayımından (büyük dosyalarda örneklemeyle tahmin). */
internal object AacParser {
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    private const val CHUNK = 256 * 1024
    private const val SCAN_LIMIT = 1024 * 1024L
    private const val SYNC_SEARCH = 8 * 1024

    fun parse(reader: BoundedReader): AudioTags? {
        val b = TagBuilder()
        val audioStart = Id3v2.parseLeading(reader, b)
        b.duration(estimateDurationMs(reader, audioStart))
        return b.build()
    }

    /** [pos] konumunda geçerli ADTS başlığı varsa (örnekleme hızı, kare uzunluğu). */
    private fun header(d: ByteArray, i: Int): Pair<Int, Int>? {
        if (i + 7 > d.size) return null
        if (d.u8(i) != 0xFF || (d.u8(i + 1) and 0xF6) != 0xF0) return null
        val sf = (d.u8(i + 2) shr 2) and 0xF
        if (sf >= RATES.size) return null
        val len = ((d.u8(i + 3) and 3) shl 11) or (d.u8(i + 4) shl 3) or (d.u8(i + 5) shr 5)
        if (len < 7) return null
        return RATES[sf] to len
    }

    fun estimateDurationMs(reader: BoundedReader, audioStart: Long): Long? {
        // İlk kareyi bul.
        val probe = reader.readUpTo(audioStart, SYNC_SEARCH * 2)
        var first = -1
        var sampleRate = 0
        for (i in 0..minOf(probe.size - 7, SYNC_SEARCH)) {
            val h = header(probe, i) ?: continue
            val nextAt = i + h.second
            val ok = if (nextAt + 7 <= probe.size) header(probe, nextAt) != null else audioStart + nextAt >= reader.size - 6
            if (ok) { first = i; sampleRate = h.first; break }
        }
        if (first < 0) return null
        val start = audioStart + first

        var p = start
        var frames = 0L
        var buf = ByteArray(0)
        var bufStart = 0L
        var reachedEnd = false
        while (true) {
            if (p - bufStart + 7 > buf.size || p < bufStart) {
                buf = reader.readUpTo(p, CHUNK)
                bufStart = p
                if (buf.size < 7) { reachedEnd = p + 7 > reader.size; break }
            }
            val h = header(buf, (p - bufStart).toInt())
            if (h == null) {
                // Dosya sonu dolgusu/etiketi ya da bozulma: sonuna yakınsa tamamlanmış say, değilse örnekle tahmin et.
                reachedEnd = reader.size - p < 8192
                break
            }
            frames++
            p += h.second
            if (p >= reader.size) { reachedEnd = true; break }
            if (p - start >= SCAN_LIMIT) break
        }
        if (frames == 0L) return null
        val scannedBytes = p - start
        val totalFrames = if (reachedEnd || scannedBytes <= 0) frames
        else frames * (reader.size - start) / scannedBytes
        return totalFrames * 1024 * 1000 / sampleRate
    }
}
