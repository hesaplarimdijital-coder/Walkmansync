package com.walkmansync.core.tags.parsers

import com.walkmansync.core.fs.RandomAccessSource
import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.tags.TagReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Dosya başına okunan toplam baytı sınırlayan, kısa okumaya dayanıklı okuyucu. Kaynağı kapatmaz. */
internal class BoundedReader(
    private val source: RandomAccessSource,
    private var budget: Long = TagReader.MAX_BYTES_READ,
) {
    val size: Long = source.size

    /** [position]'dan en çok [length] bayt; dosya sonu ya da bütçe nedeniyle kısa olabilir (boş dizi: hiç yok). */
    fun readUpTo(position: Long, length: Int): ByteArray {
        if (position < 0 || length <= 0 || position >= size) return EMPTY
        val want = minOf(length.toLong(), size - position, budget).toInt()
        if (want <= 0) return EMPTY
        val buf = ByteArray(want)
        var done = 0
        while (done < want) {
            val n = source.readAt(position + done, buf, done, want - done)
            if (n <= 0) break
            done += n
        }
        budget -= done
        return if (done == want) buf else buf.copyOf(done)
    }

    /** Tam [length] bayt ya da null (dosya sonu / bütçe). */
    fun readExact(position: Long, length: Int): ByteArray? {
        if (length < 0) return null
        if (length == 0) return EMPTY
        val b = readUpTo(position, length)
        return if (b.size == length) b else null
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.be16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.be24(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)
internal fun ByteArray.be32(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
internal fun ByteArray.be64(i: Int): Long = (be32(i) shl 32) or be32(i + 4)
internal fun ByteArray.le16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.le32(i: Int): Long = le16(i).toLong() or (le16(i + 2).toLong() shl 16)
internal fun ByteArray.le64(i: Int): Long = le32(i) or (le32(i + 4) shl 32)

/** [i] konumundaki dört baytı ASCII/Latin-1 dize olarak verir ("©nam" için 0xA9 → '©'). */
internal fun ByteArray.fourCc(i: Int): String = String(this, i, 4, Charsets.ISO_8859_1)

internal fun ByteArray.startsWithAscii(i: Int, text: String): Boolean {
    if (i < 0 || i + text.length > size) return false
    for (k in text.indices) if (this[i + k].toInt() != text[k].code) return false
    return true
}

/** Çerçeve/alan boyutu bunu aşarsa okunmadan atlanır (gömülü kapak vb. için koruma). */
internal const val MAX_FIELD_BYTES: Long = 16L * 1024 * 1024

/** Metin alanları için daha sıkı sınır. */
internal const val MAX_TEXT_FIELD_BYTES: Int = 1024 * 1024

/** Ayrıştırıcıların ortak biriktiricisi: ilk dolu değer kazanır; metinler temizlenir. */
internal class TagBuilder {
    var artist: String? = null
        private set
    var albumArtist: String? = null
        private set
    var album: String? = null
        private set
    var title: String? = null
        private set
    var trackNumber: Int? = null
        private set
    var discNumber: Int? = null
        private set
    var durationMs: Long? = null

    fun artist(v: String?) { if (artist == null) artist = TagText.clean(v) }
    fun albumArtist(v: String?) { if (albumArtist == null) albumArtist = TagText.clean(v) }
    fun album(v: String?) { if (album == null) album = TagText.clean(v) }
    fun title(v: String?) { if (title == null) title = TagText.clean(v) }
    fun track(v: String?) { if (trackNumber == null) trackNumber = TagText.number(v) }
    fun track(v: Int) { if (trackNumber == null && v > 0) trackNumber = v }
    fun disc(v: String?) { if (discNumber == null) discNumber = TagText.number(v) }
    fun disc(v: Int) { if (discNumber == null && v > 0) discNumber = v }

    /** Başlıktan hesaplanan süre ([override]=true) ya da yalnızca boşsa doldurulan yedek süre. */
    fun duration(ms: Long?, override: Boolean = false) {
        if (ms == null || ms <= 0) return
        if (override || durationMs == null) durationMs = ms
    }

    val isComplete: Boolean
        get() = artist != null && albumArtist != null && album != null && title != null &&
            trackNumber != null && discNumber != null

    fun build(): AudioTags = AudioTags(
        artist = artist, albumArtist = albumArtist, album = album, title = title,
        trackNumber = trackNumber, discNumber = discNumber, durationMs = durationMs,
    )
}

/** Metin temizleme, sayı ayrıştırma ve ID3/Vorbis/MP4 kodlama çözme. */
internal object TagText {
    private val CP1252: Charset = try {
        Charset.forName("windows-1252")
    } catch (e: Exception) {
        Charsets.ISO_8859_1
    }

    /** trim + NUL/BOM arındırma; boş → null. */
    fun clean(s: String?): String? {
        if (s == null) return null
        val t = s.trim { it.isWhitespace() || it == '\u0000' || it == '﻿' }
        return t.ifEmpty { null }
    }

    /** "3/12" → 3, " 07 " → 7; sıfır/negatif/sayı olmayan → null. */
    fun number(s: String?): Int? {
        val t = clean(s) ?: return null
        val head = t.substringBefore('/').trim()
        var end = 0
        while (end < head.length && head[end] in '0'..'9') end++
        if (end == 0 || end > 9) return null
        return head.substring(0, end).toIntOrNull()?.takeIf { it > 0 }
    }

    /** "123456" → 123456; başka → null. */
    fun longMillis(s: String?): Long? {
        val t = clean(s) ?: return null
        if (t.length > 12 || t.any { it !in '0'..'9' }) return null
        return t.toLongOrNull()?.takeIf { it > 0 }
    }

    fun strictUtf8(data: ByteArray, off: Int, len: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data, off, len))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    fun utf8Lenient(data: ByteArray, off: Int, len: Int): String = String(data, off, len, Charsets.UTF_8)

    /** ISO-8859-1 olarak etiketlenmiş ama gerçekte UTF-8 olan metinleri de doğru çözer; aksi hâlde cp1252. */
    fun latinOrUtf8(data: ByteArray, off: Int, len: Int): String {
        var high = false
        for (i in off until off + len) if (data[i] < 0) { high = true; break }
        if (!high) return String(data, off, len, Charsets.ISO_8859_1)
        strictUtf8(data, off, len)?.let { return it }
        return String(data, off, len, CP1252)
    }

    /**
     * ID3v2 metin alanı: [enc] 0 ISO-8859-1, 1 UTF-16 (BOM), 2 UTF-16BE, 3 UTF-8. Çoklu değerlerde (NUL ayraçlı)
     * ilk dolu değeri verir. [off, end) aralığı kodlama baytından SONRAki metindir.
     */
    fun decodeId3(enc: Int, data: ByteArray, off: Int, end: Int): String? {
        if (off >= end) return null
        var start = off
        while (start < end) {
            val (value, next) = decodeOne(enc, data, start, end)
            clean(value)?.let { return it }
            if (next <= start) break
            start = next
        }
        return null
    }

    private fun decodeOne(enc: Int, d: ByteArray, off: Int, end: Int): Pair<String, Int> {
        return when (enc) {
            1, 2 -> {
                var s = off
                var bigEndian = enc == 2
                if (enc == 1 && end - s >= 2) {
                    val b0 = d.u8(s); val b1 = d.u8(s + 1)
                    if (b0 == 0xFF && b1 == 0xFE) { bigEndian = false; s += 2 }
                    else if (b0 == 0xFE && b1 == 0xFF) { bigEndian = true; s += 2 }
                    else bigEndian = b0 == 0 && b1 != 0 // BOM'suz: yüksek bayt önce ise BE
                }
                var e = s
                while (e + 1 < end && !(d[e].toInt() == 0 && d[e + 1].toInt() == 0)) e += 2
                val cs = if (bigEndian) Charsets.UTF_16BE else Charsets.UTF_16LE
                val len = (e - s) and 1.inv()
                String(d, s, len, cs) to minOf(e + 2, end)
            }
            else -> {
                var e = off
                while (e < end && d[e].toInt() != 0) e++
                val text = if (enc == 3) {
                    strictUtf8(d, off, e - off) ?: utf8Lenient(d, off, e - off)
                } else {
                    latinOrUtf8(d, off, e - off)
                }
                text to minOf(e + 1, end)
            }
        }
    }
}

/** Vorbis yorum bloğu (FLAC VORBIS_COMMENT, Ogg Vorbis/Opus yorum paketi): küçük-endian uzunluklu "ANAHTAR=değer". */
internal object VorbisComments {
    private const val MAX_COMMENTS = 100_000

    fun parseInto(data: ByteArray, start: Int, end: Int, out: TagBuilder) {
        var p = start
        if (p + 4 > end) return
        val vendorLen = data.le32(p)
        p += 4
        if (vendorLen < 0 || vendorLen > end - p) return
        p += vendorLen.toInt()
        if (p + 4 > end) return
        var count = data.le32(p)
        p += 4
        if (count > MAX_COMMENTS) count = MAX_COMMENTS.toLong()
        var i = 0L
        while (i < count && p + 4 <= end) {
            val len = data.le32(p)
            p += 4
            if (len < 0 || len > end - p) return
            val n = len.toInt()
            if (n <= MAX_TEXT_FIELD_BYTES) apply(data, p, n, out)
            p += n
            i++
        }
    }

    private fun apply(d: ByteArray, off: Int, len: Int, out: TagBuilder) {
        var eq = -1
        for (k in off until off + len) if (d[k].toInt() == '='.code) { eq = k; break }
        if (eq <= off) return
        val key = String(d, off, eq - off, Charsets.ISO_8859_1).uppercase(java.util.Locale.ROOT)
        when (key) {
            "ARTIST", "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST", "ALBUM", "TITLE",
            "TRACKNUMBER", "TRACK", "DISCNUMBER", "DISC" -> Unit
            else -> return
        }
        val value = TagText.strictUtf8(d, eq + 1, off + len - eq - 1)
            ?: TagText.utf8Lenient(d, eq + 1, off + len - eq - 1)
        when (key) {
            "ARTIST" -> out.artist(value)
            "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST" -> out.albumArtist(value)
            "ALBUM" -> out.album(value)
            "TITLE" -> out.title(value)
            "TRACKNUMBER", "TRACK" -> out.track(value)
            "DISCNUMBER", "DISC" -> out.disc(value)
        }
    }
}
