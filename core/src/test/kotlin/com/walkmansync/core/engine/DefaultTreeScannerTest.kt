package com.walkmansync.core.engine

import com.walkmansync.core.fs.SourceTree
import com.walkmansync.core.model.SourceHandle
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.fs.TreeNode
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class DefaultTreeScannerTest {
    private val scanner = DefaultTreeScanner(AppDispatchers.Default, Logger.None)

    @Test fun scansSupportedFilesSkippingHiddenSystemAndUnsupported() = runBlocking {
        val rig = Rig()
        rig.srcVol.putFile("Download/a.flac", 10)
        rig.srcVol.putFile("Download/._a.flac", 10)
        rig.srcVol.putFile("Download/.hidden.mp3", 10)
        rig.srcVol.putFile("Download/readme.txt", 10)
        rig.srcVol.putFile("Download/cover.JPG", 10)
        rig.srcVol.putFile("Download/front.jpg", 10)
        rig.srcVol.putFile("Music/Artist/Album/01.FLAC", 10)
        rig.srcVol.putFile("Music/Artist/Album/01.lrc", 10)
        rig.srcVol.putFile("Android/data/x/y.mp3", 10)
        rig.srcVol.putFile("DCIM/x.mp3", 10)
        rig.srcVol.putFile(".Trash-1000/files/t.mp3", 10)
        rig.srcVol.putFile("LOST.DIR/t.mp3", 10)
        rig.srcVol.putFile("System Volume Information/t.mp3", 10)
        rig.srcVol.putFile("root.mp3", 10)
        val r = scanner.scan(rig.source, null) { _, _, _ -> }
        assertEquals(
            listOf("Download/a.flac", "Download/cover.JPG", "Music/Artist/Album/01.FLAC", "Music/Artist/Album/01.lrc", "root.mp3"),
            r.files.map { it.relativePath },
        )
        assertTrue(r.unreadableDirectories.isEmpty())
        assertTrue("Music/Artist/Album" in r.directoryPaths)
        assertTrue(r.directoryPaths.none { it.startsWith("Android") || it.startsWith("DCIM") })
    }

    @Test fun normalizesNamesToNfcAndKeepsRealHandles() = runBlocking {
        val rig = Rig()
        val nfd = "Beyoncé/Lemonade/é.flac"
        rig.srcVol.putFile("Music/$nfd", 10)
        val r = scanner.scan(rig.source, null) { _, _, _ -> }
        val f = r.files.single()
        assertEquals("Music/Beyoncé/Lemonade/é.flac", f.relativePath)
        assertEquals("é.flac", f.name)
        assertTrue(f.handle.id.endsWith(nfd)) // tutamaç dokunulmaz
    }

    @Test fun targetScanStartsAtMusicAndCollectsPartFiles() = runBlocking {
        val rig = Rig()
        rig.dstVol.putFile("MUSIC/A/B/1.flac", 10)
        rig.dstVol.putFile("MUSIC/A/B/2.flac.wsync.part", 10)
        rig.dstVol.putFile("MUSIC/A/user.part", 10)
        rig.dstVol.putFile("Other/x.flac", 10)
        rig.dstVol.putFile(".walkman_sync_index.json", 10)
        val r = scanner.scan(rig.target, "MUSIC") { _, _, _ -> }
        assertEquals(listOf("MUSIC/A/B/1.flac"), r.files.map { it.relativePath })
        assertEquals(listOf("MUSIC/A/B/2.flac.wsync.part"), r.partFiles.map { it.relativePath })
    }

    @Test fun missingStartPathGivesEmptyResult() = runBlocking {
        val rig = Rig()
        val r = scanner.scan(rig.target, "MUSIC") { _, _, _ -> }
        assertTrue(r.files.isEmpty() && r.partFiles.isEmpty())
    }

    @Test fun listingConcurrencyIsBoundedByFour() = runBlocking {
        val rig = Rig()
        for (i in 0 until 24) rig.srcVol.putFile("Music/d$i/s.flac", 10)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val slow = object : SourceTree by rig.source {
            override suspend fun list(dir: SourceHandle): List<TreeNode<SourceHandle>> {
                val now = running.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                try {
                    delay(15)
                    return rig.source.list(dir)
                } finally {
                    running.decrementAndGet()
                }
            }
        }
        val r = scanner.scan(slow, null) { _, _, _ -> }
        assertEquals(24, r.files.size)
        assertTrue("peak=${peak.get()}", peak.get() <= DefaultTreeScanner.MAX_CONCURRENT_LISTINGS)
        assertTrue("peak=${peak.get()}", peak.get() > 1)
    }

    @Test fun fileSpecificDirectoryErrorIsRecordedAndScanContinues() = runBlocking {
        val rig = Rig()
        rig.srcVol.putFile("Music/good/a.flac", 10)
        rig.srcVol.putFile("Music/bad/b.flac", 10)
        val flaky = object : SourceTree by rig.source {
            override suspend fun list(dir: SourceHandle): List<TreeNode<SourceHandle>> {
                if (dir.id.endsWith("Music/bad")) throw SyncException(SyncError.IoFailure("Music/bad", "dizin bozuk", false))
                return rig.source.list(dir)
            }
        }
        val r = scanner.scan(flaky, null) { _, _, _ -> }
        assertEquals(listOf("Music/good/a.flac"), r.files.map { it.relativePath })
        assertEquals(listOf("Music/bad"), r.unreadableDirectories)
    }

    @Test fun fatalVolumeErrorPropagates() = runBlocking {
        val rig = Rig()
        rig.srcVol.putFile("Music/a/a.flac", 10)
        val gone = object : SourceTree by rig.source {
            override suspend fun list(dir: SourceHandle): List<TreeNode<SourceHandle>> {
                if (dir.id.endsWith("Music/a")) rig.srcVol.mounted = false
                return rig.source.list(dir)
            }
        }
        try {
            scanner.scan(gone, null) { _, _, _ -> }
            fail("VolumeMissing beklenirdi")
        } catch (e: SyncException) {
            assertTrue(e.error is SyncError.VolumeMissing)
        }
    }

    @Test fun rawIoErrorWithCardGoneBecomesVolumeMissing() = runBlocking {
        val rig = Rig()
        rig.srcVol.putFile("Music/a/a.flac", 10)
        val gone = object : SourceTree by rig.source {
            override suspend fun list(dir: SourceHandle): List<TreeNode<SourceHandle>> {
                if (dir.id.endsWith("Music/a")) { rig.srcVol.mounted = false; throw IOException("ENODEV") }
                return rig.source.list(dir)
            }
        }
        try {
            scanner.scan(gone, null) { _, _, _ -> }
            fail("VolumeMissing beklenirdi")
        } catch (e: SyncException) {
            assertTrue(e.error is SyncError.VolumeMissing)
        }
    }
}
