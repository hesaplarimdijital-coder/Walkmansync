// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.scan

import com.walkmansync.core.fs.ReadableTree
import com.walkmansync.core.fs.TreeNode
import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.FileEntry
import com.walkmansync.core.model.NodeHandle
import com.walkmansync.core.model.TargetLayout
import java.util.Locale

/**
 * Özyinelemeli ağaç taraması sonucu.
 *
 * @property files Desteklenen biçimdeki dosyalar ([AudioFormat.fromFileName] != null). Sıra: relativePath'e göre.
 * @property partFiles Bulunan kendi `.wsync.part` artıklarımız (yalnızca hedef taramasında anlamlı).
 * @property directoryPaths Ziyaret edilen dizinlerin relativePath'leri (gerçek harf büyüklüğüyle).
 * @property unreadableDirectories Listelenemeyen (izin/G/Ç) dizinler; tarama bunlar yüzünden DURMAZ.
 */
data class TreeScanResult<H : NodeHandle>(
    val files: List<FileEntry<H>>,
    val partFiles: List<TreeNode<H>>,
    val directoryPaths: List<String>,
    val unreadableDirectories: List<String>,
)

/**
 * Özyinelemeli tarayıcı. Uygulama (B1): `DefaultTreeScanner(dispatchers: AppDispatchers, logger: Logger)`, core.
 *
 * Kurallar:
 *  - BFS, dizin başına tek [ReadableTree.list] çağrısı. Eşzamanlı en fazla 4 dizin listelenir (Semaphore).
 *  - [ScanExclusions.isExcludedDirectory] dizinlerine girilmez.
 *  - Döngü/sembolik bağ koruması: aynı handle ikinci kez ziyaret edilmez.
 *  - [onProgress] en fazla ~10/sn çağrılır (Clock ile kısılır), çağıran thread'i tanımsız → çağıran thread-safe olmalı.
 *  - Tek dizin hatası (IoFailure, dosyaya özgü) → [TreeScanResult.unreadableDirectories]; ölümcül birim
 *    hataları (VolumeMissing, PermissionLost) ve iptal YAYILIR.
 *
 * @param startPath Taramanın başlayacağı volume-göreli dizin; null → ağaç kökü. Yoksa boş sonuç.
 */
interface TreeScanner {
    suspend fun <H : NodeHandle> scan(
        tree: ReadableTree<H>,
        startPath: String?,
        onProgress: (filesFound: Int, directoriesVisited: Int, currentPath: String) -> Unit,
    ): TreeScanResult<H>
}

/** Taramada atlanan dizinler. Saf kural; iki ajan da bunu kullanır. */
object ScanExclusions {
    private val EXCLUDED_ANYWHERE = setOf("lost.dir", "system volume information", "\$recycle.bin")

    /** Volume KÖKÜNDE atlanır (Android uygulama verisi, sistem klasörleri). */
    private val EXCLUDED_AT_VOLUME_ROOT = setOf("android", "dcim", "pictures", "movies")

    /**
     * @param name dizin adı
     * @param parentRelativePath dizinin bulunduğu üst dizinin volume-göreli yolu ("" = volume kökü)
     */
    fun isExcludedDirectory(name: String, parentRelativePath: String): Boolean {
        if (name.startsWith('.')) return true // .Trash-1000, .thumbnails, .android_secure, gizli klasörler
        val n = name.lowercase(Locale.ROOT)
        if (n in EXCLUDED_ANYWHERE) return true
        return parentRelativePath.isEmpty() && n in EXCLUDED_AT_VOLUME_ROOT
    }

    /**
     * Dosyanın tarama sonucuna girip girmeyeceği: desteklenen biçim, gizli olmayan ("._x.flac" macOS
     * artıkları ve '.' ile başlayan adlar atlanır), kendi artığımız olmayan.
     */
    fun isCandidateFile(name: String): Boolean =
        !name.startsWith('.') && !TargetLayout.isOwnArtifact(name) && AudioFormat.fromFileName(name) != null
}
