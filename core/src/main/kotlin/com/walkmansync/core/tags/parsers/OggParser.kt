package com.walkmansync.core.tags.parsers

import com.walkmansync.core.model.AudioTags
import java.io.ByteArrayOutputStream

/** Ogg Vorbis / Opus (ve Ogg-FLAC): yorum paketi birden çok sayfaya yayılabilir; süre son granule konumundan. */
internal object OggParser {
    private const val MAX_PAGES = 512
    private const val MAX_COMMENT_PACKET = 3 * 1024 * 1024
    private const val TAIL_BYTES = 70_000

    private enum class Codec { UNKNOWN, VORBIS, OPUS, FLAC }

    fun parse(reader: BoundedReader): AudioTags? {
        val b = TagBuilder()
        var codec = Codec.UNKNOWN
        var sampleRate = 0L
        var preSkip = 0L

        var pos = 0L
        var packetIndex = 0
        var pages = 0
        val cur = ByteArrayOutputStream()
        var truncated = false

        fun handle(index: Int, packet: ByteArray) {
            if (index == 0) {
                when {
                    packet.size >= 16 && packet.u8(0) == 1 && packet.startsWithAscii(1, "vorbis") -> {
                        codec = Codec.VORBIS
                        sampleRate = packet.le32(12)
                    }
                    packet.size >= 19 && packet.startsWithAscii(0, "OpusHead") -> {
                        codec = Codec.OPUS
                        preSkip = packet.le16(10).toLong()
                        sampleRate = 48_000
                    }
                    packet.size >= 17 + 34 && packet.u8(0) == 0x7F && packet.startsWithAscii(1, "FLAC") -> {
                        codec = Codec.FLAC
                        val rate = (packet.u8(17 + 10) shl 12) or (packet.u8(17 + 11) shl 4) or (packet.u8(17 + 12) shr 4)
                        sampleRate = rate.toLong()
                    }
                }
            } else if (index == 1) {
                when (codec) {
                    Codec.VORBIS -> if (packet.size > 7 && packet.u8(0) == 3 && packet.startsWithAscii(1, "vorbis"))
                        VorbisComments.parseInto(packet, 7, packet.size, b)
                    Codec.OPUS -> if (packet.size > 8 && packet.startsWithAscii(0, "OpusTags"))
                        VorbisComments.parseInto(packet, 8, packet.size, b)
                    Codec.FLAC -> if (packet.size > 4 && (packet.u8(0) and 0x7F) == 4)
                        VorbisComments.parseInto(packet, 4, packet.size, b)
                    Codec.UNKNOWN -> Unit
                }
            }
        }

        while (packetIndex < 2 && pages++ < MAX_PAGES && pos + 27 <= reader.size) {
            val hdr = reader.readExact(pos, 27) ?: break
            if (!hdr.startsWithAscii(0, "OggS")) break
            val nSegs = hdr.u8(26)
            val segs = reader.readExact(pos + 27, nSegs) ?: break
            var dataLen = 0
            for (s in segs) dataLen += s.toInt() and 0xFF
            val dataPos = pos + 27 + nSegs
            val body = reader.readUpTo(dataPos, dataLen)
            var off = 0
            for (s in segs) {
                val len = s.toInt() and 0xFF
                val take = minOf(len, body.size - off).coerceAtLeast(0)
                cur.write(body, off, take)
                off += len
                if (len < 255) {
                    handle(packetIndex, cur.toByteArray())
                    cur.reset()
                    packetIndex++
                    if (packetIndex >= 2) break
                }
            }
            if (body.size < dataLen) { truncated = true; break }
            if (cur.size() > MAX_COMMENT_PACKET) { truncated = true; break }
            pos = dataPos + dataLen
        }
        // Tamamlanamayan (çok büyük/kesik) yorum paketinden başa yakın alanları kurtar.
        if (packetIndex == 1 && (truncated || cur.size() > 0)) handle(1, cur.toByteArray())

        if (codec != Codec.UNKNOWN && sampleRate > 0) {
            lastGranule(reader)?.let { granule ->
                val samples = if (codec == Codec.OPUS) granule - preSkip else granule
                if (samples > 0) b.duration(samples * 1000 / sampleRate)
            }
        }
        return b.build()
    }

    /** Dosyadaki son geçerli sayfanın granule konumu (-1 olanlar atlanır); yoksa null. */
    private fun lastGranule(reader: BoundedReader): Long? {
        val start = maxOf(0L, reader.size - TAIL_BYTES)
        val t = reader.readUpTo(start, TAIL_BYTES)
        var i = t.size - 27
        while (i >= 0) {
            if (t[i].toInt() == 'O'.code && t.startsWithAscii(i, "OggS") && t.u8(i + 4) == 0) {
                val g = t.le64(i + 6)
                if (g > 0) return g
            }
            i--
        }
        return null
    }
}
