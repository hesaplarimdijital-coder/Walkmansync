package com.walkmansync.core.engine

import com.walkmansync.core.fs.TreeNode
import com.walkmansync.core.index.IndexEntry
import com.walkmansync.core.index.IndexStore
import com.walkmansync.core.index.SyncIndex
import com.walkmansync.core.model.ItemFailure
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.SyncItem
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.model.TargetHandle
import com.walkmansync.core.model.TargetLayout
import com.walkmansync.core.model.TransferOutcome
import com.walkmansync.core.model.TransferProgress
import com.walkmansync.core.model.TransferSummary
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.model.CopyReason
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.transfer.TransferControl
import com.walkmansync.core.transfer.TransferEvent
import com.walkmansync.core.transfer.TransferRequest
import com.walkmansync.core.transfer.TransferRunner
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * `.wsync.part` yaz → sync → boyut doğrula → (son ad yoksa) rename protokolüyle atomik aktarım.
 * Ayrıntılı davranış sözleşmesi: [TransferRunner].
 */
class AtomicTransferRunner(
    private val indexStore: IndexStore,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
) : TransferRunner {

    override fun run(request: TransferRequest, control: TransferControl): Flow<TransferEvent> = channelFlow {
        val session = Session(request, control, this)
        val summary = withContext(dispatchers.io) { session.execute() }
        // İptal edilmişse send CancellationException fırlatır ve Finished yayılmaz (sözleşme).
        send(TransferEvent.Finished(summary))
    }

    /** Tek aktarımın değişken durumu; yalnızca aktarım coroutine'inde kullanılır. */
    private inner class Session(
        private val request: TransferRequest,
        private val control: TransferControl,
        private val out: ProducerScope<TransferEvent>,
    ) {
        private val source = request.source
        private val target = request.target
        private val config = request.config
        private val items = request.items
        private val startedAt = clock.nowMillis()
        private val speed = SpeedMeter(clock, config.speedWindowMs)
        private val buffer = ByteArray(config.bufferSize)

        private val failures = ArrayList<ItemFailure>()
        private val failedIds = HashSet<String>()
        private val newEntries = ArrayList<IndexEntry>()
        private val cleanedDirs = HashSet<String>()

        private var completedItems = 0
        private var completedBytes = 0L
        private var skippedCompanions = 0
        private var effectiveTotalBytes = items.sumOf { it.size }
        private var currentItem: SyncItem? = null
        private var currentItemBytes = 0L
        private var writtenTotal = 0L
        private var paused = false
        private var lastEmitNanos: Long? = null

        private var activePart: TreeNode<TargetHandle>? = null
        private var unsavedEntries = 0
        private var sinceFlush = 0
        private var lastFlushNanos = clock.monotonicNanos()
        private var indexSaved = true
        private var fileSystem: TargetFileSystem? = null

        private var fatal: SyncError? = null
        private var cancelled = false

        suspend fun execute(): TransferSummary {
            try {
                emitProgress(force = true)
                checkTotalSpace()
                for (item in items) {
                    if (waitIfPaused()) { cancelled = true; break }
                    if (item.companionOfId != null && item.companionOfId in failedIds) {
                        failedIds.add(item.id)
                        skippedCompanions++
                        effectiveTotalBytes -= item.size
                        emitProgress(force = true)
                        continue
                    }
                    if (!processItem(item)) { cancelled = true; break }
                }
            } catch (e: FatalTransferException) {
                fatal = e.error
            } catch (e: SyncException) {
                fatal = e.error
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                logger.e(TAG, "Aktarım beklenmeyen hatayla durdu", e)
                fatal = unexpected(e)
            } finally {
                // İptal (coroutine) dahil her çıkışta: açık .part sil, biriken kayıtları kaydet.
                withContext(NonCancellable) { finish() }
            }
            return buildSummary()
        }

        // ---- ön kontrol ------------------------------------------------------------------------

        private suspend fun checkTotalSpace() {
            val total = items.sumOf { it.size }
            val free = guardPort(null) { target.freeBytes() } ?: return
            if (total > free) throw FatalTransferException(SyncError.InsufficientSpace(total, free))
        }

        // ---- öğe işleme ------------------------------------------------------------------------

        /** @return false → kullanıcı iptal etti. */
        private suspend fun processItem(item: SyncItem): Boolean {
            currentItem = item
            currentItemBytes = 0
            try {
                while (true) {
                    when (attempt(item)) {
                        Attempt.DONE -> return true
                        Attempt.CANCELLED -> return false
                        Attempt.PAUSED -> if (waitIfPaused(item)) return false
                    }
                }
            } catch (e: FatalTransferException) {
                throw e
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                recordFailure(item, e)
                return true
            } finally {
                currentItem = null
                currentItemBytes = 0
            }
        }

        private suspend fun attempt(item: SyncItem): Attempt {
            val fs = fileSystem ?: guardPort(item) { target.fileSystem() }.also { fileSystem = it }
            val limit = fs.maxFileSize
            // UNKNOWN'da yalnızca kullanıcı açıkça zorladıysa (FORCED) denenir; FAT32'de asla.
            val limitApplies = limit != null && item.size > limit &&
                !(fs == TargetFileSystem.UNKNOWN && item.reason == CopyReason.FORCED)
            if (limitApplies) throw SyncException(SyncError.FileTooLargeForFat32(item.targetPath, item.size))

            val required = item.size + config.freeSpaceMarginBytes
            val free = guardPort(item) { target.freeBytes() }
            if (free != null && free < required) {
                throw FatalTransferException(SyncError.InsufficientSpace(required, free))
            }

            val dir = guardPort(item) { target.ensureDirectory(item.targetDir) }
            val finalName = item.targetName
            cleanDirectoryAndCheckName(item, dir, finalName)

            val partName = TargetLayout.partNameFor(finalName)
            val part = guardPort(item) { target.createFile(dir.handle, partName) }
            activePart = part
            val finalNode: TreeNode<TargetHandle>
            try {
                val result = copyInto(item, part)
                if (result != Attempt.DONE) {
                    deletePart(part)
                    return result
                }
                finalNode = verifyAndRename(item, part)
            } catch (e: Throwable) {
                // İptal dahil her hatada yarım dosya kaldırılır; istisna aynen yeniden fırlatılır.
                // Rename başarılıysa activePart null'dır: son ad ASLA bu yoldan silinmez.
                if (activePart != null) deletePart(part)
                throw e
            }
            recordSuccess(item, finalNode)
            return Attempt.DONE
        }

        /**
         * Dizindeki artık `.wsync.part` dosyalarını (dizin başına bir kez) siler ve son adın var olup
         * olmadığına bakar; varsa üzerine yazmadan [SyncError.TargetExists].
         */
        private suspend fun cleanDirectoryAndCheckName(item: SyncItem, dir: TreeNode<TargetHandle>, finalName: String) {
            val children = guardPort(item) { target.list(dir.handle) }
            val wanted = SyncIndex.foldPath(finalName)
            if (children.any { SyncIndex.foldPath(it.name) == wanted }) {
                throw SyncException(SyncError.TargetExists(item.targetPath))
            }
            val ownPart = SyncIndex.foldPath(TargetLayout.partNameFor(finalName))
            val firstVisit = cleanedDirs.add(SyncIndex.foldPath(dir.relativePath))
            for (child in children) {
                if (child.isDirectory || !TargetLayout.isPartFile(child.name)) continue
                if (firstVisit || SyncIndex.foldPath(child.name) == ownPart) {
                    guardPort(item) { target.delete(child) }
                }
            }
        }

        /** Kaynaktan hedefe tampon döngüsü. Duraklat/iptal anında [Attempt.PAUSED]/[Attempt.CANCELLED]. */
        private suspend fun copyInto(item: SyncItem, part: TreeNode<TargetHandle>): Attempt {
            var outcome = Attempt.DONE
            var read = 0L
            val input = guardPort(item) { source.openInput(item.source.handle) }
            input.use { stream ->
                val output = guardPort(item) { target.openOutput(part) }
                output.use { sink ->
                    try {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (control.cancelled.value) { outcome = Attempt.CANCELLED; break }
                            if (control.paused.value) { outcome = Attempt.PAUSED; break }
                            val n = try {
                                stream.read(buffer, 0, buffer.size)
                            } catch (e: SecurityException) {
                                throw SyncException(SyncError.PermissionLost(VolumeRole.SOURCE), e)
                            }
                            if (n < 0) break
                            if (n == 0) continue
                            read += n
                            if (read > item.size) break // kaynak büyümüş; aşağıda SizeMismatch
                            try {
                                sink.write(buffer, 0, n)
                            } catch (e: SecurityException) {
                                throw SyncException(SyncError.PermissionLost(VolumeRole.TARGET), e)
                            }
                            currentItemBytes = read
                            writtenTotal += n
                            emitProgress(force = false)
                        }
                        if (outcome == Attempt.DONE) sink.sync()
                    } catch (e: IOException) {
                        // UNKNOWN dosya sisteminde zorlanan > 4 GiB dosya gerçek FAT32 kartta EFBIG ile düşer:
                        // dosyaya özgü hata; .part, attempt() içindeki temizlikte silinir.
                        if (isFileTooLarge(e)) {
                            throw SyncException(SyncError.FileTooLargeForFat32(item.targetPath, item.size), e)
                        }
                        throw SyncException(
                            classifyIoFailure(item.targetPath, e, source, target, item.size + config.freeSpaceMarginBytes) {
                                target.freeBytes()
                            },
                            e,
                        )
                    }
                }
            }
            if (outcome == Attempt.DONE && read != item.size) {
                if (read == 0L) throw SyncException(SyncError.SourceGone(item.id))
                throw SyncException(SyncError.SizeMismatch(item.id, item.size, read))
            }
            return outcome
        }

        /** Boyutu doğrular ve `.part`'ı son ada çevirir. Başarıda [activePart] null olur. */
        private suspend fun verifyAndRename(item: SyncItem, part: TreeNode<TargetHandle>): TreeNode<TargetHandle> {
            val node = guardPort(item) { target.resolve(part.relativePath) }
                ?: throw SyncException(SyncError.IoFailure(item.targetPath, ".part kayboldu", fatal = false))
            if (node.size != item.size) {
                throw SyncException(SyncError.SizeMismatch(item.targetPath, item.size, node.size))
            }
            val finalNode = guardPort(item) { target.rename(node, item.targetName) }
            activePart = null
            return finalNode
        }

        private suspend fun recordSuccess(item: SyncItem, finalNode: TreeNode<TargetHandle>) {
            completedItems++
            completedBytes += item.size
            currentItemBytes = 0
            newEntries.add(
                IndexEntry(
                    sourcePath = item.source.relativePath,
                    sourceSize = item.source.size,
                    sourceModified = item.source.lastModified,
                    targetPath = finalNode.relativePath,
                    targetSize = item.size,
                    contentKey = item.contentKey,
                    syncedAtMillis = clock.nowMillis(),
                ),
            )
            unsavedEntries++
            sinceFlush++
            speed.record(writtenTotal)
            emitProgress(force = true)
            maybeFlushIndex()
        }

        // ---- indeks ----------------------------------------------------------------------------

        private suspend fun maybeFlushIndex() {
            val elapsedMs = (clock.monotonicNanos() - lastFlushNanos) / 1_000_000L
            if (sinceFlush < config.indexFlushEvery && elapsedMs < config.indexFlushIntervalMs) return
            try {
                saveIndex()
            } catch (e: SyncException) {
                if (e.error.isFatal) throw FatalTransferException(e.error)
                logger.w(TAG, "İndeks ara kaydı başarısız; sonra yeniden denenecek", e)
            }
            sinceFlush = 0
            lastFlushNanos = clock.monotonicNanos()
        }

        private suspend fun saveIndex() {
            val index = request.baseIndex.withEntries(newEntries.toList(), clock.nowMillis(), request.plan.pair.source)
            indexStore.save(target, index)
            unsavedEntries = 0
        }

        // ---- hata kaydı ------------------------------------------------------------------------

        private suspend fun recordFailure(item: SyncItem, e: Exception) {
            val error = classify(item, e)
            logger.w(TAG, "Öğe başarısız: ${item.targetPath} -> $error", e)
            if (error.isFatal) throw FatalTransferException(error)
            failures.add(ItemFailure(item.id, item.targetPath, error))
            failedIds.add(item.id)
            effectiveTotalBytes -= item.size
            emitProgress(force = true)
        }

        private suspend fun classify(item: SyncItem, e: Exception): SyncError {
            if (e is SyncException) {
                val err = e.error
                // Genel G/Ç hatası: kart mı gitti, dosya mı sorunlu?
                if (err is SyncError.IoFailure && !err.isFatal) {
                    return classifyIoFailure(err.path ?: item.targetPath, e, source, target,
                        item.size + config.freeSpaceMarginBytes) { target.freeBytes() }
                }
                return err
            }
            if (e is IOException) {
                return classifyIoFailure(item.targetPath, e, source, target,
                    item.size + config.freeSpaceMarginBytes) { target.freeBytes() }
            }
            return unexpected(e)
        }

        /**
         * Port çağrısını sarar: ham [IOException] → sınıflandırılmış [SyncException]. İptal ve
         * [SyncException] olduğu gibi geçer.
         */
        private suspend fun <T> guardPort(item: SyncItem?, block: suspend () -> T): T {
            try {
                return block()
            } catch (e: IOException) {
                val error = classifyIoFailure(item?.targetPath, e, source, target,
                    (item?.size ?: 0L) + config.freeSpaceMarginBytes) { target.freeBytes() }
                throw SyncException(error, e)
            }
        }

        // ---- duraklat / ilerleme ---------------------------------------------------------------

        /** Duraklatılmışsa bekler. @return bekleme sonunda iptal edilmişse true. */
        private suspend fun waitIfPaused(item: SyncItem? = currentItem): Boolean {
            if (control.paused.value) {
                paused = true
                speed.reset()
                currentItem = item
                currentItemBytes = 0
                emitProgress(force = true)
            }
            val wasCancelled = control.awaitResumedOrCancelled()
            if (paused) {
                paused = false
                speed.reset()
                speed.record(writtenTotal)
                emitProgress(force = true)
            }
            return wasCancelled
        }

        private fun emitProgress(force: Boolean) {
            val now = clock.monotonicNanos()
            val last = lastEmitNanos
            if (!force && last != null && now - last < config.progressIntervalMs * 1_000_000L) return
            lastEmitNanos = now
            speed.record(writtenTotal)
            val bps = if (paused) 0L else speed.bytesPerSecond()
            val written = completedBytes + currentItemBytes
            val remaining = (effectiveTotalBytes - written).coerceAtLeast(0)
            val eta = if (paused || bps <= 0L) null else remaining * 1000L / bps
            out.trySend(
                TransferEvent.Progress(
                    TransferProgress(
                        totalItems = items.size,
                        completedItems = completedItems,
                        failedItems = failures.size + skippedCompanions,
                        totalBytes = effectiveTotalBytes,
                        completedBytes = written,
                        bytesPerSecond = bps,
                        etaMillis = eta,
                        currentItem = currentItem,
                        currentItemBytes = currentItemBytes,
                        startedAtMillis = startedAt,
                        isPaused = paused,
                    ),
                ),
            )
        }

        // ---- bitiş -----------------------------------------------------------------------------

        private suspend fun deletePart(part: TreeNode<TargetHandle>) {
            withContext(NonCancellable) {
                try {
                    target.delete(part)
                    if (activePart == part) activePart = null
                } catch (e: SyncException) {
                    logger.w(TAG, "Yarım dosya silinemedi: ${part.relativePath}", e)
                } catch (e: IOException) {
                    logger.w(TAG, "Yarım dosya silinemedi: ${part.relativePath}", e)
                }
            }
        }

        /** NonCancellable içinde çağrılır. */
        private suspend fun finish() {
            val leftover = activePart
            if (leftover != null && target.isAvailable()) deletePart(leftover)
            activePart = null
            currentItem = null
            if (unsavedEntries > 0) {
                indexSaved = false
                if (target.isAvailable()) {
                    try {
                        saveIndex()
                        indexSaved = true
                    } catch (e: SyncException) {
                        logger.e(TAG, "Son indeks kaydı başarısız", e)
                    } catch (e: IOException) {
                        logger.e(TAG, "Son indeks kaydı başarısız", e)
                    }
                }
            }
        }

        private fun buildSummary(): TransferSummary {
            val outcome = when {
                fatal != null -> TransferOutcome.ABORTED
                cancelled -> TransferOutcome.CANCELLED
                failures.isEmpty() && skippedCompanions == 0 -> TransferOutcome.COMPLETED
                else -> TransferOutcome.COMPLETED_WITH_ERRORS
            }
            return TransferSummary(
                outcome = outcome,
                totalItems = items.size,
                copiedItems = completedItems,
                copiedBytes = completedBytes,
                failures = failures.toList(),
                notAttempted = items.size - completedItems - failures.size,
                startedAtMillis = startedAt,
                finishedAtMillis = clock.nowMillis(),
                fatalError = fatal,
                indexSaved = indexSaved,
            )
        }
    }

    private enum class Attempt { DONE, PAUSED, CANCELLED }

    private companion object {
        const val TAG = "TransferRunner"
    }
}
