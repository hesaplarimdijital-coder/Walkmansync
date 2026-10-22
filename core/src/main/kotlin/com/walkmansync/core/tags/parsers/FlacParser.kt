package com.walkmansync.core.tags.parsers

import com.walkmansync.core.model.AudioTags

/** FLAC: STREAMINFO (süre) + VORBIS_COMMENT. Başta ID3v2 varsa atlanır. */
internal object FlacParser {
    private const val MAX_BLOCKS = 1024

    fun parse(reader: BoundedReader): AudioTags? {
        val b = TagBuilder()
        var pos = Id3v2.parseLeading(reader, b)
        val magic = reader.readExact(pos, 4) ?: return null
        if (!magic.startsWithAscii(0, "fLaC")) return null
        pos += 4
        var blocks = 0
        while (blocks++ < MAX_BLOCKS && pos + 4 <= reader.size) {
            val hd = reader.readExact(pos, 4) ?: break
            val last = (hd.u8(0) and 0x80) != 0
            val type = hd.u8(0) and 0x7F
            val len = hd.be24(1).toLong()
            val dataPos = pos + 4
            if (type == 127) break
            if (type == 0 && len >= 34) {
                reader.readExact(dataPos, 34)?.let { b.duration(streamInfoDurationMs(it, 0)) }
            } else if (type == 4 && len <= MAX_FIELD_BYTES) {
                val data = reader.readUpTo(dataPos, len.toInt())
                VorbisComments.parseInto(data, 0, data.size, b)
            }
            if (last) break
            pos = dataPos + len
        }
        return b.build()
    }

    /** 34 baytlık STREAMINFO'dan süre (ms); [off] bloğun veri başlangıcı. */
    fun streamInfoDurationMs(d: ByteArray, off: Int): Long? {
        if (off + 34 > d.size) return null
        val rate = (d.u8(off + 10) shl 12) or (d.u8(off + 11) shl 4) or (d.u8(off + 12) shr 4)
        val total = ((d.u8(off + 13) and 0x0F).toLong() shl 32) or d.be32(off + 14)
        if (rate <= 0 || total <= 0) return null
        return total * 1000 / rate
    }
}
