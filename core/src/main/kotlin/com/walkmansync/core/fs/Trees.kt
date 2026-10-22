// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.fs

import com.walkmansync.core.model.FileEntry
import com.walkmansync.core.model.NodeHandle
import com.walkmansync.core.model.SourceHandle
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.model.TargetHandle
import com.walkmansync.core.model.TargetLayout
import com.walkmansync.core.model.VolumeId
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * Ağaçtaki bir düğüm (dosya ya da dizin).
 *
 * @property relativePath Volume KÖKÜNE göre yol ("" = volume kökü). Gerçek (diskteki) harf büyüklüğüyle.
 * @property size Dosyalarda bayt (bilinmiyorsa -1), dizinlerde 0.
 * @property lastModified Epoch ms; bilinmiyorsa 0.
 */
data class TreeNode<out H : NodeHandle>(
    val handle: H,
    val relativePath: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
) {
    fun toFileEntry(): FileEntry<H> {
        check(!isDirectory) { "Dizin FileEntry olamaz: $relativePath" }
        return FileEntry(handle, relativePath, name, size, lastModified)
    }
}

/**
 * Okunabilir ağaç — kaynak ve hedefin ORTAK okuma yüzeyi.
 *
 * Genel kurallar (tüm uygulamalar):
 *  - Tüm `suspend` metotlar ana iş parçacığını bloklamaz; uygulama G/Ç'yi kendi içinde IO dispatcher'a taşır
 *    (`withContext(dispatchers.io)`), çağıran dispatcher seçmek zorunda değildir.
 *  - Hatalar [com.walkmansync.core.model.SyncException] olarak fırlatılır (VolumeMissing, PermissionLost,
 *    SourceGone, IoFailure…). `CancellationException` olduğu gibi yayılır, ASLA sarılmaz.
 *  - Döndürülen akış/kaynak nesnelerinin SAHİBİ ÇAĞIRANDIR; çağıran `use { }` ile kapatır. Ağaç nesnesi
 *    kendisi kaynak tutmaz (Closeable değildir).
 */
interface ReadableTree<H : NodeHandle> {
    /** Bu ağacın bağlı olduğu birim. Tüm düğümler bu birime ait olmak ZORUNDA. */
    val volume: VolumeId

    /** Ağaç kökü (kullanıcının seçtiği klasör). Hedefte volume kökü ("") ya da "MUSIC". */
    val root: TreeNode<H>

    /**
     * [dir]'in DOĞRUDAN alt öğeleri (özyinelemesiz). Sıra tanımsız. Tek sorguda ad/boyut/mtime/tip gelir
     * (SAF: tek `query(buildChildDocumentsUriUsingTree)` cursor'ı; düğüm başına ek sorgu YAPILMAZ).
     */
    suspend fun list(dir: H): List<TreeNode<H>>

    /**
     * Volume köküne göre [relativePath]'teki düğümü döndürür; yoksa null (varlık sorgusu). Büyük/küçük harf
     * duyarlılığı alttaki dosya sistemine bağlıdır (FAT/exFAT: duyarsız) — çakışma kararları bunun yerine
     * [list] sonuçları üzerinden büyük/küçük harf duyarsız verilir.
     */
    suspend fun resolve(relativePath: String): TreeNode<H>?

    /** Sıralı okuma akışı (kopyalama için). Sahibi çağıran. */
    suspend fun openInput(file: H): InputStream

    /** Konuma göre okuma (etiketler ve indeks için). Sahibi çağıran. */
    suspend fun openRandomAccess(file: H): RandomAccessSource

    /**
     * Birim hâlâ takılı, UUID'si [volume] ve ağaç erişilebilir mi. ASLA fırlatmaz (iptal hariç).
     * G/Ç hatası sonrası "dosyaya özgü mü, kart mı gitti?" ayrımı için kullanılır.
     */
    suspend fun isAvailable(): Boolean
}

/**
 * Kaynak kart ağacı. TİP DÜZEYİNDE SALT-OKUNUR: yazma/oluşturma/silme metodu YOKTUR ve eklenmeyecektir.
 * SAF uygulaması kaynak URI'si için yalnızca `FLAG_GRANT_READ_URI_PERMISSION` alır ve dosyaları yalnızca
 * "r" kipinde açar.
 */
interface SourceTree : ReadableTree<SourceHandle>

/**
 * Hedefe yazma akışı. Bloklayıcıdır; IO dispatcher'da kullanılır.
 * Sahiplik: [TargetTree.openOutput] çağıran kapatır. [close] öncesinde [sync] çağrılmalıdır;
 * [close] [sync] çağırmaz.
 */
interface TargetOutput : Closeable {
    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Tamponları boşaltır ve veriyi karta işler (`FileOutputStream.flush()` + `fd.sync()`). */
    fun sync()

    /** Bu akışa şimdiye dek yazılan bayt. */
    val bytesWritten: Long
}

/**
 * Hedef (Walkman) kart ağacı. Kök volume kökü ya da MUSIC'tir ([TargetLayout.isValidTargetRoot]);
 * MUSIC'in var olduğundan emin olmak için `ensureDirectory("MUSIC")` kullanılır.
 *
 * APPEND-ONLY kilidi: Bu arabirimin TÜM uygulamaları [GuardedTargetTree]'yi genişletmek ZORUNDADIR; ad
 * kuralları orada `final` metotlarla zorlanır:
 *  - Yalnızca `.wsync.part` adlı dosya oluşturulur ve yazılır.
 *  - Yeniden adlandırma yalnızca `x.wsync.part → x` ve `index → index.bak`.
 *  - Silme yalnızca `.wsync.part` ve `index.bak`.
 * Böylece mevcut bir kullanıcı dosyasının üzerine yazmak, silmek ya da adını değiştirmek imkânsızdır.
 */
interface TargetTree : ReadableTree<TargetHandle> {

    /**
     * [relativePath] dizinini (eksik ara dizinler dahil) bulur ya da oluşturur. Mevcut dizin parçaları büyük/
     * küçük harf DUYARSIZ eşleştirilir ve mevcut ad korunur: "MUSIC/the beatles" varken "MUSIC/The Beatles/X"
     * istenirse "MUSIC/the beatles/X" döner. Çağıran dönen düğümün [TreeNode.relativePath]'ini GERÇEK yol
     * olarak kullanmalıdır. Aynı adda DOSYA varsa [com.walkmansync.core.model.SyncError.TargetExists].
     * Yalnızca [com.walkmansync.core.model.TargetLayout.isValidTargetDirPath] yollarına izin verilir.
     */
    suspend fun ensureDirectory(relativePath: String): TreeNode<TargetHandle>

    /**
     * [parent] altında [name] adlı BOŞ dosya oluşturur. [name] `.wsync.part` ile bitmeli. Aynı ad
     * (büyük/küçük harf duyarsız) varsa `TargetExists`. Sağlayıcı adı değiştirirse ("x (1)") uygulama
     * oluşturduğunu siler ve `TargetExists` fırlatır.
     */
    suspend fun createFile(parent: TargetHandle, name: String): TreeNode<TargetHandle>

    /** Kendi `.wsync.part` dosyamıza yazma akışı açar (kesme kipi "wt"). Sahibi çağıran. */
    suspend fun openOutput(file: TreeNode<TargetHandle>): TargetOutput

    /**
     * [file]'ın adını [newName] yapar. İzinli geçişler: `x.wsync.part → x`, `INDEX → INDEX.bak`. Hedef ad
     * (büyük/küçük harf duyarsız) varsa `TargetExists` — ASLA üzerine yazılmaz. Dönen düğüm GERÇEK addır:
     * sağlayıcı adı sessizce değiştirdiyse çağıran indekse dönen [TreeNode.relativePath]'i yazar.
     */
    suspend fun rename(file: TreeNode<TargetHandle>, newName: String): TreeNode<TargetHandle>

    /**
     * Ağaç kökünde `INDEX.wsync.part` oluşturur (indeks için tek istisna; yol
     * `TargetLayout.indexPartPathFor(root.relativePath)`). Varsa önce çağıran silmelidir; aksi hâlde `TargetExists`.
     */
    suspend fun createIndexPart(): TreeNode<TargetHandle>

    /** Yalnızca kendi artığımızı (`.wsync.part`, `INDEX.bak`) siler. Dosya zaten yoksa sessizce döner. */
    suspend fun delete(file: TreeNode<TargetHandle>)

    /** Boş alan (bayt); sorgulanamazsa null. */
    suspend fun freeBytes(): Long?

    /** Dosya sistemi türü (4 GiB kontrolü için). */
    suspend fun fileSystem(): TargetFileSystem
}

/** [TargetTree] koruma kuralları ihlali — programlama hatasıdır, kullanıcıya gösterilmez. */
class TargetGuardViolation(message: String) : IllegalArgumentException(message)

/** IOException alt tipi: işlem sırasında birim kayboldu (uygulamalar SyncException'a çevirmeden önce kullanabilir). */
class VolumeGoneException(message: String, cause: Throwable? = null) : IOException(message, cause)
