package com.walkmansync.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.walkmansync.core.fs.RandomAccessSource
import com.walkmansync.core.fs.TreeNode
import com.walkmansync.core.model.DocumentIds
import com.walkmansync.core.model.NodeHandle
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Bir SAF ağacının (kaynak ya da hedef) çalışma bağlamı. Ağaç nesneleri kısa ömürlüdür; birim/izin
 * doğrulaması her açılışta yeniden yapılır ([SafVolumeRegistry.openSource]/[SafVolumeRegistry.openTarget]).
 *
 * @property volumeMounted birim şu an takılı ve UUID'si tekil mi (StorageManager).
 * @property hasPermission kalıcı URI izni hâlâ var mı (rol bayraklarıyla).
 */
internal class SafTreeContext(
    val resolver: ContentResolver,
    val role: VolumeRole,
    val volume: VolumeId,
    val treeUri: Uri,
    val rootDocId: String,
    val dispatchers: AppDispatchers,
    val logger: Logger,
    val volumeMounted: () -> Boolean,
    val hasPermission: () -> Boolean,
) {
    fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /** Docid öneki bu ağacın birimine ait değilse ölümcül [SyncError.VolumeMismatch]. */
    fun requireOwn(docId: String) {
        val parsed = DocumentIds.parse(docId)
        if (parsed == null || parsed.volume != volume) {
            throw SyncException(SyncError.VolumeMismatch(role, volume, docId.substringBefore(':', "?")))
        }
    }

    /**
     * Blok içindeki Android istisnalarını alan hatalarına çevirir. [SyncException] ve iptal olduğu gibi geçer.
     * Birim artık takılı değilse ölümcül [SyncError.VolumeMissing].
     */
    inline fun <T> guarded(path: String?, block: () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            throw e
        } catch (e: SecurityException) {
            throw SyncException(SyncError.PermissionLost(role), e)
        } catch (e: Exception) {
            throw SyncException(mapFailure(path, e), e)
        }
    }

    fun mapFailure(path: String?, e: Exception): SyncError {
        if (!volumeMounted()) return SyncError.VolumeMissing(role, volume)
        val msg = e.message
        if (msg != null && (msg.contains("ENOSPC") || msg.contains("No space", ignoreCase = true))) {
            return SyncError.InsufficientSpace(0L, null)
        }
        if (e is FileNotFoundException && role == VolumeRole.SOURCE) return SyncError.SourceGone(path ?: "?")
        return SyncError.IoFailure(path, msg ?: e.javaClass.simpleName, fatal = false)
    }
}

/**
 * [block]'u IO dispatcher'da çalıştırıp açtığı kaynağı döndürür. `withContext` iptal edilmiş bir coroutine'e
 * dönerken sonucu ATAR (CancellationException fırlatır); açılmış fd sahipsiz kalıp sızardı. Bu durumda kaynak
 * burada kapatılır ve iptal yayılır.
 */
internal suspend fun <T : java.io.Closeable> openOnIo(ctx: SafTreeContext, block: () -> T): T {
    var opened: T? = null
    try {
        return withContext(ctx.dispatchers.io) { block().also { opened = it } }
    } catch (e: CancellationException) {
        try {
            opened?.close()
        } catch (ignored: IOException) {
            // kapanış hatası iptalin önüne geçmez
        }
        throw e
    }
}

internal fun foldName(name: String): String =
    java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).lowercase(Locale.ROOT)

/** Kaynak ve hedef ağaçların ortak okuma uygulaması (kompozisyonla kullanılır). */
internal class SafReader<H : NodeHandle>(
    private val ctx: SafTreeContext,
    private val wrap: (String) -> H,
) {
    val rootRelativePath: String =
        DocumentIds.parse(ctx.rootDocId)?.relativePath
            ?: throw SyncException(SyncError.VolumeMismatch(ctx.role, ctx.volume, ctx.rootDocId))

    val root: TreeNode<H> = TreeNode(
        handle = wrap(ctx.rootDocId),
        relativePath = rootRelativePath,
        name = rootRelativePath.substringAfterLast('/'),
        isDirectory = true,
        size = 0L,
        lastModified = 0L,
    )

    fun nodeOf(doc: SafDoc): TreeNode<H> {
        val parsed = DocumentIds.parse(doc.docId)
        if (parsed == null || parsed.volume != ctx.volume) {
            throw SyncException(SyncError.VolumeMismatch(ctx.role, ctx.volume, doc.docId.substringBefore(':', "?")))
        }
        return TreeNode(wrap(doc.docId), parsed.relativePath, doc.name, doc.isDirectory, doc.size, doc.lastModified)
    }

    fun relativePathOf(docId: String): String {
        ctx.requireOwn(docId)
        return DocumentIds.parse(docId)!!.relativePath
    }

    /**
     * Ağaç içindeki volume-göreli yol için belge kimliği. Kök öneki ve UUID metni ağaç kökü docId'sinden
     * AYNEN alınır: sağlayıcı ağaç denetimini (enforceTree) büyük/küçük harf duyarlı yol karşılaştırmasıyla
     * yapar; "MUSIC" istenip kök gerçekte "Music" ise kurulan kimlik ağaç dışı sayılır ve SecurityException
     * (→ yanlış PermissionLost) üretirdi. Çağıran [isInsideRoot] koşulunu önceden sağlamalıdır.
     */
    fun docIdFor(relativePath: String): String {
        val rel = relativePath.trim('/')
        val r = rootRelativePath
        val fixed = if (r.isEmpty() || rel.length < r.length) rel else r + rel.substring(r.length)
        return ctx.rootDocId.substringBefore(':') + ":" + fixed
    }

    /** Bloklayıcı; IO dispatcher'da çağrılmalı. */
    fun listBlocking(dirDocId: String): List<TreeNode<H>> {
        ctx.requireOwn(dirDocId)
        return ctx.guarded(dirDocId) {
            val docs = SafCursor.queryChildren(ctx.resolver, ctx.treeUri, dirDocId)
                ?: throw IOException(if (ctx.volumeMounted()) "Sağlayıcı dizin döndürmedi: $dirDocId" else "Birim yok")
            docs.map { nodeOf(it) }
        }
    }

    suspend fun list(dirDocId: String): List<TreeNode<H>> = withContext(ctx.dispatchers.io) { listBlocking(dirDocId) }

    fun resolveBlocking(relativePath: String): TreeNode<H>? {
        val rel = relativePath.trim('/')
        if (!isInsideRoot(rel)) return null
        val docId = docIdFor(rel)
        return ctx.guarded(rel) { queryExisting(docId)?.let { nodeOf(it) } }
    }

    /**
     * Tek belgeyi sorgular; YOKSA null. ExternalStorageProvider ağaç URI'sinde var olmayan belge için cursor
     * döndürmez, `enforceTree` → `isChildDocument` içinde "Failed to determine if … is child of …:
     * FileNotFoundException: Missing file" ile [IllegalArgumentException] fırlatır (DocumentFile.exists() da bunu
     * yutar). Birim takılıysa bu "yok" demektir; değilse ölümcül [SyncError.VolumeMissing]. Bloklayıcı.
     */
    fun queryExisting(docId: String): SafDoc? {
        val doc = try {
            SafCursor.queryDocument(ctx.resolver, ctx.treeUri, docId)
        } catch (e: IllegalArgumentException) {
            if (!ctx.volumeMounted()) throw SyncException(SyncError.VolumeMissing(ctx.role, ctx.volume), e)
            return null
        }
        if (doc == null && !ctx.volumeMounted()) throw SyncException(SyncError.VolumeMissing(ctx.role, ctx.volume))
        return doc
    }

    suspend fun resolve(relativePath: String): TreeNode<H>? =
        withContext(ctx.dispatchers.io) { resolveBlocking(relativePath) }

    /** Kaynak okuma: yalnızca "r" kipi. Dönen akışı çağıran kapatır; fd akışla birlikte kapanır. */
    suspend fun openInput(docId: String): InputStream = openOnIo(ctx) {
        ctx.requireOwn(docId)
        ctx.guarded(docId) {
            val pfd = ctx.resolver.openFileDescriptor(ctx.docUri(docId), "r")
                ?: throw IOException("Dosya açılamadı: $docId")
            ParcelFileDescriptor.AutoCloseInputStream(pfd)
        }
    }

    suspend fun openRandomAccess(docId: String): RandomAccessSource = openOnIo(ctx) {
        ctx.requireOwn(docId)
        ctx.guarded(docId) {
            val pfd = ctx.resolver.openFileDescriptor(ctx.docUri(docId), "r")
                ?: throw IOException("Dosya açılamadı: $docId")
            PfdRandomAccessSource.open(pfd)
        }
    }

    /** Hiç fırlatmaz (iptal hariç): birim takılı, izin var ve ağaç kökü sorgulanabiliyor mu. */
    suspend fun isAvailable(): Boolean = withContext(ctx.dispatchers.io) {
        try {
            ctx.volumeMounted() && ctx.hasPermission() &&
                SafCursor.queryDocument(ctx.resolver, ctx.treeUri, ctx.rootDocId) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    fun isInsideRoot(rel: String): Boolean {
        val r = rootRelativePath
        return r.isEmpty() || rel.equals(r, ignoreCase = true) || rel.startsWith("$r/", ignoreCase = true)
    }
}
