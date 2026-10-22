// MİMAR — değiştirilmez. Ortak test sahteleri (B1 ve B2 testleri kullanır, değiştirmez; ek ihtiyaç kendi
// test dosyalarında alt sınıf/yardımcı olarak yazılır).
package com.walkmansync.core.testing

import com.walkmansync.core.fs.ByteArrayRandomAccessSource
import com.walkmansync.core.fs.GuardedTargetTree
import com.walkmansync.core.fs.RandomAccessSource
import com.walkmansync.core.fs.ReadableTree
import com.walkmansync.core.fs.SourceTree
import com.walkmansync.core.fs.TargetOutput
import com.walkmansync.core.fs.TreeNode
import com.walkmansync.core.model.DocumentIds
import com.walkmansync.core.model.NodeHandle
import com.walkmansync.core.model.SourceHandle
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.model.TargetHandle
import com.walkmansync.core.model.VolumeId
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.TreeMap

/**
 * Bellek içi FAT benzeri birim: yollar büyük/küçük harf DUYARSIZ eşlenir, gerçek harf büyüklüğü saklanır.
 * Tutamaç kimliği SAF gibi "UUID:göreli/yol" biçimindedir. Thread-safe (tek kilit).
 */
class InMemoryVolume(val id: VolumeId) {
    private class Node(var path: String, val isDir: Boolean, var data: ByteArray, var mtime: Long)

    private val lock = Any()
    private val nodes = TreeMap<String, Node>() // anahtar: katlanmış yol ("" = kök)

    /** false → tüm işlemler VolumeMissing fırlatır (kart çıkarıldı). */
    @Volatile var mounted: Boolean = true

    /** Tanımlıysa her yazma tamponunda çağrılır; fırlatırsa yazma başarısız olur (kopma benzetimi). */
    @Volatile var onWrite: ((path: String, totalWritten: Long) -> Unit)? = null

    @Volatile var freeBytes: Long? = Long.MAX_VALUE / 4

    init {
        nodes[""] = Node("", true, ByteArray(0), 0)
    }

    private fun fold(p: String) = p.trim('/').lowercase(Locale.ROOT)

    fun checkMounted(role: com.walkmansync.core.model.VolumeRole) {
        if (!mounted) throw SyncException(SyncError.VolumeMissing(role, id))
    }

    /** Test kurulumu: dosya ekler (ara dizinler otomatik). */
    fun putFile(path: String, data: ByteArray, mtime: Long = 0L) = synchronized(lock) {
        val p = path.trim('/')
        mkdirs(p.substringBeforeLast('/', ""))
        nodes[fold(p)] = Node(p, false, data, mtime)
    }

    fun putFile(path: String, size: Int, mtime: Long = 0L) = putFile(path, ByteArray(size) { (it % 251).toByte() }, mtime)

    fun mkdirs(path: String): Unit = synchronized(lock) {
        if (path.isEmpty()) return
        var cur = ""
        for (part in path.trim('/').split('/')) {
            val existing = nodes[fold(if (cur.isEmpty()) part else "$cur/$part")]
            cur = existing?.path ?: (if (cur.isEmpty()) part else "$cur/$part")
            if (existing == null) nodes[fold(cur)] = Node(cur, true, ByteArray(0), 0)
            else if (!existing.isDir) throw IOException("Dosya var: $cur")
        }
    }

    fun exists(path: String): Boolean = synchronized(lock) { nodes.containsKey(fold(path)) }
    fun read(path: String): ByteArray? = synchronized(lock) { nodes[fold(path)]?.takeIf { !it.isDir }?.data?.copyOf() }
    fun realPath(path: String): String? = synchronized(lock) { nodes[fold(path)]?.path }

    /** Tüm dosya yolları (gerçek harf büyüklüğüyle), sıralı. */
    fun allFiles(): List<String> = synchronized(lock) { nodes.values.filter { !it.isDir }.map { it.path }.sorted() }

    internal fun <H : NodeHandle> nodeOf(path: String, wrap: (String) -> H): TreeNode<H>? = synchronized(lock) {
        val n = nodes[fold(path)] ?: return null
        TreeNode(wrap(DocumentIds.build(id, n.path)), n.path, n.path.substringAfterLast('/'), n.isDir,
            if (n.isDir) 0 else n.data.size.toLong(), n.mtime)
    }

    internal fun <H : NodeHandle> children(dirPath: String, wrap: (String) -> H): List<TreeNode<H>> = synchronized(lock) {
        val d = nodes[fold(dirPath)] ?: throw IOException("Dizin yok: $dirPath")
        if (!d.isDir) throw IOException("Dizin değil: $dirPath")
        val prefix = if (d.path.isEmpty()) "" else fold(d.path) + "/"
        nodes.entries.filter { (k, _) -> k.isNotEmpty() && k.startsWith(prefix) && '/' !in k.substring(prefix.length) }
            .map { (_, n) -> nodeOf(n.path, wrap)!! }
    }

    internal fun pathOf(handleId: String): String {
        val parsed = DocumentIds.parse(handleId) ?: throw IOException("Geçersiz tutamaç: $handleId")
        if (parsed.volume != id) throw SyncException(SyncError.VolumeMismatch(com.walkmansync.core.model.VolumeRole.TARGET, id, parsed.volume.uuid))
        return parsed.relativePath
    }

    internal fun createEmpty(path: String) = synchronized(lock) {
        if (nodes.containsKey(fold(path))) throw SyncException(SyncError.TargetExists(path))
        nodes[fold(path)] = Node(path, false, ByteArray(0), 0)
    }

    internal fun write(path: String, data: ByteArray) = synchronized(lock) {
        val n = nodes[fold(path)] ?: throw IOException("Dosya yok: $path")
        n.data = data
    }

    internal fun rename(path: String, newPath: String) = synchronized(lock) {
        if (nodes.containsKey(fold(newPath))) throw SyncException(SyncError.TargetExists(newPath))
        val n = nodes.remove(fold(path)) ?: throw IOException("Dosya yok: $path")
        n.path = newPath
        nodes[fold(newPath)] = n
    }

    internal fun remove(path: String) = synchronized(lock) { nodes.remove(fold(path)); Unit }
}

private abstract class InMemoryReadable<H : NodeHandle>(
    protected val vol: InMemoryVolume,
    private val rootPath: String,
    protected val role: com.walkmansync.core.model.VolumeRole,
    private val wrap: (String) -> H,
) : ReadableTree<H> {
    override val volume: VolumeId get() = vol.id
    override val root: TreeNode<H> get() = vol.nodeOf(rootPath, wrap) ?: throw IOException("Kök yok: $rootPath")

    override suspend fun list(dir: H): List<TreeNode<H>> {
        vol.checkMounted(role); return vol.children(vol.pathOf(dir.id), wrap)
    }

    override suspend fun resolve(relativePath: String): TreeNode<H>? {
        vol.checkMounted(role); return vol.nodeOf(relativePath, wrap)
    }

    override suspend fun openInput(file: H): InputStream {
        vol.checkMounted(role)
        val p = vol.pathOf(file.id)
        return ByteArrayInputStream(vol.read(p) ?: throw SyncException(SyncError.SourceGone(p)))
    }

    override suspend fun openRandomAccess(file: H): RandomAccessSource {
        vol.checkMounted(role)
        val p = vol.pathOf(file.id)
        return ByteArrayRandomAccessSource(vol.read(p) ?: throw SyncException(SyncError.SourceGone(p)))
    }

    override suspend fun isAvailable(): Boolean = vol.mounted
}

/** Kaynak ağaç sahtesi. [rootPath] kullanıcının seçtiği klasör (volume-göreli). */
class InMemorySourceTree(vol: InMemoryVolume, rootPath: String = "") : SourceTree,
    ReadableTree<SourceHandle> by object : InMemoryReadable<SourceHandle>(
        vol, rootPath, com.walkmansync.core.model.VolumeRole.SOURCE, ::SourceHandle,
    ) {}

/** Hedef ağaç sahtesi; [GuardedTargetTree] kurallarına tabidir. [rootPath]: "" ya da "MUSIC" (yoksa oluşturulur). */
class InMemoryTargetTree(
    private val vol: InMemoryVolume,
    var fileSystem: TargetFileSystem = TargetFileSystem.EXFAT,
    rootPath: String = "",
) : GuardedTargetTree() {
    init {
        require(com.walkmansync.core.model.TargetLayout.isValidTargetRoot(rootPath))
        vol.mkdirs(rootPath)
    }
    private val reader = object : InMemoryReadable<TargetHandle>(
        vol, rootPath, com.walkmansync.core.model.VolumeRole.TARGET, ::TargetHandle,
    ) {}
    private val role = com.walkmansync.core.model.VolumeRole.TARGET

    override val volume: VolumeId get() = vol.id
    override val root: TreeNode<TargetHandle> get() = reader.root
    override suspend fun list(dir: TargetHandle) = reader.list(dir)
    override suspend fun resolve(relativePath: String) = reader.resolve(relativePath)
    override suspend fun openInput(file: TargetHandle) = reader.openInput(file)
    override suspend fun openRandomAccess(file: TargetHandle) = reader.openRandomAccess(file)
    override suspend fun isAvailable(): Boolean = vol.mounted
    override suspend fun freeBytes(): Long? { vol.checkMounted(role); return vol.freeBytes }
    override suspend fun fileSystem(): TargetFileSystem { vol.checkMounted(role); return fileSystem }

    override suspend fun doEnsureDirectory(relativePath: String): TreeNode<TargetHandle> {
        vol.checkMounted(role)
        vol.mkdirs(relativePath)
        return vol.nodeOf(relativePath, ::TargetHandle)!!
    }

    override suspend fun doCreateFile(parent: TargetHandle, name: String): TreeNode<TargetHandle> {
        vol.checkMounted(role)
        val dir = vol.pathOf(parent.id)
        val path = if (dir.isEmpty()) name else "$dir/$name"
        vol.createEmpty(path)
        return vol.nodeOf(path, ::TargetHandle)!!
    }

    override suspend fun doOpenOutput(file: TreeNode<TargetHandle>): TargetOutput {
        vol.checkMounted(role)
        val path = vol.pathOf(file.handle.id)
        return object : TargetOutput {
            private val buf = java.io.ByteArrayOutputStream()
            private var closed = false
            override val bytesWritten: Long get() = buf.size().toLong()
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (closed) throw IOException("Kapalı")
                if (!vol.mounted) throw IOException("EIO: birim yok")
                buf.write(buffer, offset, length)
                vol.onWrite?.invoke(path, buf.size().toLong())
            }
            override fun sync() {
                if (!vol.mounted) throw IOException("EIO: birim yok")
                vol.write(path, buf.toByteArray())
            }
            override fun close() { closed = true }
        }
    }

    override suspend fun doRename(file: TreeNode<TargetHandle>, newName: String): TreeNode<TargetHandle> {
        vol.checkMounted(role)
        val path = vol.pathOf(file.handle.id)
        val parent = path.substringBeforeLast('/', "")
        val newPath = if (parent.isEmpty()) newName else "$parent/$newName"
        vol.rename(path, newPath)
        return vol.nodeOf(newPath, ::TargetHandle)!!
    }

    override suspend fun doDelete(file: TreeNode<TargetHandle>) {
        vol.checkMounted(role)
        vol.remove(vol.pathOf(file.handle.id))
    }
}
