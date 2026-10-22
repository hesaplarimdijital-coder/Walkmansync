package com.walkmansync.core.engine

import com.walkmansync.core.delta.DeltaEngine
import com.walkmansync.core.delta.DeltaRequest
import com.walkmansync.core.fs.SourceTree
import com.walkmansync.core.fs.TargetTree
import com.walkmansync.core.fs.TreeNode
import com.walkmansync.core.index.IndexLoadResult
import com.walkmansync.core.index.IndexStore
import com.walkmansync.core.index.SyncIndex
import com.walkmansync.core.model.PlanWarning
import com.walkmansync.core.model.ScanPhase
import com.walkmansync.core.model.ScanProgress
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.SyncPlan
import com.walkmansync.core.model.TargetHandle
import com.walkmansync.core.model.TargetLayout
import com.walkmansync.core.model.VolumePair
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.scan.TreeScanner
import com.walkmansync.core.workflow.ScanWorkflow
import java.io.IOException

/** Tarama aşamalarını sırayla yürütür; bkz. [ScanWorkflow]. */
class DefaultScanWorkflow(
    private val indexStore: IndexStore,
    private val treeScanner: TreeScanner,
    private val deltaEngine: DeltaEngine,
    private val clock: Clock,
    @Suppress("unused") private val dispatchers: AppDispatchers,
    private val logger: Logger,
) : ScanWorkflow {

    override suspend fun scan(
        pair: VolumePair,
        source: SourceTree,
        target: TargetTree,
        onProgress: (ScanProgress) -> Unit,
    ): SyncPlan {
        val warnings = LinkedHashSet<PlanWarning>()

        // 1. İndeks
        onProgress(ScanProgress(ScanPhase.LOADING_INDEX))
        var mayPersist = true
        val index: SyncIndex = when (val loaded = indexStore.load(target)) {
            is IndexLoadResult.Loaded -> {
                if (loaded.recoveredFromBackup) warnings.add(PlanWarning.INDEX_RECOVERED_FROM_BACKUP)
                loaded.index
            }
            IndexLoadResult.Missing -> SyncIndex.empty(pair.target, clock.nowMillis())
            is IndexLoadResult.Foreign -> {
                warnings.add(PlanWarning.FOREIGN_INDEX_IGNORED)
                mayPersist = false
                SyncIndex.empty(pair.target, clock.nowMillis())
            }
            is IndexLoadResult.Corrupt -> {
                logger.w(TAG, "İndeks bozuk, sıfırlandı: ${loaded.detail}")
                warnings.add(PlanWarning.INDEX_RESET_CORRUPT)
                mayPersist = false
                SyncIndex.empty(pair.target, clock.nowMillis())
            }
            is IndexLoadResult.Unsupported -> throw SyncException(SyncError.IndexUnsupported(loaded.schemaVersion))
        }

        // 2. Hedef taraması
        onProgress(ScanProgress(ScanPhase.LISTING_TARGET))
        val targetScan = treeScanner.scan(target, TargetLayout.MUSIC_DIR) { files, dirs, path ->
            onProgress(ScanProgress(ScanPhase.LISTING_TARGET, files, 0, dirs, path))
        }

        // 3. Artık .wsync.part temizliği
        onProgress(ScanProgress(ScanPhase.CLEANING_STALE_PARTS, 0, targetScan.partFiles.size))
        cleanStaleParts(target, targetScan.partFiles, onProgress)

        // 4. Kaynak taraması
        onProgress(ScanProgress(ScanPhase.LISTING_SOURCE))
        val sourceScan = treeScanner.scan(source, null) { files, dirs, path ->
            onProgress(ScanProgress(ScanPhase.LISTING_SOURCE, files, 0, dirs, path))
        }

        // 5-6. Fark motoru
        val fileSystem = target.fileSystem()
        val free = target.freeBytes()
        val result = deltaEngine.computePlan(
            DeltaRequest(
                pair = pair,
                source = source,
                target = target,
                sourceScan = sourceScan,
                targetScan = targetScan,
                index = index,
                targetFileSystem = fileSystem,
                targetFreeBytes = free,
                warnings = warnings.toSet(),
                nowMillis = clock.nowMillis(),
            ),
            onProgress,
        )

        // 7. Etiket önbelleği değiştiyse kaydet (yabancı/bozuk indeksin üzerine yazılmaz; hata yutulur)
        if (result.targetKeysChanged && mayPersist) {
            try {
                indexStore.save(target, result.plan.baseIndex)
            } catch (e: SyncException) {
                logger.w(TAG, "İndeks önbelleği kaydedilemedi", e)
            } catch (e: IOException) {
                logger.w(TAG, "İndeks önbelleği kaydedilemedi", e)
            }
        }
        return result.plan
    }

    /** Dosya bazlı silme hatası taramayı durdurmaz; ölümcül birim hatası ve iptal yayılır. */
    private suspend fun cleanStaleParts(
        target: TargetTree,
        parts: List<TreeNode<TargetHandle>>,
        onProgress: (ScanProgress) -> Unit,
    ) {
        val seen = HashSet<String>()
        var done = 0
        val all = ArrayList<TreeNode<TargetHandle>>(parts)
        target.resolve(TargetLayout.indexPartPathFor(target.root.relativePath))?.let { all.add(it) }
        for (node in all) {
            if (!seen.add(SyncIndex.foldPath(node.relativePath))) continue
            try {
                target.delete(node)
                logger.d(TAG, "Artık .part silindi: ${node.relativePath}")
            } catch (e: SyncException) {
                if (e.error.isFatal) throw e
                logger.w(TAG, "Artık .part silinemedi: ${node.relativePath}", e)
            } catch (e: IOException) {
                if (!target.isAvailable()) throw SyncException(SyncError.VolumeMissing(VolumeRole.TARGET, target.volume), e)
                logger.w(TAG, "Artık .part silinemedi: ${node.relativePath}", e)
            }
            done++
            onProgress(ScanProgress(ScanPhase.CLEANING_STALE_PARTS, done, all.size, 0, node.relativePath))
        }
    }

    private companion object {
        const val TAG = "ScanWorkflow"
    }
}
