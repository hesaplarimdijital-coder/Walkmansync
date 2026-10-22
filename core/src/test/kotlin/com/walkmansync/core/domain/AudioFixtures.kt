package com.walkmansync.core.domain

import java.io.ByteArrayOutputStream

/** Testler için bellekte üretilen küçük ikili örnekler (gerçek dosya gerekmez). */
object Bin {
    fun cat(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        for (p in parts) o.write(p)
        return o.toByteArray()
    }

    fun ascii(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
    fun be16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())
    fun be24(v: Int): ByteArray = byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    fun be32(v: Long): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    fun be64(v: Long): ByteArray = cat(be32(v ushr 32), be32(v and 0xFFFFFFFFL))
    fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte())
    fun le32(v: Long): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    fun le64(v: Long): ByteArray = cat(le32(v and 0xFFFFFFFFL), le32(v ushr 32))
    fun zeros(n: Int): ByteArray = ByteArray(n)
}

object Id3Fixture {
    fun encode(enc: Int, text: String): ByteArray = when (enc) {
        0 -> text.toByteArray(Charsets.ISO_8859_1)
        1 -> Bin.cat(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), text.toByteArray(Charsets.UTF_16LE))
        2 -> text.toByteArray(Charsets.UTF_16BE)
        else -> text.toByteArray(Charsets.UTF_8)
    }

    private fun synchsafe(v: Int): ByteArray =
        byteArrayOf(((v shr 21) and 0x7F).toByte(), ((v shr 14) and 0x7F).toByte(), ((v shr 7) and 0x7F).toByte(), (v and 0x7F).toByte())

    /** Bir metin çerçevesi; [version] 2, 3 ya da 4. v2'de [id] 3 harfli olmalıdır. */
    fun textFrame(version: Int, id: String, text: String, enc: Int = 3): ByteArray =
        frame(version, id, Bin.cat(byteArrayOf(enc.toByte()), encode(enc, text)))

    fun frame(version: Int, id: String, body: ByteArray, flags: Int = 0): ByteArray = when (version) {
        2 -> Bin.cat(Bin.ascii(id), Bin.be24(body.size), body)
        3 -> Bin.cat(Bin.ascii(id), Bin.be32(body.size.toLong()), Bin.be16(flags), body)
        else -> Bin.cat(Bin.ascii(id), synchsafe(body.size), Bin.be16(flags), body)
    }

    fun unsync(data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        for (i in data.indices) {
            o.write(data[i].toInt())
            val next = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            if (data[i].toInt() == -1 && (next == 0 || next >= 0xE0)) o.write(0)
        }
        return o.toByteArray()
    }

    fun tag(
        version: Int,
        frames: List<ByteArray>,
        padding: Int = 0,
        headerUnsync: Boolean = false,
        extendedHeader: Boolean = false,
        footer: Boolean = false,
    ): ByteArray {
        var body = Bin.cat(*frames.toTypedArray())
        if (headerUnsync) body = unsync(body)
        if (extendedHeader) {
            val ext = if (version == 3) Bin.cat(Bin.be32(6), Bin.be16(0), Bin.be32(0)) else Bin.cat(synchsafe(6), byteArrayOf(1, 0))
            body = Bin.cat(ext, body)
        }
        body = Bin.cat(body, Bin.zeros(padding))
        var flags = 0
        if (headerUnsync) flags = flags or 0x80
        if (extendedHeader) flags = flags or 0x40
        if (footer) flags = flags or 0x10
        val header = Bin.cat(Bin.ascii("ID3"), byteArrayOf(version.toByte(), 0, flags.toByte()), synchsafe(body.size))
        val foot = if (footer) Bin.cat(Bin.ascii("3DI"), byteArrayOf(version.toByte(), 0, flags.toByte()), synchsafe(body.size)) else ByteArray(0)
        return Bin.cat(header, body, foot)
    }

    /** MPEG1 Layer III, 128 kbps, 44.1 kHz, stereo çerçevesi (417 bayt). [xingFrames] verilirse Xing başlığı içerir. */
    fun mpegFrame(xingFrames: Long? = null, vbri: Long? = null): ByteArray {
        val f = ByteArray(417)
        f[0] = 0xFF.toByte(); f[1] = 0xFB.toByte(); f[2] = 0x90.toByte(); f[3] = 0x00
        if (xingFrames != null) {
            System.arraycopy(Bin.ascii("Xing"), 0, f, 36, 4)
            System.arraycopy(Bin.be32(1), 0, f, 40, 4)
            System.arraycopy(Bin.be32(xingFrames), 0, f, 44, 4)
        }
        if (vbri != null) {
            System.arraycopy(Bin.ascii("VBRI"), 0, f, 36, 4)
            System.arraycopy(Bin.be32(vbri), 0, f, 36 + 14, 4)
        }
        return f
    }

    fun mp3(id3: ByteArray?, frames: Int, xingFrames: Long? = null, v1: ByteArray? = null): ByteArray {
        val body = ByteArrayOutputStream()
        if (id3 != null) body.write(id3)
        for (i in 0 until frames) body.write(mpegFrame(if (i == 0) xingFrames else null))
        if (v1 != null) body.write(v1)
        return body.toByteArray()
    }

    fun id3v1(title: String, artist: String, album: String, track: Int): ByteArray {
        val b = ByteArray(128)
        System.arraycopy(Bin.ascii("TAG"), 0, b, 0, 3)
        fun put(off: Int, s: String) { val d = s.toByteArray(Charsets.ISO_8859_1); System.arraycopy(d, 0, b, off, minOf(30, d.size)) }
        put(3, title); put(33, artist); put(63, album)
        b[125] = 0; b[126] = track.toByte()
        return b
    }
}

object FlacFixture {
    fun streamInfo(sampleRate: Int, totalSamples: Long): ByteArray {
        val packed = (sampleRate.toLong() shl 44) or (1L shl 41) or (15L shl 36) or totalSamples
        return Bin.cat(Bin.be16(4096), Bin.be16(4096), Bin.be24(0), Bin.be24(0), Bin.be64(packed), Bin.zeros(16))
    }

    fun vorbisComment(vararg pairs: Pair<String, String>, vendor: String = "test"): ByteArray {
        val o = ByteArrayOutputStream()
        val v = vendor.toByteArray()
        o.write(Bin.le32(v.size.toLong())); o.write(v)
        o.write(Bin.le32(pairs.size.toLong()))
        for ((k, value) in pairs) {
            val e = "$k=$value".toByteArray(Charsets.UTF_8)
            o.write(Bin.le32(e.size.toLong())); o.write(e)
        }
        return o.toByteArray()
    }

    private fun block(type: Int, last: Boolean, data: ByteArray): ByteArray =
        Bin.cat(byteArrayOf((type or (if (last) 0x80 else 0)).toByte()), Bin.be24(data.size), data)

    fun file(
        sampleRate: Int, totalSamples: Long, comments: ByteArray?, padding: Int = 0, pictureBytes: Int = 0, id3: ByteArray? = null,
    ): ByteArray {
        val parts = ArrayList<ByteArray>()
        id3?.let { parts += it }
        parts += Bin.ascii("fLaC")
        parts += block(0, false, streamInfo(sampleRate, totalSamples))
        if (pictureBytes > 0) parts += block(6, false, Bin.zeros(pictureBytes))
        if (padding > 0) parts += block(1, comments == null, Bin.zeros(padding))
        if (comments != null) parts += block(4, true, comments)
        parts += Bin.zeros(100) // sahte ses verisi
        return Bin.cat(*parts.toTypedArray())
    }
}

object Mp4Fixture {
    fun atom(type: String, payload: ByteArray): ByteArray = Bin.cat(Bin.be32(8L + payload.size), Bin.ascii(type), payload)

    /** 64-bit boyutlu atom (size=1 + largesize). */
    fun atom64(type: String, payload: ByteArray): ByteArray =
        Bin.cat(Bin.be32(1), Bin.ascii(type), Bin.be64(16L + payload.size), payload)

    fun dataAtom(typeCode: Int, payload: ByteArray): ByteArray =
        atom("data", Bin.cat(Bin.be32(typeCode.toLong()), Bin.be32(0), payload))

    fun text(key: String, value: String): ByteArray =
        atom(key, dataAtom(1, value.toByteArray(Charsets.UTF_8)))

    fun trkn(track: Int, total: Int): ByteArray =
        atom("trkn", dataAtom(0, Bin.cat(Bin.be16(0), Bin.be16(track), Bin.be16(total), Bin.be16(0))))

    fun disk(disc: Int, total: Int): ByteArray =
        atom("disk", dataAtom(0, Bin.cat(Bin.be16(0), Bin.be16(disc), Bin.be16(total))))

    fun mvhd(timescale: Long, duration: Long, version: Int = 0): ByteArray =
        if (version == 0) atom("mvhd", Bin.cat(Bin.be32(0), Bin.be32(0), Bin.be32(0), Bin.be32(timescale), Bin.be32(duration), Bin.zeros(80)))
        else atom("mvhd", Bin.cat(Bin.be32(0x01000000L), Bin.be64(0), Bin.be64(0), Bin.be32(timescale), Bin.be64(duration), Bin.zeros(80)))

    fun file(ilstItems: List<ByteArray>, timescale: Long = 44100, duration: Long = 441000, mdatBefore: Int = 0, big: Boolean = false, mvhdVersion: Int = 0): ByteArray {
        val ilst = atom("ilst", Bin.cat(*ilstItems.toTypedArray()))
        val hdlr = atom("hdlr", Bin.cat(Bin.be32(0), Bin.ascii("mdir"), Bin.zeros(12)))
        val meta = atom("meta", Bin.cat(Bin.be32(0), hdlr, ilst))
        val udta = atom("udta", meta)
        val moov = atom("moov", Bin.cat(mvhd(timescale, duration, mvhdVersion), udta))
        val ftyp = atom("ftyp", Bin.cat(Bin.ascii("M4A "), Bin.be32(0), Bin.ascii("M4A mp42isom")))
        val mdat = if (big) atom64("mdat", Bin.zeros(mdatBefore)) else atom("mdat", Bin.zeros(mdatBefore))
        return Bin.cat(ftyp, mdat, moov)
    }
}

object OggFixture {
    /** Paketleri sayfalara böler (ilk paket tek başına BOS sayfasında). Son sayfanın granule'ı [lastGranule]. */
    fun file(packets: List<ByteArray>, lastGranule: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var seq = 0
        fun page(segs: List<Int>, data: ByteArray, headerType: Int, granule: Long) {
            out.write(Bin.ascii("OggS")); out.write(0); out.write(headerType)
            out.write(Bin.le64(granule)); out.write(Bin.le32(1234)); out.write(Bin.le32((seq++).toLong())); out.write(Bin.le32(0))
            out.write(segs.size)
            for (s in segs) out.write(s)
            out.write(data)
        }
        page(listOf(packets[0].size), packets[0], 0x02, 0)
        val pages = ArrayList<Triple<List<Int>, ByteArray, Boolean>>()
        var curSegs = ArrayList<Int>()
        var curData = ByteArrayOutputStream()
        var continued = false
        for (pkt in packets.drop(1)) {
            var off = 0
            val segLens = List(pkt.size / 255) { 255 } + (pkt.size % 255)
            for (len in segLens) {
                curSegs.add(len)
                curData.write(pkt, off, len)
                off += len
                if (curSegs.size == 255) {
                    pages.add(Triple(curSegs, curData.toByteArray(), continued))
                    continued = len == 255
                    curSegs = ArrayList()
                    curData = ByteArrayOutputStream()
                }
            }
        }
        if (curSegs.isNotEmpty()) pages.add(Triple(curSegs, curData.toByteArray(), continued))
        pages.forEachIndexed { i, (s, d, cont) ->
            page(s, d, if (cont) 0x01 else 0x00, if (i == pages.size - 1) lastGranule else -1L)
        }
        return out.toByteArray()
    }

    fun vorbisIdent(sampleRate: Long): ByteArray =
        Bin.cat(byteArrayOf(1), Bin.ascii("vorbis"), Bin.le32(0), byteArrayOf(2), Bin.le32(sampleRate), Bin.le32(0), Bin.le32(0), Bin.le32(0), byteArrayOf(0xB8.toByte(), 1))

    fun vorbisComments(vararg pairs: Pair<String, String>): ByteArray =
        Bin.cat(byteArrayOf(3), Bin.ascii("vorbis"), FlacFixture.vorbisComment(*pairs), byteArrayOf(1))

    fun opusHead(preSkip: Int): ByteArray =
        Bin.cat(Bin.ascii("OpusHead"), byteArrayOf(1, 2), Bin.le16(preSkip), Bin.le32(44100), Bin.le16(0), byteArrayOf(0))

    fun opusTags(vararg pairs: Pair<String, String>): ByteArray =
        Bin.cat(Bin.ascii("OpusTags"), FlacFixture.vorbisComment(*pairs))
}

object RiffFixture {
    private fun chunk(id: String, data: ByteArray): ByteArray =
        Bin.cat(Bin.ascii(id), Bin.le32(data.size.toLong()), data, if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

    fun fmt(sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val align = channels * bits / 8
        return chunk("fmt ", Bin.cat(Bin.le16(1), Bin.le16(channels), Bin.le32(sampleRate.toLong()), Bin.le32((sampleRate * align).toLong()), Bin.le16(align), Bin.le16(bits)))
    }

    fun info(vararg pairs: Pair<String, String>): ByteArray {
        val subs = pairs.map { (k, v) -> chunk(k, Bin.cat(v.toByteArray(Charsets.UTF_8), byteArrayOf(0))) }
        return chunk("LIST", Bin.cat(Bin.ascii("INFO"), *subs.toTypedArray()))
    }

    fun id3Chunk(tag: ByteArray): ByteArray = chunk("id3 ", tag)

    fun file(sampleRate: Int, channels: Int, bits: Int, dataBytes: Int, vararg extra: ByteArray): ByteArray {
        val body = Bin.cat(Bin.ascii("WAVE"), fmt(sampleRate, channels, bits), chunk("data", Bin.zeros(dataBytes)), *extra)
        return Bin.cat(Bin.ascii("RIFF"), Bin.le32(body.size.toLong()), body)
    }
}

object DsdFixture {
    fun dsf(samplingFrequency: Long, sampleCount: Long, id3: ByteArray?): ByteArray {
        val dataChunk = Bin.cat(Bin.ascii("data"), Bin.le64(12L + 4096), Bin.zeros(4096))
        val fmt = Bin.cat(
            Bin.ascii("fmt "), Bin.le64(52), Bin.le32(1), Bin.le32(0), Bin.le32(2), Bin.le32(2),
            Bin.le32(samplingFrequency), Bin.le32(1), Bin.le64(sampleCount), Bin.le32(4096), Bin.le32(0),
        )
        val metaPtr = 28L + fmt.size + dataChunk.size
        val total = metaPtr + (id3?.size ?: 0)
        val header = Bin.cat(Bin.ascii("DSD "), Bin.le64(28), Bin.le64(total), Bin.le64(if (id3 != null) metaPtr else 0))
        return Bin.cat(header, fmt, dataChunk, id3 ?: ByteArray(0))
    }

    private fun chunk(id: String, data: ByteArray): ByteArray =
        Bin.cat(Bin.ascii(id), Bin.be64(data.size.toLong()), data, if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0))

    fun dff(sampleRate: Long, channels: Int, dsdBytes: Int, id3: ByteArray?, artist: String? = null): ByteArray {
        val prop = chunk(
            "PROP",
            Bin.cat(Bin.ascii("SND "), chunk("FS  ", Bin.be32(sampleRate)), chunk("CHNL", Bin.cat(Bin.be16(channels), Bin.zeros(channels * 4)))),
        )
        val diin = if (artist == null) ByteArray(0) else {
            val t = artist.toByteArray(Charsets.UTF_8)
            val diar = Bin.cat(Bin.ascii("DIAR"), Bin.be32(4L + t.size), Bin.be32(t.size.toLong()), t, if (t.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
            chunk("DIIN", diar)
        }
        val body = Bin.cat(
            Bin.ascii("DSD "), chunk("FVER", Bin.be32(0x01050000)), prop, diin,
            chunk("DSD ", Bin.zeros(dsdBytes)), if (id3 != null) chunk("ID3 ", id3) else ByteArray(0),
        )
        return Bin.cat(Bin.ascii("FRM8"), Bin.be64(body.size.toLong()), body)
    }
}

object AdtsFixture {
    fun frame(length: Int, sfIndex: Int = 4): ByteArray {
        val f = ByteArray(length)
        f[0] = 0xFF.toByte(); f[1] = 0xF1.toByte()
        f[2] = (0x40 or (sfIndex shl 2)).toByte()
        f[3] = (0x80 or ((length shr 11) and 3)).toByte()
        f[4] = ((length shr 3) and 0xFF).toByte()
        f[5] = (((length and 7) shl 5) or 0x1F).toByte()
        f[6] = 0xFC.toByte()
        return f
    }

    fun file(frames: Int, frameLength: Int = 100, id3: ByteArray? = null): ByteArray {
        val o = ByteArrayOutputStream()
        if (id3 != null) o.write(id3)
        for (i in 0 until frames) o.write(frame(frameLength))
        return o.toByteArray()
    }
}
