package com.walkmansync.core.engine

import com.walkmansync.core.fs.ReadableTree
import com.walkmansync.core.fs.TargetGuardViolation
import com.walkmansync.core.fs.TargetTree
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.VolumeRole
import java.io.IOException
import java.util.Locale

/** Ağacın rolü: hedef ağaç TargetTree'dir, diğeri kaynaktır. */
internal fun roleOf(tree: ReadableTree<*>): VolumeRole =
    if (tree is TargetTree) VolumeRole.TARGET else VolumeRole.SOURCE

/** İstisna zincirinde ENOSPC / "No space left" izi var mı (ARCHITECTURE §6). */
internal fun isNoSpace(error: Throwable): Boolean {
    var cur: Throwable? = error
    var depth = 0
    while (cur != null && depth < 8) {
        val m = cur.message?.lowercase(Locale.ROOT)
        if (m != null && ("enospc" in m || "no space" in m)) return true
        cur = cur.cause
        depth++
    }
    return false
}

/**
 * İstisna zincirinde EFBIG / "File too large" izi var mı: dosya sistemi tek dosya sınırına (FAT32: 4 GiB - 1)
 * takıldı. Dosya sistemi UNKNOWN iken kullanıcının zorladığı büyük dosyalar gerçekte FAT32 olan kartta
 * bu hatayla düşer → dosyaya özgü [SyncError.FileTooLargeForFat32].
 */
internal fun isFileTooLarge(error: Throwable): Boolean {
    var cur: Throwable? = error
    var depth = 0
    while (cur != null && depth < 8) {
        val m = cur.message?.lowercase(Locale.ROOT)
        if (m != null && ("efbig" in m || "file too large" in m)) return true
        cur = cur.cause
        depth++
    }
    return false
}

/**
 * Tek bir G/Ç hatasını sınıflandırır: önce yerel ipuçları (ENOSPC), sonra birim kontrolü; kart gitmişse
 * ölümcül [SyncError.VolumeMissing], değilse dosyaya özgü [SyncError.IoFailure].
 * Çağıran `CancellationException`'ı buraya GÖNDERMEZ.
 */
internal suspend fun classifyIoFailure(
    path: String?,
    cause: Throwable,
    source: ReadableTree<*>,
    target: ReadableTree<*>,
    spaceRequired: Long,
    spaceAvailable: suspend () -> Long?,
): SyncError {
    if (isNoSpace(cause)) {
        return SyncError.InsufficientSpace(spaceRequired, runCatchingNonCancel(spaceAvailable))
    }
    if (!target.isAvailable()) return SyncError.VolumeMissing(VolumeRole.TARGET, target.volume)
    if (!source.isAvailable()) return SyncError.VolumeMissing(VolumeRole.SOURCE, source.volume)
    return SyncError.IoFailure(path, cause.message ?: cause.javaClass.simpleName, fatal = false)
}

/** Boş alan sorgusu başarısızsa null; iptal yayılır. */
private suspend fun runCatchingNonCancel(block: suspend () -> Long?): Long? =
    try {
        block()
    } catch (e: SyncException) {
        null
    } catch (e: IOException) {
        null
    }

/** Beklenmeyen (programlama hatası) istisnayı [SyncError.Unexpected]'a çevirir. */
internal fun unexpected(e: Throwable): SyncError =
    SyncError.Unexpected(if (e is TargetGuardViolation) "Koruma ihlali: ${e.message}" else e.toString())

/** Kontrol akışı: aktarımın tamamını durduran ölümcül hata. */
internal class FatalTransferException(val error: SyncError) : Exception(error.message)
