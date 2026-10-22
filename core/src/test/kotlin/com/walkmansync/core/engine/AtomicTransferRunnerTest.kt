package com.walkmansync.core.engine

import com.walkmansync.core.index.IndexLoadResult
import com.walkmansync.core.model.CopyReason
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.model.TransferOutcome
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.transfer.TransferConfig
import com.walkmansync.core.transfer.TransferControl
import com.walkmansync.core.transfer.TransferEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class AtomicTransferRunnerTest {

    @Test fun successfulTransferWritesFilesAndIndex() {
        val rig = Rig()
        val items = listOf(
            rig.item("Download/a.flac", 20_000, "MUSIC/Artist/Album/01 - A.flac"),
            rig.item("Download/b.mp3", 9_000, "MUSIC/Artist/Album/02 - B.mp3"),
            rig.item("Download/c.flac", 4_096, "MUSIC/Other/C.flac"),
        )
        val run = rig.runAll(items)
        val s = run.summary
        assertEquals(TransferOutcome.COMPLETED, s.outcome)
        assertEquals(3, s.copiedItems)
        assertEquals(33_096L, s.copiedBytes)
        assertTrue(s.indexSaved)
        assertTrue(rig.partFiles().isEmpty())
        for (i in items) assertArrayEquals(rig.srcVol.read(i.id), rig.dstVol.read(i.targetPath))
        val last = run.progress.last()
        assertEquals(3, last.completedItems)
        assertEquals(0, last.failedItems)
        val loaded = runBlocking { rig.loadIndex() } as IndexLoadResult.Loaded
        assertEquals(3, loaded.index.entries.size)
        assertEquals("Download/a.flac", loaded.index.entries[0].sourcePath)
        assertEquals(DST_ID, loaded.index.targetVolume)
        assertEquals(SRC_ID, loaded.index.lastSourceVolume)
    }

    @Test fun worksWithMusicAsTargetRoot() {
        val rig = Rig(targetRoot = "MUSIC")
        val run = rig.runAll(listOf(rig.item("Download/a.flac", 5_000, "MUSIC/A/a.flac")))
        assertEquals(TransferOutcome.COMPLETED, run.summary.outcome)
        assertTrue(rig.dstVol.exists("MUSIC/.walkman_sync_index.json"))
        assertTrue(rig.dstVol.exists("MUSIC/A/a.flac"))
    }

    @Test fun targetEjectedMidCopyAbortsAndLeavesNoFinalFile() {
        val rig = Rig()
        val items = listOf(rig.item("Download/a.flac", 40_000), rig.item("Download/b.flac", 10_000))
        rig.dstVol.onWrite = { _, total -> if (total >= 8_192) rig.dstVol.mounted = false }
        val run = rig.runAll(items)
        assertEquals(TransferOutcome.ABORTED, run.summary.outcome)
        assertTrue(run.summary.fatalError is SyncError.VolumeMissing)
        assertEquals(0, run.summary.copiedItems)
        assertFalse(rig.dstVol.exists("MUSIC/a.flac"))
        assertFalse(rig.dstVol.exists("MUSIC/b.flac"))
        assertFalse(run.summary.indexSaved && run.summary.copiedItems > 0)
        // Kart döndü: artık yalnızca .wsync.part olabilir ve sonraki aktarım/tarama bunu temizler.
        rig.dstVol.mounted = true
        rig.dstVol.onWrite = null
        val again = rig.runAll(items)
        assertEquals(TransferOutcome.COMPLETED, again.summary.outcome)
        assertTrue(rig.partFiles().isEmpty())
        assertArrayEquals(rig.srcVol.read("Download/a.flac"), rig.dstVol.read("MUSIC/a.flac"))
    }

    @Test fun sourceEjectedMidCopyAborts() {
        val rig = Rig()
        val items = listOf(rig.item("Download/a.flac", 40_000))
        rig.dstVol.onWrite = { _, total -> if (total >= 8_192) rig.srcVol.mounted = false }
        val run = rig.runAll(items)
        // Bellek içi kaynak akışı baştan okunmuş olduğundan kopya bitebilir; ölümcül olursa rol SOURCE olmalı.
        val err = run.summary.fatalError
        if (err != null) assertTrue(err is SyncError.VolumeMissing && err.role == com.walkmansync.core.model.VolumeRole.SOURCE)
        assertTrue(rig.partFiles().isEmpty())
    }

    @Test fun neverOverwritesExistingTargetFile() {
        val rig = Rig()
        val original = ByteArray(1234) { 7 }
        rig.dstVol.putFile("MUSIC/Artist/x.flac", original)
        val clash = rig.item("Download/x.flac", 9_999, "music/artist/X.FLAC")
        val ok = rig.item("Download/y.flac", 5_000, "MUSIC/y.flac")
        val run = rig.runAll(listOf(clash, ok))
        assertEquals(TransferOutcome.COMPLETED_WITH_ERRORS, run.summary.outcome)
        assertEquals(1, run.summary.copiedItems)
        val failure = run.summary.failures.single()
        assertTrue(failure.error is SyncError.TargetExists)
        assertArrayEquals(original, rig.dstVol.read("MUSIC/Artist/x.flac"))
        assertTrue(rig.partFiles().isEmpty())
        assertTrue(rig.dstVol.exists("MUSIC/y.flac"))
    }

    @Test fun renameConflictAfterWriteDoesNotOverwrite() {
        val rig = Rig()
        val item = rig.item("Download/x.flac", 6_000, "MUSIC/x.flac")
        val original = ByteArray(10) { 1 }
        // Kopya sırasında başka bir araç aynı adı oluşturur.
        rig.dstVol.onWrite = { _, total ->
            if (total >= 4096 && !rig.dstVol.exists("MUSIC/x.flac")) rig.dstVol.putFile("MUSIC/x.flac", original)
        }
        val run = rig.runAll(listOf(item))
        assertTrue(run.summary.failures.single().error is SyncError.TargetExists)
        assertArrayEquals(original, rig.dstVol.read("MUSIC/x.flac"))
        assertTrue(rig.partFiles().isEmpty())
    }

    @Test fun userCancelStopsCleanlyAndKeepsCompletedWork() {
        val rig = Rig()
        val control = TransferControl()
        val items = listOf(rig.item("Download/a.flac", 8_192), rig.item("Download/b.flac", 40_000), rig.item("Download/c.flac", 1_000))
        val writes = AtomicInteger()
        rig.dstVol.onWrite = { path, total ->
            if (path.contains("b.flac") && total >= 8_192 && writes.incrementAndGet() == 1) control.cancel()
        }
        val run = rig.runAll(items, control)
        assertEquals(TransferOutcome.CANCELLED, run.summary.outcome)
        assertEquals(1, run.summary.copiedItems)
        assertEquals(2, run.summary.notAttempted)
        assertTrue(rig.partFiles().isEmpty())
        assertFalse(rig.dstVol.exists("MUSIC/b.flac"))
        assertTrue(rig.dstVol.exists("MUSIC/a.flac"))
        assertTrue(run.summary.indexSaved)
        val loaded = runBlocking { rig.loadIndex() } as IndexLoadResult.Loaded
        assertEquals(1, loaded.index.entries.size)
        assertTrue(run.events.last() is TransferEvent.Finished)
    }

    @Test fun coroutineCancellationPropagatesAndStillCleansUp() = runBlocking {
        val rig = Rig()
        val items = listOf(rig.item("Download/a.flac", 8_192), rig.item("Download/b.flac", 60_000))
        val collector = java.util.concurrent.atomic.AtomicReference<Job?>(null)
        val finished = AtomicInteger()
        rig.dstVol.onWrite = { path, total ->
            if (path.contains("b.flac") && total >= 8_192) {
                while (collector.get() == null) Thread.sleep(1)
                collector.get()!!.cancel()
                Thread.sleep(50) // iptalin yayılması için kısa süre; döngü ensureActive ile durur
            }
        }
        val job = launch(Dispatchers.Default) {
            rig.runner.run(rig.request(items), TransferControl()).collect {
                if (it is TransferEvent.Finished) finished.incrementAndGet()
            }
        }
        collector.set(job)
        withTimeout(20_000) { job.join() }
        assertTrue(job.isCancelled)
        assertEquals(0, finished.get()) // iptalde Finished yayılmaz
        assertTrue(rig.partFiles().isEmpty())
        assertFalse(rig.dstVol.exists("MUSIC/b.flac"))
        // Biten dosyanın indeks kaydı iptale rağmen yazılmış olmalı.
        val loaded = rig.loadIndex() as IndexLoadResult.Loaded
        assertEquals(1, loaded.index.entries.size)
    }

    @Test fun pauseClosesAndDeletesPartThenResumeRewritesFromStart() {
        val rig = Rig()
        val control = TransferControl()
        val items = listOf(rig.item("Download/a.flac", 30_000), rig.item("Download/b.flac", 3_000))
        val paused = AtomicInteger()
        rig.dstVol.onWrite = { path, total ->
            if (path.contains("a.flac") && total >= 8_192 && paused.compareAndSet(0, 1)) control.pause()
        }
        var partsWhilePaused = -1
        val run = runBlocking {
            withTimeout(20_000) {
                val events = ArrayList<TransferEvent>()
                rig.runner.run(rig.request(items), control).collect { ev ->
                    events.add(ev)
                    if (ev is TransferEvent.Progress && ev.progress.isPaused && partsWhilePaused < 0) {
                        partsWhilePaused = rig.partFiles().size
                        assertEquals(0L, ev.progress.currentItemBytes)
                        assertEquals(0L, ev.progress.bytesPerSecond)
                        assertEquals(null, ev.progress.etaMillis)
                        control.resume()
                    }
                }
                Rig.Run(events)
            }
        }
        assertEquals(0, partsWhilePaused)
        assertEquals(TransferOutcome.COMPLETED, run.summary.outcome)
        assertArrayEquals(rig.srcVol.read("Download/a.flac"), rig.dstVol.read("MUSIC/a.flac"))
        assertTrue(run.progress.any { it.isPaused })
        assertFalse(run.progress.last().isPaused)
    }

    @Test fun cancelWhilePausedEndsAsCancelled() {
        val rig = Rig()
        val control = TransferControl()
        val items = listOf(rig.item("Download/a.flac", 30_000))
        val once = AtomicInteger()
        rig.dstVol.onWrite = { _, total -> if (total >= 8_192 && once.compareAndSet(0, 1)) control.pause() }
        val run = runBlocking {
            withTimeout(20_000) {
                val events = ArrayList<TransferEvent>()
                rig.runner.run(rig.request(items), control).collect { ev ->
                    events.add(ev)
                    if (ev is TransferEvent.Progress && ev.progress.isPaused) control.cancel()
                }
                Rig.Run(events)
            }
        }
        assertEquals(TransferOutcome.CANCELLED, run.summary.outcome)
        assertTrue(rig.partFiles().isEmpty())
        assertTrue(rig.dstVol.allFiles().none { it.startsWith("MUSIC/") })
    }

    @Test fun fat32SkipsFilesOverFourGiBMinusOne() {
        val rig = Rig(fs = TargetFileSystem.FAT32)
        val big = rig.entry("Download/dsd.dsf", 0xFFFF_FFFFL + 1)
        val bigItem = com.walkmansync.core.model.SyncItem("Download/dsd.dsf", big, "MUSIC/dsd.dsf", CopyReason.NEW,
            com.walkmansync.core.model.AudioFormat.DSF)
        val ok = rig.item("Download/ok.flac", 5_000)
        val run = rig.runAll(listOf(bigItem, ok))
        assertEquals(TransferOutcome.COMPLETED_WITH_ERRORS, run.summary.outcome)
        val f = run.summary.failures.single()
        assertEquals("Download/dsd.dsf", f.itemId)
        assertTrue(f.error is SyncError.FileTooLargeForFat32)
        assertEquals(1, run.summary.copiedItems)
        assertTrue(rig.dstVol.allFiles().none { it.contains("dsd") })
    }

    @Test fun unknownFsRejectsHugeFilesUnlessForced() {
        val rig = Rig(fs = TargetFileSystem.UNKNOWN)
        val big = rig.entry("Download/dsd.dsf", 0xFFFF_FFFFL + 10)
        fun item(reason: CopyReason) = com.walkmansync.core.model.SyncItem(
            "Download/dsd.dsf", big, "MUSIC/dsd.dsf", reason, com.walkmansync.core.model.AudioFormat.DSF)
        val normal = rig.runAll(listOf(item(CopyReason.NEW)))
        assertTrue(normal.summary.failures.single().error is SyncError.FileTooLargeForFat32)
        // Zorlanınca denenir (kaynak bellekte olmadığından SourceGone: yani 4 GiB kapısı geçildi).
        val forced = rig.runAll(listOf(item(CopyReason.FORCED)))
        assertTrue(forced.summary.failures.single().error is SyncError.SourceGone)
        assertTrue(rig.partFiles().isEmpty())
    }

    @Test fun efbigDuringForcedWriteBecomesFat32ItemFailureAndCleansPart() {
        val rig = Rig(fs = TargetFileSystem.UNKNOWN)
        val forced = rig.item("Download/big.dsf", 20_000, "MUSIC/big.dsf", reason = CopyReason.FORCED)
        val ok = rig.item("Download/ok.flac", 5_000)
        rig.dstVol.onWrite = { path, total ->
            if (path.contains("big.dsf") && total >= 8_192) throw IOException("write failed: EFBIG (File too large)")
        }
        val run = rig.runAll(listOf(forced, ok))
        assertEquals(TransferOutcome.COMPLETED_WITH_ERRORS, run.summary.outcome)
        assertTrue(run.summary.failures.single().error is SyncError.FileTooLargeForFat32)
        assertEquals(1, run.summary.copiedItems)
        assertTrue(rig.partFiles().isEmpty())
        assertFalse(rig.dstVol.exists("MUSIC/big.dsf"))
    }

    @Test fun exFatHasNoSizeLimitCheck() {
        val rig = Rig(fs = TargetFileSystem.EXFAT)
        val big = rig.entry("Download/dsd.dsf", 0xFFFF_FFFFL + 10)
        val it = com.walkmansync.core.model.SyncItem("Download/dsd.dsf", big, "MUSIC/dsd.dsf", CopyReason.NEW,
            com.walkmansync.core.model.AudioFormat.DSF)
        val run = rig.runAll(listOf(it))
        assertTrue(run.summary.failures.single().error is SyncError.SourceGone)
    }

    @Test fun insufficientSpaceUpFrontCopiesNothing() {
        val rig = Rig()
        rig.dstVol.freeBytes = 10_000
        val items = listOf(rig.item("Download/a.flac", 8_000), rig.item("Download/b.flac", 8_000))
        val run = rig.runAll(items)
        assertEquals(TransferOutcome.ABORTED, run.summary.outcome)
        val err = run.summary.fatalError as SyncError.InsufficientSpace
        assertEquals(16_000L, err.requiredBytes)
        assertEquals(10_000L, err.availableBytes)
        assertEquals(0, run.summary.copiedItems)
        assertTrue(rig.dstVol.allFiles().isEmpty())
    }

    @Test fun perFileMarginIsEnforced() {
        val rig = Rig()
        rig.dstVol.freeBytes = 100_000
        val items = listOf(rig.item("Download/a.flac", 40_000), rig.item("Download/b.flac", 40_000))
        // Toplam 80 KB < 100 KB, fakat her dosya için +8 MiB payı yok.
        val run = rig.runAll(items, config = TransferConfig(bufferSize = 4096))
        assertEquals(TransferOutcome.ABORTED, run.summary.outcome)
        assertTrue(run.summary.fatalError is SyncError.InsufficientSpace)
        assertEquals(0, run.summary.copiedItems)
        // Pay küçültülünce aynı veriler geçer.
        val ok = rig.runAll(items, config = TransferConfig(bufferSize = 4096, freeSpaceMarginBytes = 1_000))
        assertEquals(TransferOutcome.COMPLETED, ok.summary.outcome)
    }

    @Test fun enospcWhileWritingIsFatalAndRemovesPart() {
        val rig = Rig()
        rig.dstVol.onWrite = { _, total -> if (total >= 8_192) throw IOException("write failed: ENOSPC (No space left on device)") }
        val run = rig.runAll(listOf(rig.item("Download/a.flac", 40_000), rig.item("Download/b.flac", 1_000)))
        assertEquals(TransferOutcome.ABORTED, run.summary.outcome)
        assertTrue(run.summary.fatalError is SyncError.InsufficientSpace)
        assertTrue(rig.partFiles().isEmpty())
        assertEquals(2, run.summary.notAttempted)
    }

    @Test fun fileSpecificIoErrorContinuesWithNextFile() {
        val rig = Rig()
        rig.dstVol.onWrite = { path, _ -> if (path.contains("bad.flac")) throw IOException("EIO glitch") }
        val items = listOf(rig.item("Download/bad.flac", 10_000), rig.item("Download/good.flac", 10_000))
        val run = rig.runAll(items)
        assertEquals(TransferOutcome.COMPLETED_WITH_ERRORS, run.summary.outcome)
        val e = run.summary.failures.single().error
        assertTrue(e is SyncError.IoFailure && !e.isFatal)
        assertTrue(rig.dstVol.exists("MUSIC/good.flac"))
        assertFalse(rig.dstVol.exists("MUSIC/bad.flac"))
        assertTrue(rig.partFiles().isEmpty())
    }

    @Test fun sourceSizeChangedGivesSizeMismatchAndNoFinalFile() {
        val rig = Rig()
        val file = rig.entry("Download/a.flac", 10_000)
        rig.srcVol.putFile("Download/a.flac", 9_000) // tarama sonrası küçülmüş
        val item = com.walkmansync.core.model.SyncItem("Download/a.flac", file, "MUSIC/a.flac", CopyReason.NEW,
            com.walkmansync.core.model.AudioFormat.FLAC)
        val run = rig.runAll(listOf(item))
        assertTrue(run.summary.failures.single().error is SyncError.SizeMismatch)
        assertFalse(rig.dstVol.exists("MUSIC/a.flac"))
        assertTrue(rig.partFiles().isEmpty())
    }

    @Test fun sourceGoneIsPerFile() {
        val rig = Rig()
        val ghost = rig.entry("Download/ghost.flac", 1_000)
        val ghostItem = com.walkmansync.core.model.SyncItem("Download/ghost.flac", ghost, "MUSIC/ghost.flac", CopyReason.NEW,
            com.walkmansync.core.model.AudioFormat.FLAC)
        val run = rig.runAll(listOf(ghostItem, rig.item("Download/ok.flac", 1_000)))
        assertTrue(run.summary.failures.single().error is SyncError.SourceGone)
        assertEquals(1, run.summary.copiedItems)
    }

    @Test fun companionIsSkippedWhenItsAudioFailed() {
        val rig = Rig()
        rig.dstVol.putFile("MUSIC/Album/01.flac", 10)
        val audio = rig.item("Download/01.flac", 5_000, "MUSIC/Album/01.flac")
        val lrc = rig.item("Download/01.lrc", 200, "MUSIC/Album/01.lrc", CopyReason.COMPANION, companionOf = audio.id)
        val run = rig.runAll(listOf(audio, lrc))
        assertEquals(1, run.summary.failures.size)
        assertEquals(1, run.summary.notAttempted)
        assertFalse(rig.dstVol.exists("MUSIC/Album/01.lrc"))
    }

    @Test fun staleOwnPartsInTouchedDirectoriesAreRemoved() {
        val rig = Rig()
        rig.dstVol.putFile("MUSIC/A/x.flac.wsync.part", 100)
        rig.dstVol.putFile("MUSIC/A/other.mp3.wsync.part", 100)
        rig.dstVol.putFile("MUSIC/A/user.part", 100) // başkasının dosyası dokunulmaz
        val run = rig.runAll(listOf(rig.item("Download/x.flac", 5_000, "MUSIC/A/x.flac")))
        assertEquals(TransferOutcome.COMPLETED, run.summary.outcome)
        assertTrue(rig.partFiles().isEmpty())
        assertTrue(rig.dstVol.exists("MUSIC/A/user.part"))
    }

    @Test fun indexSaveFailureIsReportedButFilesStay() {
        val rig = Rig()
        val failing = object : com.walkmansync.core.index.IndexStore {
            override suspend fun load(tree: com.walkmansync.core.fs.TargetTree) = IndexLoadResult.Missing
            override suspend fun save(tree: com.walkmansync.core.fs.TargetTree, index: com.walkmansync.core.index.SyncIndex) {
                throw com.walkmansync.core.model.SyncException(SyncError.IndexCorrupt("disk"))
            }
        }
        val runner = AtomicTransferRunner(failing, rig.clock, AppDispatchers.Default, Logger.None)
        val items = listOf(rig.item("Download/a.flac", 5_000))
        val events = runBlocking { runner.run(rig.request(items), TransferControl()).let { f ->
            val l = ArrayList<TransferEvent>(); f.collect { l.add(it) }; l } }
        val s = (events.last() as TransferEvent.Finished).summary
        assertEquals(TransferOutcome.COMPLETED, s.outcome)
        assertFalse(s.indexSaved)
        assertTrue(rig.dstVol.exists("MUSIC/a.flac"))
    }

    @Test fun midRunIndexFlushHappensEveryNFiles() {
        val rig = Rig()
        val items = (1..5).map { rig.item("Download/t$it.flac", 4_096) }
        var flushedAfterTwo = -1
        val cfg = TransferConfig(bufferSize = 4096, indexFlushEvery = 2)
        // 3. dosya yazılırken diskteki indekste 2 kayıt görünmeli.
        rig.dstVol.onWrite = { path, _ ->
            if (path.contains("t3.flac") && flushedAfterTwo < 0) {
                val loaded = runBlocking { rig.loadIndex() } as? IndexLoadResult.Loaded
                flushedAfterTwo = loaded?.index?.entries?.size ?: 0
            }
        }
        val run = rig.runAll(items, config = cfg)
        assertEquals(2, flushedAfterTwo)
        assertEquals(5, (runBlocking { rig.loadIndex() } as IndexLoadResult.Loaded).index.entries.size)
        assertTrue(run.summary.indexSaved)
    }

    @Test fun progressIsMonotonicAndFinishedIsLastAndUnique() {
        val rig = Rig()
        val items = (1..4).map { rig.item("Download/t$it.flac", 20_000) }
        val run = rig.runAll(items)
        assertEquals(1, run.events.count { it is TransferEvent.Finished })
        assertTrue(run.events.last() is TransferEvent.Finished)
        var prev = 0L
        for (p in run.progress) {
            assertTrue(p.completedBytes >= prev)
            prev = p.completedBytes
        }
        assertEquals(80_000L, prev)
        assertNotNull(run.progress.first())
    }

    @Test fun progressIsThrottledToIntervalAndSpeedFollowsClock() {
        val clock = com.walkmansync.core.runtime.ManualClock()
        val rig = Rig(clock = clock)
        val item = rig.item("Download/a.flac", 100 * 4096)
        // Her 4 KiB'lık yazma 50 ms sürmüş gibi: ~82 KB/sn, toplam ~5 sn.
        rig.dstVol.onWrite = { _, _ -> clock.advanceMillis(50) }
        val run = rig.runAll(listOf(item))
        val during = run.progress.filter { it.currentItem != null && it.currentItemBytes > 0 }
        assertTrue("ilerleme sayısı=${during.size}", during.size in 15..30) // ≈ 5000 ms / 200 ms
        val speed = during.last().bytesPerSecond
        assertTrue("hız=$speed", speed in 70_000L..95_000L)
        assertTrue(during.last().etaMillis != null)
        assertEquals(TransferOutcome.COMPLETED, run.summary.outcome)
    }

    @Test fun speedMeterUsesSlidingWindow() {
        val clock = com.walkmansync.core.runtime.ManualClock()
        val m = SpeedMeter(clock, windowMs = 5_000)
        assertEquals(0, m.bytesPerSecond())
        m.record(0)
        clock.advanceMillis(1_000)
        m.record(1_000_000)
        assertEquals(1_000_000L, m.bytesPerSecond())
        // Hız düşer; eski hızlı örnekler pencere dışına çıkınca ortalamayı etkilemez.
        for (i in 1..10) { clock.advanceMillis(1_000); m.record(1_000_000L + i * 100_000L) }
        val v = m.bytesPerSecond()
        assertTrue("hız=$v", v in 95_000L..110_000L)
        m.reset()
        assertEquals(0, m.bytesPerSecond())
    }
}
