package com.walkmansync.storage

import com.walkmansync.core.fs.RandomAccessSource
import com.walkmansync.core.fs.SourceTree
import com.walkmansync.core.fs.TreeNode
import com.walkmansync.core.model.SourceHandle
import com.walkmansync.core.model.VolumeId
import java.io.InputStream

/**
 * Kaynak kart ağacı. Tip düzeyinde salt-okunurdur: yazma yüzeyi yoktur; dosyalar yalnızca "r" kipinde
 * açılır ([SafReader]) ve URI izni yalnızca READ olarak alınmıştır.
 */
internal class SafSourceTree(ctx: SafTreeContext) : SourceTree {
    private val reader = SafReader(ctx, ::SourceHandle)

    override val volume: VolumeId = ctx.volume
    override val root: TreeNode<SourceHandle> get() = reader.root

    override suspend fun list(dir: SourceHandle): List<TreeNode<SourceHandle>> = reader.list(dir.id)
    override suspend fun resolve(relativePath: String): TreeNode<SourceHandle>? = reader.resolve(relativePath)
    override suspend fun openInput(file: SourceHandle): InputStream = reader.openInput(file.id)
    override suspend fun openRandomAccess(file: SourceHandle): RandomAccessSource = reader.openRandomAccess(file.id)
    override suspend fun isAvailable(): Boolean = reader.isAvailable()
}
