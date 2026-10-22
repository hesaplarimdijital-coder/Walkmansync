package com.walkmansync.core.domain

import com.walkmansync.core.fs.RandomAccessSource
import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.tags.TagFallback
import com.walkmansync.core.tags.TagReader
import com.walkmansync.core.testing.InMemorySourceTree
import com.walkmansync.core.testing.InMemoryVolume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TagServiceTest {
    private val vol = InMemoryVolume(VolumeId.require("1A2B-3C4D")).also {
        for (i in 0 until 40) it.putFile("Download/f$i.mp3", ByteArray(50))
        it.putFile("Download/readme.lrc", ByteArray(5))
    }
    private val tree = InMemorySourceTree(vol)

    private class StubReader(val block: (RandomAccessSource, AudioFormat) -> AudioTags?) : TagReader {
        override val formats: Set<AudioFormat> = AudioFormat.entries.filter { it.isAudio }.toSet()
        override fun read(source: RandomAccessSource, format: AudioFormat): AudioTags? = block(source, format)
    }

    private fun service(reader: TagReader, fallback: TagFallback = TagFallback.None) =
        DefaultTagService(reader, fallback, AppDispatchers.Default, Logger.None)

    private fun file(name: String) = runBlocking {
        tree.resolve("Download/$name")!!.toFileEntry()
    }

    @Test fun fallbackFillsMissingFieldsOnly() {
        val s = service(
            StubReader { _, _ -> AudioTags(artist = "Parser Artist", durationMs = 1000) },
            TagFallback { _, _ -> AudioTags(artist = "Fallback Artist", title = "Fallback Title", durationMs = 9999) },
        )
        val t = runBlocking { s.read(tree, file("f1.mp3")) }!!
        assertEquals("Parser Artist", t.artist)
        assertEquals("Fallback Title", t.title)
        assertEquals(1000L, t.durationMs)
    }

    @Test fun fallbackNotCalledWhenTitleAndDurationPresent() {
        val calls = AtomicInteger()
        val s = service(
            StubReader { _, _ -> AudioTags(title = "T", durationMs = 5) },
            TagFallback { _, _ -> calls.incrementAndGet(); null },
        )
        runBlocking { s.read(tree, file("f2.mp3")) }
        assertEquals(0, calls.get())
    }

    @Test fun fallbackUsedWhenParserReturnsNull() {
        val s = service(StubReader { _, _ -> null }, TagFallback { _, _ -> AudioTags(title = "Only Fallback") })
        assertEquals("Only Fallback", runBlocking { s.read(tree, file("f3.mp3")) }!!.title)
    }

    @Test fun nothingReadableGivesNull() {
        val s = service(StubReader { _, _ -> null })
        assertNull(runBlocking { s.read(tree, file("f4.mp3")) })
        assertNull(runBlocking { service(StubReader { _, _ -> AudioTags() }).read(tree, file("f4.mp3")) })
    }

    @Test fun nonAudioFilesAreNotOpened() {
        val opened = AtomicInteger()
        val s = service(StubReader { _, _ -> opened.incrementAndGet(); AudioTags(title = "x") })
        assertNull(runBlocking { s.read(tree, file("readme.lrc")) })
        assertEquals(0, opened.get())
    }

    @Test fun parserAndFallbackFailuresBecomeNull() {
        val s = service(StubReader { _, _ -> throw IOException("kart") }, TagFallback { _, _ -> throw IllegalStateException("x") })
        assertNull(runBlocking { s.read(tree, file("f5.mp3")) })
        val s2 = service(StubReader { _, _ -> throw IllegalArgumentException("bozuk") })
        assertNull(runBlocking { s2.read(tree, file("f5.mp3")) })
    }

    @Test fun missingSourceFileYieldsNullButVolumeLossPropagates() {
        val s = service(StubReader { _, _ -> AudioTags(title = "x", durationMs = 1) })
        val ghost = file("f6.mp3").copy(handle = com.walkmansync.core.model.SourceHandle("1A2B-3C4D:Download/ghost.mp3"))
        assertNull(runBlocking { s.read(tree, ghost) }) // SourceGone → null
        vol.mounted = false
        try {
            runBlocking { s.read(tree, file("f6.mp3")) }
            fail("VolumeMissing bekleniyordu")
        } catch (e: SyncException) {
            assertTrue(e.error is SyncError.VolumeMissing)
        } finally {
            vol.mounted = true
        }
    }

    @Test fun concurrencyIsBounded() {
        val running = AtomicInteger()
        val maxSeen = AtomicInteger()
        val s = service(
            StubReader { _, _ ->
                val now = running.incrementAndGet()
                maxSeen.updateAndGet { maxOf(it, now) }
                Thread.sleep(15)
                running.decrementAndGet()
                AudioTags(title = "t", durationMs = 1)
            },
        )
        val files = (0 until 40).map { file("f$it.mp3") }
        runBlocking {
            files.map { f -> async(AppDispatchers.Default.cpu) { s.read(tree, f) } }.awaitAll()
        }
        assertTrue("en çok 4 eşzamanlı, görülen ${maxSeen.get()}", maxSeen.get() in 1..4)
    }

    @Test fun cancellationPropagates() {
        val s = service(StubReader { _, _ -> AudioTags(title = "t", durationMs = 1) })
        try {
            runBlocking {
                val job = async { throw CancellationException("iptal") }
                job.await()
                s.read(tree, file("f7.mp3"))
            }
            fail()
        } catch (e: CancellationException) {
            // beklenen
        }
    }

    @Test fun domainModuleWiresEverything() {
        val module = DomainModules.create(TagFallback.None, AppDispatchers.Default, Clock.System, Logger.None)
        assertEquals("AC_DC", module.sanitizer.sanitizeDirComponent("AC/DC"))
        assertTrue(module.tagReader.formats.contains(AudioFormat.FLAC))
        val idx = com.walkmansync.core.index.SyncIndex.empty(VolumeId.require("ABCD-1234"), 7)
        assertEquals(idx, module.indexCodec.decode(module.indexCodec.encode(idx)))
        assertEquals("MUSIC/A/B", module.pathPlanner.planAudio(
            com.walkmansync.core.model.SourceFile(com.walkmansync.core.model.SourceHandle("1A2B-3C4D:Download/x.mp3"), "Download/x.mp3", "x.mp3", 1, 0),
            "", AudioTags(artist = "A", album = "B", title = "T"),
        ).substringBeforeLast('/'))
    }
}
