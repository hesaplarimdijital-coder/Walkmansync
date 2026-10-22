package com.walkmansync.core.tags.parsers

import com.walkmansync.core.model.AudioTags

/** MP4/M4A/ALAC: moov/mvhd (süre) + moov/udta/meta/ilst (etiketler). 64-bit atom boyutları desteklenir. */
internal object Mp4Parser {
    private const val MAX_ATOMS_PER_LEVEL = 20_000

    private class Atom(val type: String, val dataStart: Long, val end: Long)

    fun parse(reader: BoundedReader): AudioTags? {
        if (reader.size < 16) return null
        val b = TagBuilder()
        forEachAtom(reader, 0, reader.size) { atom ->
            if (atom.type == "moov") {
                parseMoov(reader, atom, b)
                false
            } else true
        }
        return b.build()
    }

    /** [visit] false dönerse yineleme durur. */
    private fun forEachAtom(reader: BoundedReader, start: Long, end: Long, visit: (Atom) -> Boolean) {
        var pos = start
        var n = 0
        while (pos + 8 <= end && n++ < MAX_ATOMS_PER_LEVEL) {
            val h = reader.readUpTo(pos, 16)
            if (h.size < 8) return
            val size32 = h.be32(0)
            val type = h.fourCc(4)
            var headerLen = 8
            var size = size32
            if (size32 == 1L) {
                if (h.size < 16) return
                size = h.be64(8)
                headerLen = 16
            } else if (size32 == 0L) {
                size = end - pos
            }
            if (size < headerLen) return
            val atomEnd = if (pos + size > end || pos + size < pos) end else pos + size
            if (!visit(Atom(type, pos + headerLen, atomEnd))) return
            pos = atomEnd
        }
    }

    private fun parseMoov(reader: BoundedReader, moov: Atom, out: TagBuilder) {
        forEachAtom(reader, moov.dataStart, moov.end) { atom ->
            when (atom.type) {
                "mvhd" -> parseMvhd(reader, atom, out)
                "udta" -> forEachAtom(reader, atom.dataStart, atom.end) { child ->
                    if (child.type == "meta") parseMeta(reader, child, out)
                    true
                }
                "meta" -> parseMeta(reader, atom, out)
            }
            true
        }
    }

    private fun parseMvhd(reader: BoundedReader, atom: Atom, out: TagBuilder) {
        val d = reader.readUpTo(atom.dataStart, 32)
        if (d.size < 20) return
        val version = d.u8(0)
        val timescale: Long
        val duration: Long
        if (version == 1) {
            if (d.size < 32) return
            timescale = d.be32(20)
            duration = d.be64(24)
        } else {
            timescale = d.be32(12)
            duration = d.be32(16)
        }
        if (timescale <= 0 || duration <= 0 || duration == 0xFFFFFFFFL) return
        out.duration(duration * 1000 / timescale)
    }

    private fun parseMeta(reader: BoundedReader, meta: Atom, out: TagBuilder) {
        val probe = reader.readUpTo(meta.dataStart, 12)
        val childrenStart = when {
            probe.size >= 12 && probe.startsWithAscii(8, "hdlr") -> meta.dataStart + 4
            probe.size >= 8 && probe.startsWithAscii(4, "hdlr") -> meta.dataStart
            else -> meta.dataStart + 4
        }
        forEachAtom(reader, childrenStart, meta.end) { atom ->
            if (atom.type == "ilst") {
                parseIlst(reader, atom, out)
                false
            } else true
        }
    }

    private fun parseIlst(reader: BoundedReader, ilst: Atom, out: TagBuilder) {
        forEachAtom(reader, ilst.dataStart, ilst.end) { item ->
            when (item.type) {
                "©ART", "aART", "©alb", "©nam", "trkn", "disk" -> readItem(reader, item, out)
            }
            true
        }
    }

    private fun readItem(reader: BoundedReader, item: Atom, out: TagBuilder) {
        forEachAtom(reader, item.dataStart, item.end) { data ->
            if (data.type != "data") return@forEachAtom true
            val len = data.end - data.dataStart
            if (len < 8 || len > MAX_TEXT_FIELD_BYTES) return@forEachAtom false
            val d = reader.readExact(data.dataStart, len.toInt()) ?: return@forEachAtom false
            val typeCode = (d.be32(0) and 0xFFFFFF).toInt()
            val payloadLen = d.size - 8
            when (item.type) {
                "trkn" -> if (payloadLen >= 4) out.track(d.be16(8 + 2))
                "disk" -> if (payloadLen >= 4) out.disc(d.be16(8 + 2))
                else -> {
                    val text = if (typeCode == 2) String(d, 8, payloadLen and 1.inv(), Charsets.UTF_16BE)
                    else TagText.strictUtf8(d, 8, payloadLen) ?: TagText.utf8Lenient(d, 8, payloadLen)
                    when (item.type) {
                        "©ART" -> out.artist(text)
                        "aART" -> out.albumArtist(text)
                        "©alb" -> out.album(text)
                        "©nam" -> out.title(text)
                    }
                }
            }
            false
        }
    }
}
