package com.walkmansync.core.tags.parsers

import java.io.ByteArrayOutputStream

/** ID3v2.2/2.3/2.4 ayrıştırıcı. Yalnızca metin çerçevelerinden istenenleri okur; gerisini atlar. */
internal object Id3v2 {
    private const val MAX_FRAMES = 20_000
    private const val MAX_TAG_COUNT = 4

    /** Bir çerçeve penceresi: ya kaynak üzerinde ya bellekte (unsynchronisation sonrası). */
    private abstract class Window(val length: Long) {
        abstract fun get(pos: Long, len: Int): ByteArray?
    }

    private class SourceWindow(val reader: BoundedReader, val base: Long, length: Long) : Window(length) {
        override fun get(pos: Long, len: Int): ByteArray? =
            if (pos < 0 || len < 0 || pos + len > length) null else reader.readExact(base + pos, len)
    }

    private class MemoryWindow(val data: ByteArray) : Window(data.size.toLong()) {
        override fun get(pos: Long, len: Int): ByteArray? =
            if (pos < 0 || len < 0 || pos + len > data.size) null else data.copyOfRange(pos.toInt(), pos.toInt() + len)
    }

    /**
     * [offset] konumunda bir ID3v2 etiketi varsa alanlarını [out]'a ekler ve etiketin TOPLAM bayt uzunluğunu
     * (başlık + gövde + varsa alt bilgi) döndürür; etiket yoksa 0. Biçim bozuksa da başlık geçerliyse uzunluk döner.
     */
    fun parseInto(reader: BoundedReader, offset: Long, out: TagBuilder): Long {
        val h = reader.readExact(offset, 10) ?: return 0
        if (!h.startsWithAscii(0, "ID3")) return 0
        val major = h.u8(3)
        val flags = h.u8(5)
        if (h.u8(6) >= 0x80 || h.u8(7) >= 0x80 || h.u8(8) >= 0x80 || h.u8(9) >= 0x80) return 0
        val bodySize = synchsafe(h, 6)
        val footer = if (major == 4 && (flags and 0x10) != 0) 10L else 0L
        val total = 10L + bodySize + footer
        if (major !in 2..4) return total
        if (major == 2 && (flags and 0x40) != 0) return total // v2.2 sıkıştırma: desteklenmez
        val bodyLen = minOf(bodySize, reader.size - offset - 10).coerceAtLeast(0)
        if (bodyLen < 6) return total
        val headerUnsync = (flags and 0x80) != 0
        try {
            val window: Window = if (headerUnsync && major < 4) {
                val raw = reader.readUpTo(offset + 10, minOf(bodyLen, Int.MAX_VALUE.toLong()).toInt())
                MemoryWindow(deUnsync(raw))
            } else {
                SourceWindow(reader, offset + 10, bodyLen)
            }
            parseFrames(window, major, flags, headerUnsync && major == 4, out)
        } catch (e: IndexOutOfBoundsException) {
            // Bozuk etiket: o ana kadar toplananlarla yetin.
        } catch (e: NegativeArraySizeException) {
        }
        return total
    }

    private fun parseFrames(w: Window, major: Int, flags: Int, allUnsync: Boolean, out: TagBuilder) {
        var pos = 0L
        if ((flags and 0x40) != 0 && major >= 3) {
            val eh = w.get(0, 4) ?: return
            pos = if (major == 3) 4L + eh.be32(0) else synchsafeOrPlain(eh, 0)
            if (pos < 4 || pos > w.length) return
        }
        val hdrLen = if (major == 2) 6 else 10
        var frames = 0
        while (pos + hdrLen <= w.length && frames++ < MAX_FRAMES) {
            val fh = w.get(pos, hdrLen) ?: return
            if (fh[0].toInt() == 0) return // dolgu
            val idLen = if (major == 2) 3 else 4
            for (k in 0 until idLen) {
                val c = fh[k].toInt()
                if (!((c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code))) return
            }
            val id = String(fh, 0, idLen, Charsets.ISO_8859_1)
            val size: Long
            var frameFlags = 0
            when (major) {
                2 -> size = fh.be24(3).toLong()
                3 -> { size = fh.be32(4); frameFlags = fh.be16(8) }
                else -> { size = synchsafeOrPlain(fh, 4); frameFlags = fh.be16(8) }
            }
            val dataStart = pos + hdrLen
            if (size < 0 || dataStart + size > w.length) return
            val next = dataStart + size
            val field = fieldOf(id, major)
            if (field != null && size in 2..MAX_TEXT_FIELD_BYTES.toLong()) {
                var skip = false
                var prefix = 0
                var frameUnsync = allUnsync
                if (major == 3) {
                    if ((frameFlags and 0x00C0) != 0) skip = true // sıkıştırma/şifreleme
                    if ((frameFlags and 0x0020) != 0) prefix += 1 // gruplama baytı
                } else if (major == 4) {
                    if ((frameFlags and 0x000C) != 0) skip = true
                    if ((frameFlags and 0x0040) != 0) prefix += 1
                    if ((frameFlags and 0x0001) != 0) prefix += 4
                    if ((frameFlags and 0x0002) != 0) frameUnsync = true
                }
                if (!skip && size > prefix) {
                    var body = w.get(dataStart, size.toInt())
                    if (body != null) {
                        if (frameUnsync) body = deUnsync(body)
                        if (body.size > prefix + 1) applyText(field, body, prefix, out)
                    }
                }
            }
            if (out.isComplete && out.durationMs != null) return
            pos = next
        }
    }

    private enum class Field { ARTIST, ALBUM_ARTIST, ALBUM, TITLE, TRACK, DISC, LENGTH }

    private fun fieldOf(id: String, major: Int): Field? = if (major == 2) when (id) {
        "TP1" -> Field.ARTIST
        "TP2" -> Field.ALBUM_ARTIST
        "TAL" -> Field.ALBUM
        "TT2" -> Field.TITLE
        "TRK" -> Field.TRACK
        "TPA" -> Field.DISC
        "TLE" -> Field.LENGTH
        else -> null
    } else when (id) {
        "TPE1" -> Field.ARTIST
        "TPE2" -> Field.ALBUM_ARTIST
        "TALB" -> Field.ALBUM
        "TIT2" -> Field.TITLE
        "TRCK" -> Field.TRACK
        "TPOS" -> Field.DISC
        "TLEN" -> Field.LENGTH
        else -> null
    }

    private fun applyText(field: Field, body: ByteArray, prefix: Int, out: TagBuilder) {
        val enc = body.u8(prefix)
        val text = TagText.decodeId3(enc, body, prefix + 1, body.size)
        when (field) {
            Field.ARTIST -> out.artist(text)
            Field.ALBUM_ARTIST -> out.albumArtist(text)
            Field.ALBUM -> out.album(text)
            Field.TITLE -> out.title(text)
            Field.TRACK -> out.track(text)
            Field.DISC -> out.disc(text)
            Field.LENGTH -> out.duration(TagText.longMillis(text))
        }
    }

    private fun synchsafe(b: ByteArray, i: Int): Long =
        (b.u8(i).toLong() shl 21) or (b.u8(i + 1).toLong() shl 14) or (b.u8(i + 2).toLong() shl 7) or b.u8(i + 3).toLong()

    /** v2.4 çerçeve boyutu: normalde synchsafe; bazı yazıcılar düz BE yazar (bit 7 set ise düz say). */
    private fun synchsafeOrPlain(b: ByteArray, i: Int): Long =
        if (b.u8(i) >= 0x80 || b.u8(i + 1) >= 0x80 || b.u8(i + 2) >= 0x80 || b.u8(i + 3) >= 0x80) b.be32(i) else synchsafe(b, i)

    /** 0xFF 0x00 → 0xFF. */
    fun deUnsync(data: ByteArray): ByteArray {
        var has = false
        for (i in 0 until data.size - 1) if (data[i].toInt() == -1 && data[i + 1].toInt() == 0) { has = true; break }
        if (!has) return data
        val o = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt()
            o.write(b)
            if (b == -1 && i + 1 < data.size && data[i + 1].toInt() == 0) i++
            i++
        }
        return o.toByteArray()
    }

    /** Dosya başındaki art arda ID3v2 etiketlerini ayrıştırır; ses verisinin başladığı konumu döndürür. */
    fun parseLeading(reader: BoundedReader, out: TagBuilder): Long {
        var pos = 0L
        var count = 0
        while (count++ < MAX_TAG_COUNT) {
            val n = parseInto(reader, pos, out)
            if (n <= 0) break
            pos += n
            if (pos >= reader.size) return reader.size
        }
        return pos
    }
}

/** ID3v1 / v1.1 (dosya sonundaki 128 bayt). Yalnızca [TagBuilder]'daki boş alanları doldurur. */
internal object Id3v1 {
    /** Etiket bulunduysa true. */
    fun parseInto(reader: BoundedReader, out: TagBuilder): Boolean {
        if (reader.size < 128) return false
        val b = reader.readExact(reader.size - 128, 128) ?: return false
        if (!b.startsWithAscii(0, "TAG")) return false
        out.title(field(b, 3, 30))
        out.artist(field(b, 33, 30))
        out.album(field(b, 63, 30))
        if (b[125].toInt() == 0 && b[126].toInt() != 0) out.track(b.u8(126))
        return true
    }

    private fun field(b: ByteArray, off: Int, len: Int): String? {
        var e = off
        while (e < off + len && b[e].toInt() != 0) e++
        return TagText.clean(TagText.latinOrUtf8(b, off, e - off))
    }
}

/** MPEG ses (Layer I/II/III) kare başlığı ve süre hesabı. */
internal object MpegAudio {
    private val BITRATES_V1_L1 = intArrayOf(32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val BITRATES_V1_L2 = intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val BITRATES_V1_L3 = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATES_V2_L1 = intArrayOf(32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val BITRATES_V2_L23 = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val RATES_V1 = intArrayOf(44100, 48000, 32000)
    private val RATES_V2 = intArrayOf(22050, 24000, 16000)
    private val RATES_V25 = intArrayOf(11025, 12000, 8000)

    private const val SCAN_WINDOW = 32 * 1024
    private const val SCAN_LIMIT = 256 * 1024

    class FrameHeader(
        val isMpeg1: Boolean,
        val layer: Int,
        val bitrateKbps: Int,
        val sampleRate: Int,
        val padding: Int,
        val isMono: Boolean,
        val hasCrc: Boolean,
    ) {
        val samplesPerFrame: Int
            get() = when (layer) { 1 -> 384; 2 -> 1152; else -> if (isMpeg1) 1152 else 576 }

        val frameLength: Int
            get() = when (layer) {
                1 -> (12 * bitrateKbps * 1000 / sampleRate + padding) * 4
                2 -> 144 * bitrateKbps * 1000 / sampleRate + padding
                else -> (if (isMpeg1) 144 else 72) * bitrateKbps * 1000 / sampleRate + padding
            }

        /** Kare başından Xing/Info etiketinin beklenen konumu. */
        val xingOffset: Int
            get() {
                val side = if (isMpeg1) (if (isMono) 17 else 32) else (if (isMono) 9 else 17)
                return 4 + (if (hasCrc) 2 else 0) + side
            }
    }

    fun parseHeader(b: ByteArray, i: Int): FrameHeader? {
        if (i < 0 || i + 4 > b.size) return null
        if (b.u8(i) != 0xFF || (b.u8(i + 1) and 0xE0) != 0xE0) return null
        val versionBits = (b.u8(i + 1) shr 3) and 3
        val layerBits = (b.u8(i + 1) shr 1) and 3
        if (versionBits == 1 || layerBits == 0) return null
        val layer = 4 - layerBits
        val bitrateIdx = (b.u8(i + 2) shr 4) and 0xF
        val rateIdx = (b.u8(i + 2) shr 2) and 3
        if (bitrateIdx == 0 || bitrateIdx == 15 || rateIdx == 3) return null
        val isMpeg1 = versionBits == 3
        val table = when {
            isMpeg1 -> when (layer) { 1 -> BITRATES_V1_L1; 2 -> BITRATES_V1_L2; else -> BITRATES_V1_L3 }
            layer == 1 -> BITRATES_V2_L1
            else -> BITRATES_V2_L23
        }
        val rate = when (versionBits) { 3 -> RATES_V1[rateIdx]; 2 -> RATES_V2[rateIdx]; else -> RATES_V25[rateIdx] }
        return FrameHeader(
            isMpeg1 = isMpeg1, layer = layer, bitrateKbps = table[bitrateIdx - 1], sampleRate = rate,
            padding = (b.u8(i + 2) shr 1) and 1, isMono = ((b.u8(i + 3) shr 6) and 3) == 3,
            hasCrc = (b.u8(i + 1) and 1) == 0,
        )
    }

    /** [start]'tan itibaren ilk geçerli (ardışık kareyle doğrulanmış) kare. */
    fun findFirstFrame(reader: BoundedReader, start: Long): Pair<Long, FrameHeader>? {
        var base = start
        val limit = minOf(reader.size, start + SCAN_LIMIT)
        while (base + 4 <= limit) {
            val win = reader.readUpTo(base, SCAN_WINDOW)
            if (win.size < 4) return null
            for (i in 0..win.size - 4) {
                val h = parseHeader(win, i) ?: continue
                val len = h.frameLength
                if (len < 24) continue
                val abs = base + i
                val nextPos = abs + len
                if (nextPos + 4 > reader.size) {
                    // Tek kareli ya da kısa dosya: sonraki kare aranmaz.
                    if (nextPos <= reader.size) return abs to h
                    continue
                }
                val nextHdr = if (i + len + 4 <= win.size) win.copyOfRange(i + len, i + len + 4) else reader.readExact(nextPos, 4)
                if (nextHdr != null && parseHeader(nextHdr, 0) != null) return abs to h
            }
            if (win.size < SCAN_WINDOW) return null
            base += SCAN_WINDOW - 3
        }
        return null
    }

    /** Xing/Info/VBRI varsa onunla, yoksa CBR tahminiyle süre (ms). */
    fun durationMs(reader: BoundedReader, audioStart: Long, hasId3v1: Boolean): Long? {
        val (framePos, h) = findFirstFrame(reader, audioStart) ?: return null
        val frame = reader.readUpTo(framePos, 4 + 2 + 36 + 16)
        val spf = h.samplesPerFrame.toLong()
        val frames = xingFrames(frame, h) ?: vbriFrames(frame)
        if (frames != null && frames > 0) return frames * spf * 1000 / h.sampleRate
        val tail = if (hasId3v1) 128L else 0L
        val audioBytes = reader.size - framePos - tail
        if (audioBytes <= 0) return null
        return audioBytes * 8 / h.bitrateKbps
    }

    private fun xingFrames(frame: ByteArray, h: FrameHeader): Long? {
        for (off in intArrayOf(h.xingOffset, 36, 21, 13)) {
            if (off + 12 > frame.size) continue
            if (!frame.startsWithAscii(off, "Xing") && !frame.startsWithAscii(off, "Info")) continue
            val flags = frame.be32(off + 4)
            if ((flags and 1L) == 0L) return null
            return frame.be32(off + 8)
        }
        return null
    }

    private fun vbriFrames(frame: ByteArray): Long? {
        val off = 36
        if (off + 18 > frame.size || !frame.startsWithAscii(off, "VBRI")) return null
        return frame.be32(off + 14)
    }
}

/** MP3: ID3v2 + ID3v1 + MPEG süre. */
internal object Mp3TagParser {
    fun parse(reader: BoundedReader): com.walkmansync.core.model.AudioTags {
        val b = TagBuilder()
        val audioStart = Id3v2.parseLeading(reader, b)
        val hasV1 = Id3v1.parseInto(reader, b)
        b.duration(MpegAudio.durationMs(reader, audioStart, hasV1), override = true)
        return b.build()
    }
}
