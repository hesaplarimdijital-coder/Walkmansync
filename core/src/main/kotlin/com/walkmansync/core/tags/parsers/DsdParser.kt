package com.walkmansync.core.tags.parsers

import com.walkmansync.core.model.AudioTags

/** DSF: fmt'den süre + metadata işaretçisindeki ID3v2. */
internal object DsfParser {
    fun parse(reader: BoundedReader): AudioTags? {
        val head = reader.readExact(0, 28 + 52) ?: return null
        if (!head.startsWithAscii(0, "DSD ")) return null
        val b = TagBuilder()
        val metaPtr = head.le64(20)
        if (head.startsWithAscii(28, "fmt ")) {
            val samplingFrequency = head.le32(28 + 28)
            val sampleCount = head.le64(28 + 36)
            if (samplingFrequency > 0 && sampleCount > 0) b.duration(sampleCount * 1000 / samplingFrequency)
        }
        if (metaPtr in 28 until reader.size) Id3v2.parseInto(reader, metaPtr, b)
        return b.build()
    }
}

/** DFF (DSDIFF): PROP'tan örnekleme hızı/kanal, DSD veri boyutundan süre; "ID3 " chunk'ı ve DIIN (DIAR/DITI). */
internal object DffParser {
    private const val MAX_CHUNKS = 2048

    fun parse(reader: BoundedReader): AudioTags? {
        val head = reader.readExact(0, 16) ?: return null
        if (!head.startsWithAscii(0, "FRM8") || !head.startsWithAscii(12, "DSD ")) return null
        val id3 = TagBuilder()
        val diin = TagBuilder()
        var sampleRate = 0L
        var channels = 0
        var dsdBytes = -1L

        var pos = 16L
        var n = 0
        while (n++ < MAX_CHUNKS && pos + 12 <= reader.size) {
            val h = reader.readExact(pos, 12) ?: break
            val id = h.fourCc(0)
            val size = h.be64(4)
            val start = pos + 12
            if (size < 0) break
            when (id) {
                "PROP" -> if (size in 4..65_536) reader.readExact(start, size.toInt())?.let { p ->
                    if (p.startsWithAscii(0, "SND ")) {
                        var q = 4
                        while (q + 12 <= p.size) {
                            val sid = p.fourCc(q)
                            val ssz = p.be64(q + 4)
                            val sdata = q + 12
                            if (ssz < 0 || sdata + ssz > p.size) break
                            if (sid == "FS  " && ssz >= 4) sampleRate = p.be32(sdata)
                            if (sid == "CHNL" && ssz >= 2) channels = p.be16(sdata)
                            q = (sdata + ssz + (ssz and 1L)).toInt()
                        }
                    }
                }
                "DSD " -> if (dsdBytes < 0) dsdBytes = minOf(size, (reader.size - start).coerceAtLeast(0))
                "ID3 " -> if (size >= 10) Id3v2.parseInto(reader, start, id3)
                "DIIN" -> if (size in 8..65_536) reader.readExact(start, size.toInt())?.let { readDiin(it, diin) }
            }
            val next = start + size + (size and 1L)
            if (next <= pos || next > reader.size + 1) break
            pos = next
        }
        if (sampleRate > 0 && channels > 0 && dsdBytes > 0) {
            id3.duration(dsdBytes * 8 * 1000 / channels / sampleRate, override = true)
        }
        return id3.build().mergedWith(diin.build())
    }

    /** DIIN yerel chunk'ları: kimlik(4) boyut(4) + [metin uzunluğu(4) + metin] (DIAR sanatçı, DITI başlık). */
    private fun readDiin(d: ByteArray, out: TagBuilder) {
        var p = 0
        while (p + 8 <= d.size) {
            val id = d.fourCc(p)
            val size = d.be32(p + 4)
            val s = p + 8
            if (size < 0 || s + size > d.size) return
            if ((id == "DIAR" || id == "DITI") && size >= 4) {
                val textLen = d.be32(s)
                if (textLen >= 0 && textLen <= size - 4) {
                    val text = TagText.strictUtf8(d, s + 4, textLen.toInt()) ?: TagText.latinOrUtf8(d, s + 4, textLen.toInt())
                    if (id == "DIAR") out.artist(text) else out.title(text)
                }
            }
            p = (s + size + (size and 1L)).toInt()
        }
    }
}
