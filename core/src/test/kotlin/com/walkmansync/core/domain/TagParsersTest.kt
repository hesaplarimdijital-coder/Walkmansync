package com.walkmansync.core.domain

import com.walkmansync.core.fs.ByteArrayRandomAccessSource
import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.tags.parsers.CompositeTagReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Random

class TagParsersTest {
    private val reader = CompositeTagReader()

    private fun read(data: ByteArray, format: AudioFormat): AudioTags? =
        ByteArrayRandomAccessSource(data).use { reader.read(it, format) }

    // ---------- ID3v2 / MP3 ----------

    @Test fun id3v23AllEncodingsAndTrackFormats() {
        for (enc in 0..3) {
            val text = if (enc == 0) "Beyonce" else "Beyoncé Işık 日本"
            val tag = Id3Fixture.tag(
                3,
                listOf(
                    Id3Fixture.textFrame(3, "TPE1", text, enc),
                    Id3Fixture.textFrame(3, "TPE2", "Various", enc),
                    Id3Fixture.textFrame(3, "TALB", "Album $text", enc),
                    Id3Fixture.textFrame(3, "TIT2", "Title $text", enc),
                    Id3Fixture.textFrame(3, "TRCK", "3/12", enc),
                    Id3Fixture.textFrame(3, "TPOS", "1/2", enc),
                ),
                padding = 64,
            )
            val t = read(Id3Fixture.mp3(tag, 5), AudioFormat.MP3)!!
            assertEquals("enc=$enc", text, t.artist)
            assertEquals("Various", t.albumArtist)
            assertEquals("Album $text", t.album)
            assertEquals("Title $text", t.title)
            assertEquals(3, t.trackNumber)
            assertEquals(1, t.discNumber)
        }
    }

    @Test fun id3v24SynchsafeSizesMultiValueAndUtf16Be() {
        val tag = Id3Fixture.tag(
            4,
            listOf(
                Id3Fixture.textFrame(4, "TPE1", "First\u0000Second", 3),
                Id3Fixture.textFrame(4, "TIT2", "Şarkı", 2),
                Id3Fixture.textFrame(4, "TRCK", "07", 3),
            ),
            footer = true,
        )
        val t = read(Id3Fixture.mp3(tag, 3), AudioFormat.MP3)!!
        assertEquals("First", t.artist)
        assertEquals("Şarkı", t.title)
        assertEquals(7, t.trackNumber)
    }

    @Test fun id3v22ThreeCharFrames() {
        val tag = Id3Fixture.tag(
            2,
            listOf(
                Id3Fixture.textFrame(2, "TP1", "Old Artist", 0),
                Id3Fixture.textFrame(2, "TAL", "Old Album", 0),
                Id3Fixture.textFrame(2, "TT2", "Old Title", 1),
                Id3Fixture.textFrame(2, "TRK", "4/9", 0),
                Id3Fixture.textFrame(2, "TPA", "2/3", 0),
            ),
        )
        val t = read(Id3Fixture.mp3(tag, 3), AudioFormat.MP3)!!
        assertEquals("Old Artist", t.artist)
        assertEquals("Old Album", t.album)
        assertEquals("Old Title", t.title)
        assertEquals(4, t.trackNumber)
        assertEquals(2, t.discNumber)
    }

    @Test fun id3v23HeaderUnsynchronisationAndExtendedHeader() {
        // UTF-16 BOM (FF FE) unsync sırasında FF 00 FE olur.
        val tag = Id3Fixture.tag(
            3,
            listOf(Id3Fixture.textFrame(3, "TIT2", "Ünsync Tıtle", 1), Id3Fixture.textFrame(3, "TPE1", "Sanatçı", 1)),
            headerUnsync = true,
            extendedHeader = true,
        )
        val t = read(Id3Fixture.mp3(tag, 3), AudioFormat.MP3)!!
        assertEquals("Ünsync Tıtle", t.title)
        assertEquals("Sanatçı", t.artist)
    }

    @Test fun id3SkipsHugeFramesAndPadding() {
        val bigApic = Id3Fixture.frame(3, "APIC", ByteArray(300_000))
        val tag = Id3Fixture.tag(3, listOf(bigApic, Id3Fixture.textFrame(3, "TIT2", "After Picture")), padding = 500)
        val t = read(Id3Fixture.mp3(tag, 3), AudioFormat.MP3)!!
        assertEquals("After Picture", t.title)
    }

    @Test fun id3FrameLargerThanTagStopsSafely() {
        val good = Id3Fixture.textFrame(3, "TIT2", "Good")
        val lying = Id3Fixture.frame(3, "TALB", ByteArray(10)).also {
            it[4] = 0x7F; it[5] = 0x00 // boyut alanını abartılı yap
        }
        val tag = Id3Fixture.tag(3, listOf(good, lying))
        val t = read(Id3Fixture.mp3(tag, 3), AudioFormat.MP3)!!
        assertEquals("Good", t.title)
        assertNull(t.album)
    }

    @Test fun mp3DurationFromXingAndCbrAndVbri() {
        val xing = read(Id3Fixture.mp3(null, 3, xingFrames = 1000), AudioFormat.MP3)!!
        assertEquals(1000L * 1152 * 1000 / 44100, xing.durationMs)

        val cbr = read(Id3Fixture.mp3(null, 100), AudioFormat.MP3)!!
        assertEquals(100L * 417 * 8 / 128, cbr.durationMs)

        val vbriBytes = Id3Fixture.mpegFrame(vbri = 500) + Id3Fixture.mpegFrame() + Id3Fixture.mpegFrame()
        val vbri = read(vbriBytes, AudioFormat.MP3)!!
        assertEquals(500L * 1152 * 1000 / 44100, vbri.durationMs)
    }

    @Test fun mp3CbrIgnoresId3v1TrailerAndSkipsId3v2() {
        val tag = Id3Fixture.tag(3, listOf(Id3Fixture.textFrame(3, "TIT2", "T")), padding = 2000)
        val v1 = Id3Fixture.id3v1("V1 Title", "V1 Artist", "V1 Album", 5)
        val t = read(Id3Fixture.mp3(tag, 50, v1 = v1), AudioFormat.MP3)!!
        assertEquals("T", t.title) // v2 kazanır
        assertEquals("V1 Artist", t.artist) // eksikler v1'den
        assertEquals("V1 Album", t.album)
        assertEquals(5, t.trackNumber)
        assertEquals(50L * 417 * 8 / 128, t.durationMs)
    }

    @Test fun id3v1Only() {
        val t = read(Id3Fixture.mp3(null, 10, v1 = Id3Fixture.id3v1("Solo", "Artist", "Alb", 0)), AudioFormat.MP3)!!
        assertEquals("Solo", t.title)
        assertNull(t.trackNumber)
    }

    @Test fun tlenUsedWhenNoFrameFound() {
        val tag = Id3Fixture.tag(3, listOf(Id3Fixture.textFrame(3, "TIT2", "T"), Id3Fixture.textFrame(3, "TLEN", "123456", 0)))
        val t = read(tag + ByteArray(2000), AudioFormat.MP3)!!
        assertEquals(123456L, t.durationMs)
    }

    // ---------- FLAC ----------

    @Test fun flacStreamInfoAndVorbisComment() {
        val comments = FlacFixture.vorbisComment(
            "artist" to "Sanatçı", "ALBUMARTIST" to "Grup", "Album" to "Albüm", "TITLE" to "Şarkı",
            "TRACKNUMBER" to "3/12", "DISCNUMBER" to "2",
        )
        val data = FlacFixture.file(44100, 441000L * 3, comments, padding = 4096, pictureBytes = 100_000)
        val t = read(data, AudioFormat.FLAC)!!
        assertEquals("Sanatçı", t.artist)
        assertEquals("Grup", t.albumArtist)
        assertEquals("Albüm", t.album)
        assertEquals("Şarkı", t.title)
        assertEquals(3, t.trackNumber)
        assertEquals(2, t.discNumber)
        assertEquals(30_000L, t.durationMs)
    }

    @Test fun flacWithLeadingId3() {
        val id3 = Id3Fixture.tag(3, listOf(Id3Fixture.textFrame(3, "TIT2", "From ID3")))
        val data = FlacFixture.file(96000, 96000L * 10, FlacFixture.vorbisComment("TITLE" to "From Vorbis"), id3 = id3)
        val t = read(data, AudioFormat.FLAC)!!
        assertEquals("From ID3", t.title) // ID3 önce gelir, ilk dolu kazanır
        assertEquals(10_000L, t.durationMs)
    }

    @Test fun flacSixtyFourBitSampleCountHighBits() {
        val data = FlacFixture.file(48000, (1L shl 33) + 48000L, null)
        val t = read(data, AudioFormat.FLAC)!!
        assertEquals(((1L shl 33) + 48000L) * 1000 / 48000, t.durationMs)
    }

    // ---------- MP4 ----------

    @Test fun mp4IlstAndMvhd() {
        val items = listOf(
            Mp4Fixture.text("©ART", "Sanatçı"), Mp4Fixture.text("aART", "Albüm Sanatçısı"),
            Mp4Fixture.text("©alb", "Albüm"), Mp4Fixture.text("©nam", "Parça"),
            Mp4Fixture.trkn(5, 11), Mp4Fixture.disk(2, 3),
        )
        val t = read(Mp4Fixture.file(items, timescale = 44100, duration = 441000L * 2, mdatBefore = 5000), AudioFormat.M4A)!!
        assertEquals("Sanatçı", t.artist)
        assertEquals("Albüm Sanatçısı", t.albumArtist)
        assertEquals("Albüm", t.album)
        assertEquals("Parça", t.title)
        assertEquals(5, t.trackNumber)
        assertEquals(2, t.discNumber)
        assertEquals(20_000L, t.durationMs)
    }

    @Test fun mp4With64BitAtomsAndVersion1Mvhd() {
        val t = read(
            Mp4Fixture.file(listOf(Mp4Fixture.text("©nam", "Big")), timescale = 1000, duration = 61_000, mdatBefore = 100, big = true, mvhdVersion = 1),
            AudioFormat.ALAC,
        )!!
        assertEquals("Big", t.title)
        assertEquals(61_000L, t.durationMs)
    }

    // ---------- Ogg ----------

    @Test fun oggVorbisCommentsAndDuration() {
        val packets = listOf(
            OggFixture.vorbisIdent(44100),
            OggFixture.vorbisComments("ARTIST" to "Ogg Artist", "TITLE" to "Ogg Title", "ALBUM" to "Ogg Album", "TRACKNUMBER" to "2"),
            ByteArray(400), ByteArray(300),
        )
        val t = read(OggFixture.file(packets, 44100L * 7), AudioFormat.OGG)!!
        assertEquals("Ogg Artist", t.artist)
        assertEquals("Ogg Title", t.title)
        assertEquals(2, t.trackNumber)
        assertEquals(7000L, t.durationMs)
    }

    @Test fun oggCommentPacketSpansMultiplePages() {
        val long = "x".repeat(70_000)
        val packets = listOf(
            OggFixture.vorbisIdent(48000),
            OggFixture.vorbisComments("TITLE" to "Multi Page", "COMMENT" to long, "ARTIST" to "After Long"),
            ByteArray(50), ByteArray(50),
        )
        val file = OggFixture.file(packets, 48000L * 3)
        val t = read(file, AudioFormat.OGG)!!
        assertEquals("Multi Page", t.title)
        assertEquals("After Long", t.artist)
        assertEquals(3000L, t.durationMs)
    }

    @Test fun opusTagsAndPreSkip() {
        val packets = listOf(
            OggFixture.opusHead(312),
            OggFixture.opusTags("ARTIST" to "Opus Artist", "TITLE" to "Opus Title", "DISCNUMBER" to "1/2"),
            ByteArray(100),
        )
        val t = read(OggFixture.file(packets, 48000L * 10 + 312), AudioFormat.OPUS)!!
        assertEquals("Opus Artist", t.artist)
        assertEquals(1, t.discNumber)
        assertEquals(10_000L, t.durationMs)
    }

    // ---------- WAV ----------

    @Test fun wavInfoAndDuration() {
        val info = RiffFixture.info("IART" to "Wav Artist", "INAM" to "Wav Title", "IPRD" to "Wav Album", "ITRK" to "4")
        val t = read(RiffFixture.file(8000, 1, 8, 24_000, info), AudioFormat.WAV)!!
        assertEquals("Wav Artist", t.artist)
        assertEquals("Wav Title", t.title)
        assertEquals("Wav Album", t.album)
        assertEquals(4, t.trackNumber)
        assertEquals(3000L, t.durationMs)
    }

    @Test fun wavId3ChunkPreferredOverInfo() {
        val id3 = RiffFixture.id3Chunk(Id3Fixture.tag(3, listOf(Id3Fixture.textFrame(3, "TIT2", "ID3 Title"))))
        val info = RiffFixture.info("INAM" to "INFO Title", "IART" to "INFO Artist")
        val t = read(RiffFixture.file(8000, 1, 8, 8_000, info, id3), AudioFormat.WAV)!!
        assertEquals("ID3 Title", t.title)
        assertEquals("INFO Artist", t.artist)
        assertEquals(1000L, t.durationMs)
    }

    // ---------- DSD ----------

    @Test fun dsfId3AtMetadataPointerAndDuration() {
        val id3 = Id3Fixture.tag(4, listOf(Id3Fixture.textFrame(4, "TIT2", "DSF Title"), Id3Fixture.textFrame(4, "TPE1", "DSF Artist")))
        val t = read(DsdFixture.dsf(2_822_400, 2_822_400L * 5, id3), AudioFormat.DSF)!!
        assertEquals("DSF Title", t.title)
        assertEquals("DSF Artist", t.artist)
        assertEquals(5000L, t.durationMs)
    }

    @Test fun dffId3ChunkAndDurationFromDsdSize() {
        val id3 = Id3Fixture.tag(3, listOf(Id3Fixture.textFrame(3, "TIT2", "DFF Title")))
        // 2 kanal, 2.8224 MHz: saniyede 2.8224e6*2/8 = 705600 bayt
        val t = read(DsdFixture.dff(2_822_400, 2, 705_600 * 2, id3, artist = "Diin Artist"), AudioFormat.DFF)!!
        assertEquals("DFF Title", t.title)
        assertEquals("Diin Artist", t.artist)
        assertEquals(2000L, t.durationMs)
    }

    // ---------- AAC ----------

    @Test fun aacAdtsDurationAndId3() {
        val id3 = Id3Fixture.tag(3, listOf(Id3Fixture.textFrame(3, "TIT2", "AAC Title")))
        val t = read(AdtsFixture.file(200, 100, id3), AudioFormat.AAC)!!
        assertEquals("AAC Title", t.title)
        assertEquals(200L * 1024 * 1000 / 44100, t.durationMs)
    }

    @Test fun aacEstimatesDurationForLargeFiles() {
        val t = read(AdtsFixture.file(30_000, 100), AudioFormat.AAC)!! // 3 MB > 1 MB örnekleme sınırı
        val exact = 30_000L * 1024 * 1000 / 44100
        val got = t.durationMs!!
        assertTrue("tahmin $got, gerçek $exact", Math.abs(got - exact) < exact / 50)
    }

    // ---------- Sihirli bayt, bozuk veri, sınırlar ----------

    @Test fun magicBytesBeatWrongExtension() {
        val flac = FlacFixture.file(44100, 44100, FlacFixture.vorbisComment("TITLE" to "Mislabelled"))
        assertEquals("Mislabelled", read(flac, AudioFormat.MP3)!!.title)
    }

    @Test fun nonAudioFormatsReturnNull() {
        assertNull(read(ByteArray(100), AudioFormat.LRC))
        assertNull(read(ByteArray(100), AudioFormat.COVER))
        assertNull(read(ByteArray(100), AudioFormat.M3U8))
    }

    @Test fun truncatedAndGarbageNeverThrow() {
        val samples = listOf(
            AudioFormat.MP3 to Id3Fixture.mp3(Id3Fixture.tag(3, listOf(Id3Fixture.textFrame(3, "TIT2", "T"))), 5, xingFrames = 10),
            AudioFormat.FLAC to FlacFixture.file(44100, 44100, FlacFixture.vorbisComment("TITLE" to "T")),
            AudioFormat.M4A to Mp4Fixture.file(listOf(Mp4Fixture.text("©nam", "T"), Mp4Fixture.trkn(1, 2))),
            AudioFormat.OGG to OggFixture.file(listOf(OggFixture.vorbisIdent(44100), OggFixture.vorbisComments("TITLE" to "T"), ByteArray(10)), 1000),
            AudioFormat.OPUS to OggFixture.file(listOf(OggFixture.opusHead(0), OggFixture.opusTags("TITLE" to "T"), ByteArray(10)), 1000),
            AudioFormat.WAV to RiffFixture.file(8000, 1, 8, 100, RiffFixture.info("INAM" to "T")),
            AudioFormat.DSF to DsdFixture.dsf(2_822_400, 2_822_400, Id3Fixture.tag(3, listOf(Id3Fixture.textFrame(3, "TIT2", "T")))),
            AudioFormat.DFF to DsdFixture.dff(2_822_400, 2, 1000, null, "A"),
            AudioFormat.AAC to AdtsFixture.file(20),
        )
        val rnd = Random(42)
        for ((format, data) in samples) {
            for (cut in data.indices step maxOf(1, data.size / 97)) {
                read(data.copyOf(cut), format) // istisna kaçmamalı
            }
            repeat(60) {
                val mutated = data.copyOf()
                repeat(1 + rnd.nextInt(6)) { mutated[rnd.nextInt(mutated.size)] = rnd.nextInt(256).toByte() }
                read(mutated, format)
            }
            val noise = ByteArray(5000).also { rnd.nextBytes(it) }
            read(noise, format)
            read(ByteArray(0), format)
        }
    }

    @Test fun absurdLengthFieldsAreIgnored() {
        // Vorbis yorum sayısı ve uzunluğu devasa.
        val evil = FlacFixture.vorbisComment("TITLE" to "ok").also {
            // yorum sayısını 0xFFFFFFFF yap (vendor "test" = 4 bayt → sayı alanı 8. konumda)
            for (i in 8 until 12) it[i] = 0xFF.toByte()
        }
        val t = read(FlacFixture.file(44100, 44100, evil), AudioFormat.FLAC)
        assertEquals("ok", t?.title)
        // MP4 atomu dosyadan büyük.
        val m4 = Mp4Fixture.file(listOf(Mp4Fixture.text("©nam", "X")))
        m4[0] = 0x7F // ftyp boyutu devasa → tüm dosya tek atom sayılır
        read(m4, AudioFormat.M4A)
    }

    @Test fun ioExceptionPropagates() {
        val failing = object : com.walkmansync.core.fs.RandomAccessSource {
            override val size: Long = 1000
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("kart çıktı")
            override fun close() = Unit
        }
        try {
            reader.read(failing, AudioFormat.MP3)
            throw AssertionError("IOException bekleniyordu")
        } catch (e: IOException) {
            assertEquals("kart çıktı", e.message)
        }
    }

    @Test fun supportedFormatsCoverAllAudio() {
        assertEquals(AudioFormat.entries.filter { it.isAudio }.toSet(), reader.formats)
        assertNotNull(reader)
    }
}
