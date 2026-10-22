package com.walkmansync.storage

import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.walkmansync.core.fs.GuardedTargetTree
import com.walkmansync.core.fs.RandomAccessSource
import com.walkmansync.core.fs.TargetGuardViolation
import com.walkmansync.core.fs.TargetOutput
import com.walkmansync.core.fs.TreeNode
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.model.TargetHandle
import com.walkmansync.core.model.TargetLayout
import com.walkmansync.core.model.VolumeId
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Hedef (Walkman) kart ağacı. Append-only kurallarını [GuardedTargetTree] zorlar; bu sınıf yalnızca
 * `do*` metotlarını SAF ile uygular. Ek savunma: [doDelete] belgenin ŞU ANKİ adını yeniden sorgular ve
 * kendi artığımız değilse silmez (rename sonrası bayat tutamaç gerçek müzik dosyasını silmesin).
 */
internal class SafTargetTree(
    private val ctx: SafTreeContext,
    private val probe: FileSystemProbe,
) : GuardedTargetTree() {

    private val reader = SafReader(ctx, ::TargetHandle)

    /** Bu örnek ömrünce çözülmüş dizinler (katlanmış yol → düğüm). Hata olunca temizlenir. */
    private val dirCache = HashMap<String, TreeNode<TargetHandle>>()

    @Volatile private var cachedFs: TargetFileSystem? = null

    override val volume: VolumeId = ctx.volume
    override val root: TreeNode<TargetHandle> get() = reader.root

    override suspend fun list(dir: TargetHandle): List<TreeNode<TargetHandle>> = reader.list(dir.id)
    override suspend fun resolve(relativePath: String): TreeNode<TargetHandle>? = reader.resolve(relativePath)
    override suspend fun openInput(file: TargetHandle): InputStream = reader.openInput(file.id)
    override suspend fun openRandomAccess(file: TargetHandle): RandomAccessSource = reader.openRandomAccess(file.id)
    override suspend fun isAvailable(): Boolean = reader.isAvailable()

    override suspend fun freeBytes(): Long? = withContext(ctx.dispatchers.io) {
        ctx.guarded(null) { probe.freeBytes(ctx.volume) }
    }

    override suspend fun fileSystem(): TargetFileSystem {
        cachedFs?.let { return it }
        val detected = withContext(ctx.dispatchers.io) { probe.fileSystem(ctx.volume) }
        cachedFs = detected
        return detected
    }

    // ---- GuardedTargetTree uygulamaları ----------------------------------------------------------

    override suspend fun doEnsureDirectory(relativePath: String): TreeNode<TargetHandle> =
        withContext(ctx.dispatchers.io) {
            val segments = segmentsBelowRoot(relativePath)
            var current = reader.root
            for (segment in segments) {
                val childPath = if (current.relativePath.isEmpty()) segment else "${current.relativePath}/$segment"
                val key = foldName(childPath)
                val cached = dirCache[key]
                if (cached != null) {
                    current = cached
                    continue
                }
                val existing = reader.listBlocking(current.handle.id).firstOrNull { foldName(it.name) == foldName(segment) }
                current = when {
                    existing == null -> createDirectory(current, segment)
                    existing.isDirectory -> existing
                    else -> throw SyncException(SyncError.TargetExists(existing.relativePath))
                }
                dirCache[key] = current
            }
            current
        }

    override suspend fun doCreateFile(parent: TargetHandle, name: String): TreeNode<TargetHandle> =
        withContext(ctx.dispatchers.io) {
            ctx.requireOwn(parent.id)
            val parentPath = reader.relativePathOf(parent.id)
            val displayPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            try {
                val siblings = reader.listBlocking(parent.id)
                if (siblings.any { foldName(it.name) == foldName(name) }) {
                    throw SyncException(SyncError.TargetExists(displayPath))
                }
                ctx.guarded(displayPath) {
                    // MIME octet-stream: sağlayıcı adın uzantısını MIME'a göre değiştirmesin (".wsync.part" korunur).
                    val uri = DocumentsContract.createDocument(
                        ctx.resolver, ctx.docUri(parent.id), "application/octet-stream", name,
                    ) ?: throw IOException("Belge oluşturulamadı: $displayPath")
                    val docId = DocumentsContract.getDocumentId(uri)
                    val doc = SafCursor.queryDocument(ctx.resolver, ctx.treeUri, docId)
                        ?: throw IOException("Oluşturulan belge okunamadı: $displayPath")
                    if (doc.name != name) {
                        // Sağlayıcı adı değiştirdi ("x (1)"): kendi oluşturduğumuzu sil, üzerine yazma yok.
                        deleteQuietly(docId)
                        throw SyncException(SyncError.TargetExists(displayPath))
                    }
                    reader.nodeOf(doc)
                }
            } catch (e: SyncException) {
                dirCache.clear()
                throw e
            }
        }

    override suspend fun doOpenOutput(file: TreeNode<TargetHandle>): TargetOutput =
        openOnIo(ctx) {
            ctx.requireOwn(file.handle.id)
            ctx.guarded(file.relativePath) {
                val pfd = ctx.resolver.openFileDescriptor(ctx.docUri(file.handle.id), "wt")
                    ?: throw IOException("Yazma için açılamadı: ${file.relativePath}")
                PfdTargetOutput(pfd)
            }
        }

    override suspend fun doRename(file: TreeNode<TargetHandle>, newName: String): TreeNode<TargetHandle> =
        withContext(ctx.dispatchers.io) {
            ctx.requireOwn(file.handle.id)
            val parentPath = file.relativePath.substringBeforeLast('/', "")
            val parentId = reader.docIdFor(parentPath)
            val newPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"
            val siblings = reader.listBlocking(parentId)
            if (siblings.any { it.handle != file.handle && foldName(it.name) == foldName(newName) }) {
                throw SyncException(SyncError.TargetExists(newPath))
            }
            ctx.guarded(newPath) {
                val renamed = try {
                    DocumentsContract.renameDocument(ctx.resolver, ctx.docUri(file.handle.id), newName)
                } catch (e: IllegalStateException) {
                    // ExternalStorageProvider "Failed to rename" (File.renameTo false). Kart söküldüyse ölümcül
                    // VolumeMissing; ad bu arada kapıldıysa TargetExists (üzerine yazılmaz); aksi hâlde dosyaya özgü G/Ç.
                    if (!ctx.volumeMounted()) throw SyncException(SyncError.VolumeMissing(ctx.role, ctx.volume), e)
                    val taken = reader.listBlocking(parentId)
                        .any { it.handle != file.handle && foldName(it.name) == foldName(newName) }
                    if (taken) throw SyncException(SyncError.TargetExists(newPath), e)
                    throw IOException("Yeniden adlandırılamadı: ${file.relativePath} → $newName", e)
                }
                // null → sağlayıcı belge kimliğinin değişmediğini bildirir (başarı olabilir); adı doğrulayarak karar veririz.
                val newDocId = renamed?.let { DocumentsContract.getDocumentId(it) } ?: file.handle.id
                val doc = SafCursor.queryDocument(ctx.resolver, ctx.treeUri, newDocId)
                    ?: throw IOException("Yeniden adlandırılan belge okunamadı: $newPath")
                if (renamed == null && foldName(doc.name) != foldName(newName)) {
                    throw IOException("Yeniden adlandırılamadı: ${file.relativePath} → $newName")
                }
                dirCache.clear()
                reader.nodeOf(doc)
            }
        }

    override suspend fun doDelete(file: TreeNode<TargetHandle>) {
        withContext(ctx.dispatchers.io) {
            ctx.requireOwn(file.handle.id)
            ctx.guarded(file.relativePath) {
                // Belge yoksa (sözleşme: sessizce dön) sağlayıcı fırlatabilir → queryExisting null döner.
                val current = reader.queryExisting(file.handle.id)
                if (current != null) {
                    val ours = TargetLayout.isPartFile(current.name) || current.name == TargetLayout.INDEX_BACKUP_NAME
                    if (!ours) {
                        ctx.logger.w(TAG, "Silme reddedildi, belge artık kendi artığımız değil: ${current.name}")
                    } else if (!deleteQuietly(file.handle.id) && reader.queryExisting(file.handle.id) != null) {
                        throw IOException("Silinemedi: ${file.relativePath}")
                    }
                }
            }
        }
    }

    // ---- yardımcılar --------------------------------------------------------------------------------

    /** `MUSIC/A/B` → ağaç köküne göre kalan parçalar; kök dışı yol programlama hatasıdır. */
    private fun segmentsBelowRoot(relativePath: String): List<String> {
        val rel = relativePath.trim('/')
        if (!reader.isInsideRoot(rel)) {
            throw TargetGuardViolation("Dizin ağaç kökü dışında: '$relativePath' (kök='${reader.rootRelativePath}')")
        }
        val rest = rel.substring(minOf(rel.length, reader.rootRelativePath.length)).trim('/')
        return if (rest.isEmpty()) emptyList() else rest.split('/')
    }

    private fun createDirectory(parent: TreeNode<TargetHandle>, name: String): TreeNode<TargetHandle> {
        val path = if (parent.relativePath.isEmpty()) name else "${parent.relativePath}/$name"
        return ctx.guarded(path) {
            val uri = DocumentsContract.createDocument(
                ctx.resolver, ctx.docUri(parent.handle.id), DocumentsContract.Document.MIME_TYPE_DIR, name,
            ) ?: throw IOException("Dizin oluşturulamadı: $path")
            val docId = DocumentsContract.getDocumentId(uri)
            val doc = SafCursor.queryDocument(ctx.resolver, ctx.treeUri, docId)
                ?: throw IOException("Oluşturulan dizin okunamadı: $path")
            reader.nodeOf(doc)
        }
    }

    /** Yalnızca kendi oluşturduğumuz belge için; yok/başarısızsa false. */
    private fun deleteQuietly(docId: String): Boolean = try {
        DocumentsContract.deleteDocument(ctx.resolver, ctx.docUri(docId))
    } catch (e: java.io.FileNotFoundException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    /** `wt` kipinde açılmış fd üzerine yazma; [close] fd'yi de bırakır. */
    private class PfdTargetOutput(pfd: ParcelFileDescriptor) : TargetOutput {
        private val out: FileOutputStream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        override var bytesWritten: Long = 0L
            private set

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            bytesWritten += length
        }

        override fun sync() {
            out.flush()
            out.fd.sync()
        }

        override fun close() {
            out.close()
        }
    }

    private companion object {
        const val TAG = "SafTargetTree"
    }
}
