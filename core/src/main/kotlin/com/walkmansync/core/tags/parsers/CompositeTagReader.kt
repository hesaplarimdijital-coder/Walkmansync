package com.walkmansync.core.tags.parsers

import com.walkmansync.core.fs.RandomAccessSource
import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.tags.TagReader
import java.io.IOException

/**
 * Tüm saf Kotlin ayrıştırıcıları uzantı + sihirli bayt ile seçer. Bozuk veri → null/kısmi sonuç;
 * yalnızca [IOException] yayılır.
 */
class CompositeTagReader : TagReader {

    override val formats: Set<AudioFormat> = AudioFormat.entries.filter { it.isAudio }.toSet()

    private enum class Kind { MP3, AAC, FLAC, MP4, OGG, WAV, DSF, DFF }

    override fun read(source: RandomAccessSource, format: AudioFormat): AudioTags? {
        if (!format.isAudio) return null
        return try {
            val reader = BoundedReader(source)
            val kind = sniff(reader) ?: declared(format) ?: return null
            val tags = when (kind) {
                Kind.MP3 -> Mp3TagParser.parse(reader)
                Kind.AAC -> AacParser.parse(reader)
                Kind.FLAC -> FlacParser.parse(reader)
                Kind.MP4 -> Mp4Parser.parse(reader)
                Kind.OGG -> OggParser.parse(reader)
                Kind.WAV -> RiffParser.parse(reader)
                Kind.DSF -> DsfParser.parse(reader)
                Kind.DFF -> DffParser.parse(reader)
            }
            tags?.takeUnless { it.isEmpty }
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            null // bozuk dosya: ayrıştırıcı hatası etiketsiz sayılır
        }
    }

    private fun declared(format: AudioFormat): Kind? = when (format) {
        AudioFormat.FLAC -> Kind.FLAC
        AudioFormat.DSF -> Kind.DSF
        AudioFormat.DFF -> Kind.DFF
        AudioFormat.WAV -> Kind.WAV
        AudioFormat.M4A, AudioFormat.ALAC -> Kind.MP4
        AudioFormat.MP3 -> Kind.MP3
        AudioFormat.OGG, AudioFormat.OPUS -> Kind.OGG
        AudioFormat.AAC -> Kind.AAC
        else -> null
    }

    /** İlk baytlara bakarak kapsayıcıyı belirler; tanınmazsa null (bildirilen biçime düşülür). */
    private fun sniff(reader: BoundedReader): Kind? {
        val h = reader.readUpTo(0, 16)
        if (h.size < 4) return null
        return when {
            h.startsWithAscii(0, "fLaC") -> Kind.FLAC
            h.startsWithAscii(0, "OggS") -> Kind.OGG
            h.startsWithAscii(0, "DSD ") -> Kind.DSF
            h.startsWithAscii(0, "FRM8") -> Kind.DFF
            (h.startsWithAscii(0, "RIFF") || h.startsWithAscii(0, "RF64")) && h.startsWithAscii(8, "WAVE") -> Kind.WAV
            h.size >= 8 && h.startsWithAscii(4, "ftyp") -> Kind.MP4
            h.startsWithAscii(0, "ID3") -> sniffAfterId3(reader)
            looksLikeAdts(h) -> Kind.AAC
            MpegAudio.parseHeader(h, 0) != null -> Kind.MP3
            else -> null
        }
    }

    private fun sniffAfterId3(reader: BoundedReader): Kind? {
        val probe = TagBuilder()
        val pos = Id3v2.parseLeading(reader, probe)
        val h = reader.readUpTo(pos, 12)
        if (h.size < 4) return null
        return when {
            h.startsWithAscii(0, "fLaC") -> Kind.FLAC
            h.startsWithAscii(0, "OggS") -> Kind.OGG
            h.size >= 8 && h.startsWithAscii(4, "ftyp") -> Kind.MP4
            looksLikeAdts(h) -> Kind.AAC
            else -> null // MPEG ses (ya da bilinmiyor): bildirilen biçime göre
        }
    }

    /** MPEG katman bitleri 00 ve sync → ADTS. */
    private fun looksLikeAdts(h: ByteArray): Boolean =
        h.size >= 2 && h.u8(0) == 0xFF && (h.u8(1) and 0xF6) == 0xF0
}
