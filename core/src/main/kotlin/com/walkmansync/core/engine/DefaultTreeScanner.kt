package com.walkmansync.core.engine

import com.walkmansync.core.fs.ReadableTree
import com.walkmansync.core.fs.TreeNode
import com.walkmansync.core.model.FileEntry
import com.walkmansync.core.model.NodeHandle
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.TargetLayout
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.scan.ScanExclusions
import com.walkmansync.core.scan.TreeScanResult
import com.walkmansync.core.scan.TreeScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong

/**
 * Seviye seviye (BFS) özyinelemeli tarayıcı. Aynı seviyedeki dizinler en fazla [MAX_CONCURRENT_LISTINGS]
 * eşzamanlılıkla listelenir; sonuçlar tek coroutine'de birleştirilir (kilit gerekmez).
 */
class DefaultTreeScanner(
    private val dispatchers: AppDispatchers,
    private val logger: Logger,
    private val clock: Clock = Clock.System,
) : TreeScanner {

    private class Listing<H : NodeHandle>(val dir: TreeNode<H>, val children: List<TreeNode<H>>?)

    override suspend fun <H : NodeHandle> scan(
        tree: ReadableTree<H>,
        startPath: String?,
        onProgress: (filesFound: Int, directoriesVisited: Int, currentPath: String) -> Unit,
    ): TreeScanResult<H> = withContext(dispatchers.io) {
        val start: TreeNode<H>? = if (startPath == null) tree.root else tree.resolve(startPath.trim('/'))
        if (start == null || !start.isDirectory) {
            return@withContext TreeScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        }

        val files = ArrayList<FileEntry<H>>()
        val parts = ArrayList<TreeNode<H>>()
        val directories = ArrayList<String>()
        val unreadable = ArrayList<String>()
        val visited = HashSet<String>()
        visited.add(start.handle.id)

        val semaphore = Semaphore(MAX_CONCURRENT_LISTINGS)
        val lastEmit = AtomicLong(clock.monotonicNanos() - PROGRESS_INTERVAL_NANOS)
        var level: List<TreeNode<H>> = listOf(start)

        while (level.isNotEmpty()) {
            val listings = coroutineScope {
                level.map { dir -> async { semaphore.withPermit { listDirectory(tree, dir) } } }.awaitAll()
            }
            val next = ArrayList<TreeNode<H>>()
            for (listing in listings) {
                val dir = listing.dir
                directories.add(dir.relativePath)
                val children = listing.children
                if (children == null) {
                    unreadable.add(dir.relativePath)
                    continue
                }
                for (child in children) {
                    if (child.isDirectory) {
                        if (ScanExclusions.isExcludedDirectory(child.name, dir.relativePath)) continue
                        if (visited.add(child.handle.id)) next.add(child)
                    } else if (TargetLayout.isPartFile(child.name)) {
                        parts.add(child)
                    } else if (ScanExclusions.isCandidateFile(child.name)) {
                        files.add(toEntry(child))
                    }
                }
                emitProgress(lastEmit, onProgress, files.size, directories.size, dir.relativePath)
            }
            level = next
        }

        files.sortBy { it.relativePath }
        TreeScanResult(files, parts, directories, unreadable)
    }

    /** Dizini listeler; dosyaya/dizine özgü hata → children=null, ölümcül hata ve iptal yayılır. */
    private suspend fun <H : NodeHandle> listDirectory(tree: ReadableTree<H>, dir: TreeNode<H>): Listing<H> {
        try {
            return Listing(dir, tree.list(dir.handle))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            if (e.error.isFatal) throw e
            failIfVolumeGone(tree, e)
            logger.w(TAG, "Dizin listelenemedi: ${dir.relativePath}", e)
            return Listing(dir, null)
        } catch (e: IOException) {
            failIfVolumeGone(tree, e)
            logger.w(TAG, "Dizin listelenemedi (G/Ç): ${dir.relativePath}", e)
            return Listing(dir, null)
        }
    }

    /** Hata sonrası birim gitmişse sessizce "okunamayan dizin" demek yerine ölümcül hata fırlatır. */
    private suspend fun failIfVolumeGone(tree: ReadableTree<*>, cause: Exception) {
        if (!tree.isAvailable()) {
            throw SyncException(SyncError.VolumeMissing(roleOf(tree), tree.volume), cause)
        }
    }

    private fun <H : NodeHandle> toEntry(node: TreeNode<H>): FileEntry<H> =
        FileEntry(
            handle = node.handle,
            relativePath = nfc(node.relativePath),
            name = nfc(node.name),
            size = node.size,
            lastModified = node.lastModified,
        )

    private fun emitProgress(
        lastEmit: AtomicLong,
        onProgress: (Int, Int, String) -> Unit,
        files: Int,
        dirs: Int,
        path: String,
    ) {
        val now = clock.monotonicNanos()
        val prev = lastEmit.get()
        if (now - prev >= PROGRESS_INTERVAL_NANOS && lastEmit.compareAndSet(prev, now)) {
            onProgress(files, dirs, path)
        }
    }

    private fun nfc(s: String): String =
        if (Normalizer.isNormalized(s, Normalizer.Form.NFC)) s else Normalizer.normalize(s, Normalizer.Form.NFC)

    companion object {
        const val MAX_CONCURRENT_LISTINGS = 4
        private const val PROGRESS_INTERVAL_NANOS = 100L * 1_000_000L
        private const val TAG = "TreeScanner"
    }
}
