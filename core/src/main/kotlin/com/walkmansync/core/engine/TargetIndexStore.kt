package com.walkmansync.core.engine

import com.walkmansync.core.fs.TargetTree
import com.walkmansync.core.fs.TreeNode
import com.walkmansync.core.index.IndexCodec
import com.walkmansync.core.index.IndexFormatException
import com.walkmansync.core.index.IndexLoadResult
import com.walkmansync.core.index.IndexStore
import com.walkmansync.core.index.SyncIndex
import com.walkmansync.core.index.UnsupportedIndexVersionException
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.TargetHandle
import com.walkmansync.core.model.TargetLayout
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * İndeksin hedef karttaki kalıcılığı (protokol: bkz. [IndexStore]). Codec bir port olarak enjekte edilir.
 * Yazma sırası `INDEX.wsync.part → (INDEX → INDEX.bak) → INDEX`; her adım arasında en az bir geçerli sürüm kalır.
 */
class TargetIndexStore(
    private val codec: IndexCodec,
    private val logger: Logger,
    private val dispatchers: AppDispatchers = AppDispatchers.Default,
) : IndexStore {

    private val saveMutex = Mutex()

    /** Tek dosyanın okuma sonucu. */
    private sealed interface Read {
        data object Absent : Read
        data class Ok(val index: SyncIndex) : Read
        data class Corrupt(val detail: String) : Read
        data class Unsupported(val version: Int) : Read
    }

    override suspend fun load(tree: TargetTree): IndexLoadResult {
        val root = tree.root.relativePath
        val main = readOne(tree, TargetLayout.indexPathFor(root))
        if (main is Read.Unsupported) return IndexLoadResult.Unsupported(main.version)
        if (main is Read.Ok) return classify(tree, main.index, recovered = false)

        val backup = readOne(tree, TargetLayout.indexBackupPathFor(root))
        return when (backup) {
            is Read.Ok -> {
                logger.w(TAG, "Ana indeks ${if (main is Read.Corrupt) "bozuk" else "yok"}; yedekten kurtarıldı")
                classify(tree, backup.index, recovered = true)
            }
            is Read.Unsupported -> IndexLoadResult.Unsupported(backup.version)
            is Read.Corrupt -> IndexLoadResult.Corrupt("yedek: ${backup.detail}")
            Read.Absent -> when (main) {
                is Read.Corrupt -> IndexLoadResult.Corrupt(main.detail)
                else -> IndexLoadResult.Missing
            }
        }
    }

    private fun classify(tree: TargetTree, index: SyncIndex, recovered: Boolean): IndexLoadResult =
        if (index.targetVolume != tree.volume) IndexLoadResult.Foreign(index.targetVolume.uuid)
        else IndexLoadResult.Loaded(index, recovered)

    private suspend fun readOne(tree: TargetTree, path: String): Read {
        val node = tree.resolve(path) ?: return Read.Absent
        if (node.isDirectory) return Read.Corrupt("dizin: $path")
        if (node.size > SyncIndex.MAX_FILE_BYTES) return Read.Corrupt("çok büyük: ${node.size} B")
        val bytes = try {
            withContext(dispatchers.io) {
                tree.openInput(node.handle).use { it.readBytes() }
            }
        } catch (e: IOException) {
            throw SyncException(ioError(tree, path, e), e)
        }
        if (bytes.size.toLong() > SyncIndex.MAX_FILE_BYTES) return Read.Corrupt("çok büyük: ${bytes.size} B")
        return try {
            Read.Ok(withContext(dispatchers.cpu) { codec.decode(String(bytes, Charsets.UTF_8)) })
        } catch (e: UnsupportedIndexVersionException) {
            Read.Unsupported(e.schemaVersion)
        } catch (e: IndexFormatException) {
            logger.w(TAG, "İndeks ayrıştırılamadı: $path", e)
            Read.Corrupt(e.message ?: "biçim hatası")
        }
    }

    override suspend fun save(tree: TargetTree, index: SyncIndex) {
        val bytes = withContext(dispatchers.cpu) { codec.encode(index).toByteArray(Charsets.UTF_8) }
        if (bytes.size.toLong() > SyncIndex.MAX_FILE_BYTES) {
            throw SyncException(SyncError.IndexCorrupt("indeks çok büyük: ${bytes.size} B"))
        }
        saveMutex.withLock {
            // Yarım kalmış bir indeks yazımı kart üzerinde tutarsız bırakılmasın diye iptalden korunur.
            withContext(NonCancellable + dispatchers.io) { writeAtomically(tree, bytes) }
        }
    }

    private suspend fun writeAtomically(tree: TargetTree, bytes: ByteArray) {
        val root = tree.root.relativePath
        val indexPath = TargetLayout.indexPathFor(root)
        val backupPath = TargetLayout.indexBackupPathFor(root)
        val partPath = TargetLayout.indexPartPathFor(root)

        tree.resolve(partPath)?.let { tree.delete(it) }
        val part = tree.createIndexPart()
        try {
            writeAll(tree, part, bytes)
            val written = tree.resolve(partPath)
                ?: throw IOException("İndeks .part kayboldu: $partPath")
            if (written.size != bytes.size.toLong()) {
                throw IOException("İndeks boyutu tutmadı: beklenen=${bytes.size} gerçek=${written.size}")
            }
            val current = tree.resolve(indexPath)
            if (current != null) {
                tree.resolve(backupPath)?.let { tree.delete(it) }
                tree.rename(current, TargetLayout.INDEX_BACKUP_NAME)
            }
            tree.rename(written, TargetLayout.INDEX_FILE_NAME)
        } catch (e: IOException) {
            deletePartQuietly(tree, partPath)
            throw SyncException(ioError(tree, indexPath, e), e)
        } catch (e: SyncException) {
            deletePartQuietly(tree, partPath)
            throw e
        }
    }

    private suspend fun writeAll(tree: TargetTree, part: TreeNode<TargetHandle>, bytes: ByteArray) {
        tree.openOutput(part).use { out ->
            var offset = 0
            while (offset < bytes.size) {
                val n = minOf(WRITE_CHUNK, bytes.size - offset)
                out.write(bytes, offset, n)
                offset += n
            }
            out.sync()
        }
    }

    private suspend fun deletePartQuietly(tree: TargetTree, partPath: String) {
        try {
            tree.resolve(partPath)?.let { tree.delete(it) }
        } catch (e: SyncException) {
            logger.w(TAG, "İndeks .part silinemedi", e)
        } catch (e: IOException) {
            logger.w(TAG, "İndeks .part silinemedi", e)
        }
    }

    private suspend fun ioError(tree: TargetTree, path: String, e: IOException): SyncError =
        if (isNoSpace(e)) SyncError.InsufficientSpace(0, null)
        else if (!tree.isAvailable()) SyncError.VolumeMissing(roleOf(tree), tree.volume)
        else SyncError.IoFailure(path, e.message, fatal = false)

    private companion object {
        const val TAG = "IndexStore"
        const val WRITE_CHUNK = 64 * 1024
    }
}
