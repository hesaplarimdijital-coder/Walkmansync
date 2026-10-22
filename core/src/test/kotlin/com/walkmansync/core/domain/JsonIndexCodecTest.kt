package com.walkmansync.core.domain

import com.walkmansync.core.index.ContentKey
import com.walkmansync.core.index.IndexEntry
import com.walkmansync.core.index.IndexFormatException
import com.walkmansync.core.index.SyncIndex
import com.walkmansync.core.index.TargetKeyEntry
import com.walkmansync.core.index.UnsupportedIndexVersionException
import com.walkmansync.core.index.json.JsonIndexCodec
import com.walkmansync.core.model.VolumeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonIndexCodecTest {
    private val codec = JsonIndexCodec()
    private val target = VolumeId.require("ABCD-1234")
    private val source = VolumeId.require("1A2B-3C4D")

    private fun sample(): SyncIndex = SyncIndex(
        schemaVersion = 1, targetVolume = target, lastSourceVolume = source,
        createdAtMillis = 1_767_225_600_000, updatedAtMillis = 1_767_225_700_000,
        entries = listOf(
            IndexEntry("Download/a.flac", 31_457_280, 1_767_225_600_000, "MUSIC/Artist/Album/01 - A.flac", 31_457_280, ContentKey("v1|artist|album|1|1|a|120"), 1_767_225_650_000),
            IndexEntry("Download/\"q\" \\ /ü😀\n\t\u0001.mp3", Long.MAX_VALUE, 0, "MUSIC/X/Y/ü😀.mp3", 1, null, 5),
        ),
        targetKeys = listOf(
            TargetKeyEntry("MUSIC/Old/x.mp3", 4_000_000, ContentKey("v1|old|x|0|0|x|-")),
            TargetKeyEntry("MUSIC/Old/untagged.mp3", 10, null),
        ),
    )

    @Test fun roundTrip() {
        val idx = sample()
        val json = codec.encode(idx)
        assertEquals(idx, codec.decode(json))
        assertEquals(json, codec.encode(codec.decode(json))) // kararlı çıktı
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"lastSourceVolume\": \"1A2B-3C4D\""))
    }

    @Test fun roundTripEmptyAndNullSource() {
        val idx = SyncIndex.empty(target, 42)
        val back = codec.decode(codec.encode(idx))
        assertEquals(idx, back)
        assertNull(back.lastSourceVolume)
    }

    @Test fun keyOrderMatchesContractSchema() {
        val json = codec.encode(sample())
        val order = listOf("schemaVersion", "app", "targetVolume", "lastSourceVolume", "createdAt", "updatedAt", "entries", "targetKeys")
        var last = -1
        for (k in order) {
            val i = json.indexOf("\"$k\"")
            assertTrue("$k yok/sırasız", i > last)
            last = i
        }
    }

    @Test fun lonelySurrogatesAreEscapedAndSurvive() {
        val idx = sample().copy(entries = listOf(IndexEntry("a\uD800b", 1, 1, "MUSIC/x\uDC00.mp3", 1, null, 1)))
        val json = codec.encode(idx)
        assertTrue(json.contains("\\ud800"))
        assertEquals(idx, codec.decode(json))
    }

    @Test fun decodesEscapesAndSurrogatePairs() {
        val json = """
            {"schemaVersion":1,"app":"WalkmanSync","targetVolume":"ABCD-1234","lastSourceVolume":null,
             "createdAt":1,"updatedAt":2,"entries":[{"src":"a\/b\u00fc\ud83c\udfb5\"\\","srcSize":3,"srcMtime":4,"dst":"MUSIC/\u0041.flac","dstSize":3,"key":null,"at":5}],"targetKeys":[]}
        """.trimIndent()
        val e = codec.decode(json).entries.single()
        assertEquals("a/bü🎵\"\\", e.sourcePath)
        assertEquals("MUSIC/A.flac", e.targetPath)
    }

    @Test fun unknownFieldsAreIgnored() {
        val json = """
            {"future":{"a":[1,2.5e3,{"b":null}],"c":true},"schemaVersion":1,"app":"WalkmanSync","targetVolume":"abcd-1234",
             "createdAt":1,"updatedAt":2,"extra":"x",
             "entries":[{"src":"s","srcSize":1,"srcMtime":2,"dst":"MUSIC/d.mp3","dstSize":1,"at":3,"novel":[false,-0.5]}],
             "targetKeys":[{"dst":"MUSIC/t.mp3","dstSize":9,"zzz":1}]}
        """.trimIndent()
        val idx = codec.decode(json)
        assertEquals(target, idx.targetVolume)
        assertNull(idx.lastSourceVolume)
        assertNull(idx.entries.single().contentKey)
        assertEquals(1, idx.targetKeys.size)
    }

    @Test fun targetKeysMayBeAbsent() {
        val json = """{"schemaVersion":1,"targetVolume":"ABCD-1234","createdAt":1,"updatedAt":1,"entries":[]}"""
        assertTrue(codec.decode(json).targetKeys.isEmpty())
    }

    @Test fun newerSchemaIsRejectedWithoutParsingBody() {
        val json = """{"schemaVersion":2,"entries":"tamamen farklı yapı","targetVolume":123}"""
        try {
            codec.decode(json)
            fail()
        } catch (e: UnsupportedIndexVersionException) {
            assertEquals(2, e.schemaVersion)
        }
        try {
            codec.decode("""{"entries":[],"schemaVersion":99999999999}""")
            fail()
        } catch (e: UnsupportedIndexVersionException) {
            assertEquals(Int.MAX_VALUE, e.schemaVersion)
        }
    }

    private fun assertFormatError(json: String) {
        try {
            codec.decode(json)
            fail("IndexFormatException bekleniyordu: $json")
        } catch (e: IndexFormatException) {
            // beklenen
        }
    }

    @Test fun schemaVersionMustBeANumber() {
        assertFormatError("""{"schemaVersion":"1","targetVolume":"ABCD-1234","createdAt":1,"updatedAt":1,"entries":[]}""")
        assertFormatError("""{"schemaVersion":1.5,"targetVolume":"ABCD-1234","createdAt":1,"updatedAt":1,"entries":[]}""")
        assertFormatError("""{"schemaVersion":null,"targetVolume":"ABCD-1234","createdAt":1,"updatedAt":1,"entries":[]}""")
        assertFormatError("""{"targetVolume":"ABCD-1234","createdAt":1,"updatedAt":1,"entries":[]}""")
        assertFormatError("""{"schemaVersion":0,"targetVolume":"ABCD-1234","createdAt":1,"updatedAt":1,"entries":[]}""")
    }

    @Test fun missingRequiredFieldsFail() {
        val ok = """{"schemaVersion":1,"targetVolume":"ABCD-1234","createdAt":1,"updatedAt":1,"entries":[]}"""
        codec.decode(ok)
        assertFormatError(ok.replace("\"targetVolume\":\"ABCD-1234\",", ""))
        assertFormatError(ok.replace("\"createdAt\":1,", ""))
        assertFormatError(ok.replace("\"updatedAt\":1,", ""))
        assertFormatError(ok.replace(",\"entries\":[]", ""))
        assertFormatError(ok.replace("\"entries\":[]", "\"entries\":[{\"src\":\"a\",\"srcSize\":1,\"srcMtime\":1,\"dst\":\"d\",\"dstSize\":1}]")) // at yok
        assertFormatError(ok.replace("\"entries\":[]", "\"entries\":[{\"srcSize\":1,\"srcMtime\":1,\"dst\":\"d\",\"dstSize\":1,\"at\":1}]")) // src yok
        assertFormatError(ok.replace("\"ABCD-1234\"", "\"primary\"")) // geçersiz UUID
    }

    @Test fun malformedJsonFails() {
        val bad = listOf(
            "", "   ", "{", "}", "[]", "null", "{\"schemaVersion\":1,}", "{\"schemaVersion\":1 \"x\":2}",
            "{'schemaVersion':1}", "{\"schemaVersion\":1}garbage", "{\"schemaVersion\":01}", "{\"schemaVersion\":1,\"a\":\"unterminated}",
            "{\"schemaVersion\":1,\"a\":\"bad\\q\"}", "{\"schemaVersion\":1,\"a\":\"\\u12G4\"}", "{\"schemaVersion\":1,\"a\":\"tab\there\"}",
            "{\"schemaVersion\":1,\"a\":[1,2,]}", "{\"schemaVersion\":1,\"a\":tru}", "{\"schemaVersion\":1,\"a\":-}", "{\"schemaVersion\":1,\"a\":1.}",
            "{\"schemaVersion\":1,\"a\":{\"b\":}}", "{\"schemaVersion\":1,\"a\":[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[1]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]}",
        )
        for (b in bad) assertFormatError(b)
    }

    @Test fun wrongTypesFail() {
        assertFormatError("""{"schemaVersion":1,"targetVolume":5,"createdAt":1,"updatedAt":1,"entries":[]}""")
        assertFormatError("""{"schemaVersion":1,"targetVolume":"ABCD-1234","createdAt":"1","updatedAt":1,"entries":[]}""")
        assertFormatError("""{"schemaVersion":1,"targetVolume":"ABCD-1234","createdAt":1,"updatedAt":1,"entries":{}}""")
        assertFormatError("""{"schemaVersion":1,"targetVolume":"ABCD-1234","createdAt":1,"updatedAt":1,"entries":[1]}""")
        assertFormatError("""{"schemaVersion":1,"targetVolume":"ABCD-1234","createdAt":1.5,"updatedAt":1,"entries":[]}""")
    }

    @Test fun bomAndWhitespaceAreAccepted() {
        val json = "\uFEFF \n\t{\"schemaVersion\" : 1 , \"targetVolume\":\"ABCD-1234\",\"createdAt\":1,\"updatedAt\":1,\"entries\":[ ] }\n"
        assertEquals(0, codec.decode(json).entries.size)
    }

    @Test fun largeIndexRoundTrips() {
        val entries = (0 until 20_000).map {
            IndexEntry("Download/f$it.flac", it.toLong(), it.toLong(), "MUSIC/A/B/$it.flac", it.toLong(), ContentKey("v1|a|b|0|$it|t|1"), it.toLong())
        }
        val idx = SyncIndex.empty(target, 1).copy(entries = entries)
        assertEquals(idx, codec.decode(codec.encode(idx)))
    }
}
