package com.walkmansync.core.tags.parsers

import com.walkmansync.core.model.AudioTags

/** WAV (RIFF/RF64): fmt + data'dan süre, LIST/INFO ve "id3 " chunk'ından etiketler. */
internal object RiffParser {
    private const val MAX_CHUNKS = 4096

    fun parse(reader: BoundedReader): AudioTags? {
        val head = reader.readExact(0, 12) ?: return null
        val rf64 = head.startsWithAscii(0, "RF64")
        if ((!head.startsWithAscii(0, "RIFF") && !rf64) || !head.startsWithAscii(8, "WAVE")) return null

        val id3 = TagBuilder()
        val info = TagBuilder()
        var byteRate = 0L
        var dataSize = -1L
        var dataStart = -1L
        var ds64DataSize = -1L

        var pos = 12L
        var n = 0
        while (n++ < MAX_CHUNKS && pos + 8 <= reader.size) {
            val h = reader.readExact(pos, 8) ?: break
            val id = h.fourCc(0)
            val rawSize = h.le32(4)
            val start = pos + 8
            val size = if (id == "data" && rawSize == 0xFFFFFFFFL) {
                if (ds64DataSize >= 0) ds64DataSize else reader.size - start
            } else rawSize
            when (id) {
                "ds64" -> reader.readExact(start, 24.coerceAtMost(size.toInt()))?.let {
                    if (it.size >= 16) ds64DataSize = it.le64(8)
                }
                "fmt " -> if (size >= 16) reader.readExact(start, 16)?.let {
                    byteRate = it.le32(8)
                    if (byteRate <= 0) byteRate = it.le32(4) * it.le16(12).toLong()
                }
                "data" -> if (dataSize < 0) {
                    dataStart = start
                    dataSize = minOf(size, (reader.size - start).coerceAtLeast(0))
                }
                "LIST" -> if (size in 12..MAX_TEXT_FIELD_BYTES.toLong()) readInfoList(reader, start, size.toInt(), info)
                "id3 ", "ID3 " -> if (size >= 10) Id3v2.parseInto(reader, start, id3)
            }
            val next = start + size + (size and 1L)
            if (next <= pos || next > reader.size + 1) break
            pos = next
        }
        if (dataStart >= 0 && byteRate > 0) id3.duration(dataSize * 1000 / byteRate, override = true)
        return id3.build().mergedWith(info.build())
    }

    private fun readInfoList(reader: BoundedReader, start: Long, size: Int, out: TagBuilder) {
        val d = reader.readExact(start, size) ?: return
        if (!d.startsWithAscii(0, "INFO")) return
        var p = 4
        while (p + 8 <= d.size) {
            val id = d.fourCc(p)
            val len = d.le32(p + 4)
            val s = p + 8
            if (len < 0 || s + len > d.size) return
            val n = len.toInt()
            var e = s
            while (e < s + n && d[e].toInt() != 0) e++
            val text = TagText.strictUtf8(d, s, e - s) ?: TagText.latinOrUtf8(d, s, e - s)
            when (id) {
                "IART" -> out.artist(text)
                "INAM" -> out.title(text)
                "IPRD" -> out.album(text)
                "ITRK", "IPRT" -> out.track(text)
            }
            p = s + n + (n and 1)
        }
    }
}
