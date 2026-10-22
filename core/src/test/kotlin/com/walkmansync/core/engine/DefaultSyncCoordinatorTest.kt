package com.walkmansync.core.engine

import com.walkmansync.core.delta.DeltaEngine
import com.walkmansync.core.delta.DeltaRequest
import com.walkmansync.core.delta.DeltaResult
import com.walkmansync.core.fs.GuardedTargetTree
import com.walkmansync.core.fs.SourceTree
import com.walkmansync.core.index.IndexLoadResult
import com.walkmansync.core.model.CopyReason
import com.walkmansync.core.model.PlanWarning
import com.walkmansync.core.model.RemovableVolume
import com.walkmansync.core.model.ScanProgress
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncItem
import com.walkmansync.core.model.SyncPlan
import com.walkmansync.core.model.SyncState
import com.walkmansync.core.model.TransferOutcome
import com.walkmansync.core.model.VolumeBinding
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumeKind
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.model.VolumeStatus
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.workflow.TreeOpener
import com.walkmansync.core.workflow.VolumeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DefaultSyncCoordinatorTest {

    /** Kaynaktaki her desteklenen dosyayı MUSIC/<ad> altına kopyalayan basit fark motoru. */
    private class FlatDelta(var targetKeysChanged: Boolean = false) : DeltaEngine {
        override suspend fun computePlan(request: DeltaRequest, onProgress: (ScanProgress) -> Unit): DeltaResult {
            val items = request.sourceScan.files.filter { it.format != null }.map {
                SyncItem(it.relativePath, it, "MUSIC/${it.name}", CopyReason.NEW, it.format!!)
            }
            val plan = SyncPlan(request.pair, request.nowMillis, items, emptyList(), request.index,
                request.targetFileSystem, request.targetFreeBytes, request.warnings)
            return DeltaResult(plan, targetKeysChanged)
        }
    }

    private val rig = Rig()
    private val delta = FlatDelta()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val srcBinding = VolumeBinding(VolumeRole.SOURCE, SRC_ID, "content://src", "1A2B-3C4D:", "Tab", 0)
    private val dstBinding = VolumeBinding(VolumeRole.TARGET, DST_ID, "content://dst", "ABCD-1234:", "Walkman", 0)
    private fun rv(id: VolumeId) = RemovableVolume(id, "SD", VolumeKind.SD_CARD, true)
    private fun ready() = VolumeSnapshot(
        VolumeStatus.Ready(srcBinding, rv(SRC_ID)), VolumeStatus.Ready(dstBinding, rv(DST_ID)))
    private fun targetGone() = VolumeSnapshot(
        VolumeStatus.Ready(srcBinding, rv(SRC_ID)), VolumeStatus.NotMounted(dstBinding))

    private val volumes = MutableStateFlow(ready())
    @Volatile private var sourceGate: CompletableDeferred<Unit>? = null

    private val opener = object : TreeOpener {
        override suspend fun openSource(binding: VolumeBinding): SourceTree {
            sourceGate?.await()
            return rig.source
        }
        override suspend fun openTarget(binding: VolumeBinding): GuardedTargetTree = rig.target
    }

    private val workflow = DefaultScanWorkflow(
        rig.store, DefaultTreeScanner(AppDispatchers.Default, Logger.None), delta,
        Clock.System, AppDispatchers.Default, Logger.None)

    private fun coordinator(hardCancelMs: Long = 15_000) = DefaultSyncCoordinator(
        volumes, opener, workflow, rig.runner, Clock.System, Logger.None, scope, hardCancelMs)

    @After fun tearDown() { scope.cancel() }

    private fun DefaultSyncCoordinator.await(timeoutMs: Long = 15_000, pred: (SyncState) -> Boolean): SyncState =
        runBlocking { withTimeout(timeoutMs) { state.first(pred) } }

    private fun seedSource(n: Int, size: Int = 20_000) {
        for (i in 1..n) rig.srcVol.putFile("Download/t$i.flac", size)
    }

    @Test fun initialStateFollowsVolumes() {
        volumes.value = VolumeSnapshot.Unbound
        val c = coordinator()
        assertTrue(c.state.value is SyncState.AwaitingVolumes)
        volumes.value = ready()
        assertTrue(c.await { it is SyncState.Idle } is SyncState.Idle)
        assertFalse(c.startTransfer(setOf("x")))
        assertFalse(c.pause() || c.resume() || c.cancel() || c.dismiss())
    }

    @Test fun scanTransferCompleteAndDismiss() {
        seedSource(3)
        val c = coordinator()
        assertTrue(c.scan())
        val ready = c.await { it is SyncState.PlanReady } as SyncState.PlanReady
        assertEquals(3, ready.plan.items.size)
        assertFalse(c.startTransfer(emptySet()))
        assertFalse(c.startTransfer(setOf("nonexistent")))
        assertTrue(c.startTransfer(ready.plan.defaultSelection))
        val done = c.await { it is SyncState.Completed || it is SyncState.Failed }
        val summary = (done as SyncState.Completed).summary
        assertEquals(TransferOutcome.COMPLETED, summary.outcome)
        assertEquals(3, summary.copiedItems)
        assertTrue(rig.partFiles().isEmpty())
        assertEquals(3, (runBlocking { rig.store.load(rig.target) } as IndexLoadResult.Loaded).index.entries.size)
        assertTrue(c.dismiss())
        assertTrue(c.state.value is SyncState.Idle)
        // İkinci tarama: iş bitti, kilit serbest.
        assertTrue(c.scan())
    }

    @Test fun secondScanWhileBusyIsRejectedAndCancelReturnsToIdle() {
        seedSource(1)
        val gate = CompletableDeferred<Unit>()
        sourceGate = gate
        val c = coordinator()
        assertTrue(c.scan())
        assertTrue(c.state.value is SyncState.Scanning)
        assertFalse(c.scan())
        assertFalse(c.startTransfer(setOf("x")))
        assertTrue(c.cancel())
        assertTrue(c.state.value is SyncState.Idle)
        sourceGate = null
        // Zombi iş çözülünce kilit bırakılır; yeni tarama kabul edilir.
        val deadline = System.currentTimeMillis() + 10_000
        while (!c.scan()) {
            assertTrue("kilit bırakılmadı", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        assertTrue(c.await { it is SyncState.PlanReady } is SyncState.PlanReady)
    }

    @Test fun pauseAndResumeThroughCoordinator() {
        seedSource(2, 30_000)
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        rig.dstVol.onWrite = { path, total ->
            if (path.contains("t1.flac") && total >= 8_192 && started.count > 0) {
                started.countDown()
                proceed.await(10, TimeUnit.SECONDS)
            }
        }
        val c = coordinator()
        c.scan()
        val plan = (c.await { it is SyncState.PlanReady } as SyncState.PlanReady).plan
        assertTrue(c.startTransfer(plan.defaultSelection))
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertTrue(c.pause())
        assertTrue(c.state.value is SyncState.Paused)
        assertFalse(c.pause())
        proceed.countDown()
        // Runner açık dosyayı kapatıp .part'ı siler ve bekler.
        val deadline = System.currentTimeMillis() + 10_000
        while (rig.partFiles().isNotEmpty()) {
            assertTrue("part silinmedi", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
        assertTrue(c.state.value is SyncState.Paused)
        assertTrue(c.resume())
        assertFalse(c.resume())
        val done = c.await { it is SyncState.Completed } as SyncState.Completed
        assertEquals(2, done.summary.copiedItems)
        assertEquals(30_000, rig.dstVol.read("MUSIC/t1.flac")!!.size)
    }

    @Test fun userCancelDuringTransferEndsCompletedCancelled() {
        seedSource(3, 30_000)
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        rig.dstVol.onWrite = { path, total ->
            if (path.contains("t2.flac") && total >= 8_192 && started.count > 0) {
                started.countDown()
                proceed.await(10, TimeUnit.SECONDS)
            }
        }
        val c = coordinator()
        c.scan()
        val plan = (c.await { it is SyncState.PlanReady } as SyncState.PlanReady).plan
        c.startTransfer(plan.defaultSelection)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertTrue(c.cancel())
        proceed.countDown()
        val done = c.await { it is SyncState.Completed || it is SyncState.Failed } as SyncState.Completed
        assertEquals(TransferOutcome.CANCELLED, done.summary.outcome)
        assertEquals(1, done.summary.copiedItems)
        assertTrue(rig.partFiles().isEmpty())
    }

    @Test fun cardRemovedDuringTransferFailsRecoverablyAndRescanCleansLeftovers() {
        seedSource(2, 30_000)
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        rig.dstVol.onWrite = { _, total ->
            if (total >= 8_192 && started.count > 0) {
                started.countDown()
                proceed.await(10, TimeUnit.SECONDS)
            }
        }
        val c = coordinator()
        c.scan()
        val plan = (c.await { it is SyncState.PlanReady } as SyncState.PlanReady).plan
        c.startTransfer(plan.defaultSelection)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        rig.dstVol.mounted = false
        volumes.value = targetGone()
        proceed.countDown()
        val failed = c.await { it is SyncState.Failed } as SyncState.Failed
        assertTrue(failed.error is SyncError.VolumeMissing)
        assertEquals(VolumeRole.TARGET, (failed.error as SyncError.VolumeMissing).role)
        assertTrue(failed.recoverable)
        assertEquals(0, failed.summary!!.copiedItems)
        assertFalse(rig.dstVol.exists("MUSIC/t1.flac"))
        // Kart geri takıldı: sonuç yerinde kalır, yeniden tarama artık .part'ı temizler.
        rig.dstVol.mounted = true
        rig.dstVol.onWrite = null
        volumes.value = ready()
        assertTrue(c.state.value is SyncState.Failed)
        assertTrue(c.scan())
        assertTrue(c.await { it is SyncState.PlanReady } is SyncState.PlanReady)
        assertTrue(rig.partFiles().isEmpty())
    }

    @Test fun targetTurningReadOnlyWhileScanningFailsWithVolumeReadOnly() {
        seedSource(1)
        sourceGate = CompletableDeferred()
        val c = coordinator()
        assertTrue(c.scan())
        volumes.value = VolumeSnapshot(
            VolumeStatus.Ready(srcBinding, rv(SRC_ID)),
            VolumeStatus.ReadOnly(dstBinding, RemovableVolume(DST_ID, "SD", VolumeKind.SD_CARD, false)),
        )
        val failed = c.await { it is SyncState.Failed } as SyncState.Failed
        assertTrue(failed.error is SyncError.VolumeReadOnly && failed.recoverable)
        assertEquals(null, volumes.value.pair)
        sourceGate = null
    }

    @Test fun cardRemovedWhileScanningFailsWithVolumeMissing() {
        seedSource(1)
        sourceGate = CompletableDeferred()
        val c = coordinator()
        assertTrue(c.scan())
        volumes.value = targetGone()
        val failed = c.await { it is SyncState.Failed } as SyncState.Failed
        assertTrue(failed.error is SyncError.VolumeMissing && failed.recoverable)
        sourceGate = null
    }

    @Test fun planIsDroppedWhenVolumesChange() {
        seedSource(1)
        val c = coordinator()
        c.scan()
        c.await { it is SyncState.PlanReady }
        volumes.value = targetGone()
        assertTrue(c.await { it is SyncState.AwaitingVolumes } is SyncState.AwaitingVolumes)
        assertFalse(c.startTransfer(setOf("Download/t1.flac")))
    }

    @Test fun completedResultSurvivesCardRemovalUntilDismissed() {
        seedSource(1)
        val c = coordinator()
        c.scan()
        val plan = (c.await { it is SyncState.PlanReady } as SyncState.PlanReady).plan
        c.startTransfer(plan.defaultSelection)
        c.await { it is SyncState.Completed }
        volumes.value = targetGone()
        Thread.sleep(100)
        assertTrue(c.state.value is SyncState.Completed)
        assertTrue(c.dismiss())
        assertTrue(c.state.value is SyncState.AwaitingVolumes)
    }

    @Test fun foreignIndexIsIgnoredWarnedAndNeverOverwrittenByScan() {
        seedSource(1)
        val foreign = rig.codec.encode(
            com.walkmansync.core.index.SyncIndex.empty(VolumeId.require("FFFF-0000"), 1)).toByteArray()
        rig.dstVol.putFile(".walkman_sync_index.json", foreign)
        delta.targetKeysChanged = true // yine de kaydedilmemeli
        val c = coordinator()
        c.scan()
        val plan = (c.await { it is SyncState.PlanReady } as SyncState.PlanReady).plan
        assertTrue(PlanWarning.FOREIGN_INDEX_IGNORED in plan.warnings)
        assertTrue(foreign.contentEquals(rig.dstVol.read(".walkman_sync_index.json")))
        assertEquals(DST_ID, plan.baseIndex.targetVolume)
    }

    @Test fun unsupportedIndexFailsUnrecoverable() {
        seedSource(1)
        rig.dstVol.putFile(".walkman_sync_index.json", "v=99\n".toByteArray())
        val c = coordinator()
        c.scan()
        val failed = c.await { it is SyncState.Failed } as SyncState.Failed
        assertTrue(failed.error is SyncError.IndexUnsupported)
        assertFalse(failed.recoverable)
    }

    @Test fun recoversIndexFromBackupDuringScanAndWarns() {
        seedSource(1)
        val idx = com.walkmansync.core.index.SyncIndex.empty(DST_ID, 1)
        rig.dstVol.putFile(".walkman_sync_index.json.bak", rig.codec.encode(idx).toByteArray())
        rig.dstVol.putFile(".walkman_sync_index.json", "bozuk".toByteArray())
        val c = coordinator()
        c.scan()
        val plan = (c.await { it is SyncState.PlanReady } as SyncState.PlanReady).plan
        assertTrue(PlanWarning.INDEX_RECOVERED_FROM_BACKUP in plan.warnings)
    }

    @Test fun staleStalePartsAreCleanedDuringScan() {
        seedSource(1)
        rig.dstVol.putFile("MUSIC/A/x.flac.wsync.part", 50)
        rig.dstVol.putFile(".walkman_sync_index.json.wsync.part", 50)
        val c = coordinator()
        c.scan()
        c.await { it is SyncState.PlanReady }
        assertTrue(rig.partFiles().isEmpty())
    }

    @Test fun hungTransferIsHardCancelledAfterTimeout() {
        seedSource(1, 30_000)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.dstVol.onWrite = { _, total ->
            if (total >= 8_192 && started.count > 0) {
                started.countDown()
                release.await(10, TimeUnit.SECONDS) // takılı G/Ç benzetimi
            }
        }
        val c = coordinator(hardCancelMs = 300)
        c.scan()
        val plan = (c.await { it is SyncState.PlanReady } as SyncState.PlanReady).plan
        c.startTransfer(plan.defaultSelection)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertTrue(c.cancel())
        val done = c.await(5_000) { it is SyncState.Completed } as SyncState.Completed
        assertEquals(TransferOutcome.CANCELLED, done.summary.outcome)
        release.countDown()
        runBlocking { delay(100) }
        assertTrue(c.state.value is SyncState.Completed)
    }
}
