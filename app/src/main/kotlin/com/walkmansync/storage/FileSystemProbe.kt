package com.walkmansync.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.system.Os
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.runtime.Logger
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Hedef kart için dosya sistemi türü ve boş alan sorguları. Bloklayıcıdır (IO dispatcher'da çağrılır).
 *
 *  - Boş alan: önce `StorageVolume.directory` üzerinde `statvfs`; olmazsa ExternalStorageProvider kök
 *    sorgusundaki `COLUMN_AVAILABLE_BYTES`.
 *  - Dosya sistemi: `/proc/mounts` içinde birimin bağlama noktasına ait kesin tür (vfat/exfat). Android 11+
 *    uygulama mount ad alanında yalnızca FUSE görünebilir; kesin tür yoksa [TargetFileSystem.UNKNOWN]
 *    (4 GiB sınırı açısından muhafazakâr değerlendirilir).
 */
internal class FileSystemProbe(
    private val storageManager: StorageManager,
    private val resolver: ContentResolver,
    private val logger: Logger,
    private val mountsFile: File = File("/proc/mounts"),
) {
    fun freeBytes(volume: VolumeId): Long? {
        val dir = findVolume(volume)?.directory
        if (dir != null) {
            try {
                val st = Os.statvfs(dir.path)
                return st.f_bavail * st.f_frsize
            } catch (e: Exception) {
                logger.d(TAG, "statvfs başarısız (${dir.path}): ${e.message}")
            }
        }
        return availableBytesFromRoots(volume)
    }

    fun fileSystem(volume: VolumeId): TargetFileSystem {
        val lines = try {
            mountsFile.readLines()
        } catch (e: IOException) {
            return TargetFileSystem.UNKNOWN
        } catch (e: SecurityException) {
            return TargetFileSystem.UNKNOWN
        }
        return parseMounts(lines, volume)
    }

    private fun findVolume(volume: VolumeId): StorageVolume? =
        storageManager.storageVolumes.firstOrNull {
            !it.isPrimary && it.isRemovable && VolumeId.of(it.uuid) == volume
        }

    private fun availableBytesFromRoots(volume: VolumeId): Long? {
        val rootsUri: Uri = DocumentsContract.buildRootsUri(EXTERNAL_STORAGE_AUTHORITY)
        val projection = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
        return try {
            resolver.query(rootsUri, projection, null, null, null)?.use { c ->
                var result: Long? = null
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (VolumeId.of(id) == volume && !c.isNull(1)) {
                        result = c.getLong(1)
                        break
                    }
                }
                result
            }
        } catch (e: Exception) {
            logger.d(TAG, "Kök sorgusu başarısız: ${e.message}")
            null
        }
    }

    companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val TAG = "FsProbe"

        /** `/proc/mounts` satırlarından birimin kesin dosya sistemi; bulunamazsa UNKNOWN. */
        fun parseMounts(lines: List<String>, volume: VolumeId): TargetFileSystem {
            var result = TargetFileSystem.UNKNOWN
            for (line in lines) {
                val f = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
                if (f.size < 3) continue
                val mountPoint = f[1].trimEnd('/')
                if (!mountPoint.substringAfterLast('/').equals(volume.uuid, ignoreCase = true)) continue
                when (f[2].lowercase(Locale.ROOT)) {
                    "exfat", "texfat" -> return TargetFileSystem.EXFAT
                    "vfat", "msdos", "fat" -> result = TargetFileSystem.FAT32
                    else -> Unit // fuse / sdcardfs / sdfat: kesin değil
                }
            }
            return result
        }
    }
}
