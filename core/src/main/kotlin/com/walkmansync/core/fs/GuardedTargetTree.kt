// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.fs

import com.walkmansync.core.model.TargetHandle
import com.walkmansync.core.model.TargetLayout

/**
 * Append-only kurallarını `final` metotlarla zorlayan temel sınıf. TÜM [TargetTree] uygulamaları
 * (SAF uygulaması B1, test sahtesi B2) bunu genişletir ve yalnızca `do*` metotlarını yazar.
 * Kurallar ihlal edilirse [TargetGuardViolation] fırlatılır ve alttaki uygulama HİÇ çağrılmaz.
 */
abstract class GuardedTargetTree : TargetTree {

    final override suspend fun ensureDirectory(relativePath: String): TreeNode<TargetHandle> {
        val path = relativePath.trim('/')
        guard(TargetLayout.isValidTargetDirPath(path)) { "MUSIC dışında/geçersiz dizin: '$relativePath'" }
        return doEnsureDirectory(path)
    }

    final override suspend fun createFile(parent: TargetHandle, name: String): TreeNode<TargetHandle> {
        guard(isSafeName(name) && TargetLayout.isPartFile(name)) { "Yalnızca .wsync.part oluşturulabilir: '$name'" }
        return doCreateFile(parent, name)
    }

    /**
     * İndeks dosyası için tek istisna: ağaç kökünde `INDEX.wsync.part` oluşturma. (IndexStore uygulaması
     * indeksi `.part` olarak yazar, mevcut indeksi `.bak` yapar, `.part`'ı indeks adına çevirir.)
     */
    final override suspend fun createIndexPart(): TreeNode<TargetHandle> =
        doCreateFile(root.handle, TargetLayout.partNameFor(TargetLayout.INDEX_FILE_NAME))

    final override suspend fun openOutput(file: TreeNode<TargetHandle>): TargetOutput {
        guard(!file.isDirectory && TargetLayout.isPartFile(file.name)) { "Yalnızca .wsync.part yazılabilir: '${file.relativePath}'" }
        return doOpenOutput(file)
    }

    final override suspend fun rename(file: TreeNode<TargetHandle>, newName: String): TreeNode<TargetHandle> {
        guard(!file.isDirectory && isSafeName(newName)) { "Geçersiz yeniden adlandırma: '${file.name}' → '$newName'" }
        val parentPath = file.relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        val resultPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"
        val rootPath = root.relativePath
        val allowed =
            // x.wsync.part → x : MUSIC altında geçerli bir hedef yola ya da ağaç kökündeki indekse
            (TargetLayout.finalNameOf(file.name) == newName &&
                (TargetLayout.isValidTargetFilePath(resultPath) || resultPath == TargetLayout.indexPathFor(rootPath))) ||
                // INDEX → INDEX.bak : yalnızca ağaç kökünde
                (file.relativePath == TargetLayout.indexPathFor(rootPath) && newName == TargetLayout.INDEX_BACKUP_NAME)
        guard(allowed) { "İzinsiz yeniden adlandırma: '${file.name}' → '$newName'" }
        return doRename(file, newName)
    }

    final override suspend fun delete(file: TreeNode<TargetHandle>) {
        guard(!file.isDirectory) { "Dizin silinemez: '${file.relativePath}'" }
        val allowed = TargetLayout.isPartFile(file.name) ||
            file.relativePath == TargetLayout.indexBackupPathFor(root.relativePath)
        guard(allowed) { "Yalnızca kendi artıklarımız silinebilir: '${file.relativePath}'" }
        doDelete(file)
    }

    /** Eksik ara dizinleri oluşturur; mevcut parçaları büyük/küçük harf duyarsız eşler (bkz. [TargetTree.ensureDirectory]). */
    protected abstract suspend fun doEnsureDirectory(relativePath: String): TreeNode<TargetHandle>

    /** Boş dosya oluşturur; çakışmada SyncException(TargetExists). */
    protected abstract suspend fun doCreateFile(parent: TargetHandle, name: String): TreeNode<TargetHandle>

    protected abstract suspend fun doOpenOutput(file: TreeNode<TargetHandle>): TargetOutput

    /** Hedef ad varsa SyncException(TargetExists); asla üzerine yazmaz. */
    protected abstract suspend fun doRename(file: TreeNode<TargetHandle>, newName: String): TreeNode<TargetHandle>

    protected abstract suspend fun doDelete(file: TreeNode<TargetHandle>)

    private fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && name.none(Char::isISOControl)

    private inline fun guard(ok: Boolean, message: () -> String) {
        if (!ok) throw TargetGuardViolation(message())
    }
}
