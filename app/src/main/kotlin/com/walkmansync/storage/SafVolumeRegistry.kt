package com.walkmansync.storage

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.UriPermission
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.walkmansync.core.fs.GuardedTargetTree
import com.walkmansync.core.fs.SourceTree
import com.walkmansync.core.model.BindRejection
import com.walkmansync.core.model.BindResult
import com.walkmansync.core.model.DocumentIds
import com.walkmansync.core.model.RemovableVolume
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.TargetLayout
import com.walkmansync.core.model.VolumeBinding
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumeKind
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.model.VolumeStatus
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.workflow.VolumeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Çift SD kart kaydı: StorageManager + SAF. Bağlamalar [BindingStore]'da (UUID + ağaç URI) tutulur.
 * Kimlik yalnızca `StorageVolume.uuid`'dir; SAF belge kimliği öneki her açılışta yeniden doğrulanır,
 * hiçbir şey önbelleğe alınmaz (kart değişmiş olabilir).
 */
class SafVolumeRegistry(
    context: Context,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    private val logger: Logger,
    private val scope: CoroutineScope,
) : VolumeRegistry {

    private val app: Context = context.applicationContext
    private val resolver = app.contentResolver
    private val storageManager: StorageManager = requireNotNull(app.getSystemService(StorageManager::class.java))
    private val store = BindingStore(app)
    private val probe = FileSystemProbe(storageManager, resolver, logger)
    private val refreshMutex = Mutex()

    private val _mounted = MutableStateFlow<List<RemovableVolume>>(emptyList())
    private val _snapshot = MutableStateFlow(VolumeSnapshot.Unbound)
    private val _events = MutableSharedFlow<VolumeEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val mounted: StateFlow<List<RemovableVolume>> = _mounted.asStateFlow()
    override val snapshot: StateFlow<VolumeSnapshot> = _snapshot.asStateFlow()
    override val events: Flow<VolumeEvent> = _events.asSharedFlow()

    private val volumeCallback = object : StorageManager.StorageVolumeCallback() {
        override fun onStateChanged(volume: StorageVolume) {
            refresh()
        }
    }

    init {
        try {
            storageManager.registerStorageVolumeCallback(ContextCompat.getMainExecutor(app), volumeCallback)
        } catch (e: RuntimeException) {
            logger.w(TAG, "StorageVolumeCallback kaydedilemedi", e)
        }
        registerReceivers()
        refresh()
    }

    // ---- izleme ----------------------------------------------------------------------------------

    private fun registerReceivers() {
        val receiver = VolumeEventReceiver()
        val media = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        val usb = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            ContextCompat.registerReceiver(app, receiver, media, ContextCompat.RECEIVER_EXPORTED)
            ContextCompat.registerReceiver(app, receiver, usb, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: RuntimeException) {
            logger.w(TAG, "Yayın alıcıları kaydedilemedi", e)
        }
    }

    override fun refresh(withRetries: Boolean) {
        scope.launch(dispatchers.io) {
            recompute()
            if (withRetries) {
                // USB okuyucu takılınca birimin bağlanması saniyeler sürebilir: art arda birkaç kez yeniler.
                for (wait in RETRY_DELAYS_MS) {
                    delay(wait)
                    recompute()
                }
            }
        }
    }

    override fun bindingFor(role: VolumeRole): VolumeBinding? = store.get(role)

    // ---- keşif -----------------------------------------------------------------------------------

    private class Scan(
        val volumes: List<RemovableVolume>,
        val byId: Map<VolumeId, RemovableVolume>,
        val duplicates: Set<VolumeId>,
        val native: Map<VolumeId, StorageVolume>,
    )

    private fun scanVolumes(): Scan {
        val byId = LinkedHashMap<VolumeId, RemovableVolume>()
        val native = HashMap<VolumeId, StorageVolume>()
        val duplicates = HashSet<VolumeId>()
        for (sv in storageManager.storageVolumes) {
            if (!sv.isRemovable || sv.isPrimary) continue
            val id = VolumeId.of(sv.uuid) ?: continue
            val state = sv.state
            if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
            if (byId.containsKey(id)) {
                duplicates.add(id)
                continue
            }
            val label = sv.getDescription(app) ?: id.uuid
            byId[id] = RemovableVolume(id, label, kindOf(label), isWritable = state == Environment.MEDIA_MOUNTED)
            native[id] = sv
        }
        return Scan(byId.values.toList(), byId, duplicates, native)
    }

    private fun kindOf(label: String): VolumeKind {
        val l = label.lowercase(Locale.ROOT)
        return when {
            "usb" in l -> VolumeKind.USB
            "sd" in l -> VolumeKind.SD_CARD
            else -> VolumeKind.UNKNOWN
        }
    }

    private fun isMounted(id: VolumeId): Boolean {
        val scan = scanVolumes()
        return id in scan.byId && id !in scan.duplicates
    }

    private fun hasPermission(binding: VolumeBinding, perms: List<UriPermission>): Boolean =
        perms.any {
            it.uri.toString() == binding.treeUri && it.isReadPermission &&
                (binding.role != VolumeRole.TARGET || it.isWritePermission)
        }

    private fun statusFor(role: VolumeRole, scan: Scan, perms: List<UriPermission>): VolumeStatus {
        val binding = store.get(role) ?: return VolumeStatus.Unbound
        if (binding.volume in scan.duplicates) return VolumeStatus.Conflict(binding)
        val volume = scan.byId[binding.volume] ?: return VolumeStatus.NotMounted(binding)
        if (!hasPermission(binding, perms)) return VolumeStatus.PermissionMissing(binding)
        // Salt-okunur bağlanmış hedefe yazılamaz: kullanılabilir sayılmaz, kullanıcıya ayrı durum gösterilir.
        if (role == VolumeRole.TARGET && !volume.isWritable) return VolumeStatus.ReadOnly(binding, volume)
        return VolumeStatus.Ready(binding, volume)
    }

    private suspend fun recompute() {
        refreshMutex.withLock {
            try {
                withContext(dispatchers.io) { recomputeBlocking() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Birim listesi yenilenemedi", e)
            }
        }
    }

    private fun recomputeBlocking() {
        val scan = scanVolumes()
        val perms = resolver.persistedUriPermissions
        val snapshot = VolumeSnapshot(
            source = statusFor(VolumeRole.SOURCE, scan, perms),
            target = statusFor(VolumeRole.TARGET, scan, perms),
        )
        val previousIds = _mounted.value.map { it.id }.toSet()
        _mounted.value = scan.volumes
        _snapshot.value = snapshot
        val currentIds = scan.volumes.map { it.id }.toSet()
        for (v in scan.volumes) if (v.id !in previousIds) _events.tryEmit(VolumeEvent.Mounted(v))
        for (id in previousIds) if (id !in currentIds) _events.tryEmit(VolumeEvent.Unmounted(id))
    }

    // ---- seçici ve bağlama -----------------------------------------------------------------------

    override fun createPickerIntent(role: VolumeRole, volume: VolumeId?): Intent {
        val native = volume?.let { id ->
            try {
                scanVolumes().native[id]
            } catch (e: RuntimeException) {
                null
            }
        }
        val intent = native?.createOpenDocumentTreeIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if (role == VolumeRole.TARGET) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return intent.addFlags(flags)
    }

    override suspend fun bind(role: VolumeRole, treeUri: Uri, expected: VolumeId?): BindResult =
        withContext(dispatchers.io) {
            refreshMutex.withLock { bindLocked(role, treeUri, expected) }
        }

    private fun reject(reason: BindRejection, detail: String? = null) = BindResult.Rejected(reason, detail)

    private fun bindLocked(role: VolumeRole, treeUri: Uri, expected: VolumeId?): BindResult {
        // 1. Yalnızca ExternalStorageProvider; docId önekı geçerli bir UUID olmalı (primary değil).
        if (treeUri.authority != FileSystemProbe.EXTERNAL_STORAGE_AUTHORITY) {
            return reject(BindRejection.NOT_REMOVABLE, "authority=${treeUri.authority}")
        }
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            return reject(BindRejection.NOT_REMOVABLE, "ağaç URI'si değil")
        }
        val parsed = DocumentIds.parse(docId) ?: return reject(BindRejection.NOT_REMOVABLE, docId)

        val scan = scanVolumes()
        // 2-3. Takılı ve tekil olmalı.
        val volume = scan.byId[parsed.volume] ?: return reject(BindRejection.VOLUME_NOT_MOUNTED, parsed.volume.uuid)
        if (parsed.volume in scan.duplicates) return reject(BindRejection.DUPLICATE_UUID, parsed.volume.uuid)
        // 4. İstenen birim.
        if (expected != null && expected != parsed.volume) {
            return reject(BindRejection.WRONG_VOLUME, "beklenen=$expected seçilen=${parsed.volume}")
        }
        // 5. Kaynak ≠ hedef.
        val other = store.get(if (role == VolumeRole.SOURCE) VolumeRole.TARGET else VolumeRole.SOURCE)
        if (other != null && other.volume == parsed.volume) return reject(BindRejection.SAME_AS_OTHER_ROLE, parsed.volume.uuid)
        // 6. Hedef: volume kökü ya da MUSIC; yazılabilir olmalı.
        if (role == VolumeRole.TARGET) {
            if (!TargetLayout.isValidTargetRoot(parsed.relativePath)) {
                return reject(BindRejection.TARGET_INVALID_FOLDER, parsed.relativePath)
            }
            if (!volume.isWritable) return reject(BindRejection.TARGET_READ_ONLY, parsed.volume.uuid)
        }
        // 7. Kalıcı izin: kaynak yalnızca READ, hedef READ|WRITE.
        val flags = flagsFor(role)
        try {
            resolver.takePersistableUriPermission(treeUri, flags)
        } catch (e: SecurityException) {
            logger.w(TAG, "takePersistableUriPermission reddedildi", e)
            return reject(BindRejection.PERMISSION_NOT_GRANTED, e.message)
        }
        val granted = resolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && (role != VolumeRole.TARGET || it.isWritePermission)
        }
        if (!granted) return reject(BindRejection.PERMISSION_NOT_GRANTED, "kalıcı izin doğrulanamadı")

        val old = store.get(role)
        val binding = VolumeBinding(role, parsed.volume, treeUri.toString(), docId, volume.label, clock.nowMillis())
        store.put(binding)
        if (old != null && old.treeUri != binding.treeUri) releaseIfUnused(old)
        releaseOrphanedPermissions()
        try {
            recomputeBlocking()
        } catch (e: RuntimeException) {
            logger.w(TAG, "Bağlama sonrası yenileme başarısız", e)
        }
        return BindResult.Bound(binding)
    }

    override suspend fun unbind(role: VolumeRole) {
        withContext(dispatchers.io) {
            refreshMutex.withLock {
                val old = store.get(role)
                store.clear(role)
                if (old != null) releaseIfUnused(old)
                try {
                    recomputeBlocking()
                } catch (e: RuntimeException) {
                    logger.w(TAG, "Bağ kaldırma sonrası yenileme başarısız", e)
                }
            }
        }
    }

    private fun flagsFor(role: VolumeRole): Int =
        if (role == VolumeRole.TARGET) Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        else Intent.FLAG_GRANT_READ_URI_PERMISSION

    /** [old]'un URI'sini başka rol kullanmıyorsa kalıcı izni bırakır. */
    private fun releaseIfUnused(old: VolumeBinding) {
        val others = VolumeRole.values().filter { it != old.role }.mapNotNull { store.get(it) }
        if (others.any { it.treeUri == old.treeUri }) return
        releaseUri(Uri.parse(old.treeUri))
    }

    /** Hiçbir bağlamanın kullanmadığı eski kalıcı izinleri temizler (izin sayısı sınırı: 128/512). */
    private fun releaseOrphanedPermissions() {
        val used = VolumeRole.values().mapNotNull { store.get(it)?.treeUri }.toSet()
        for (p in resolver.persistedUriPermissions) {
            if (p.uri.toString() !in used) releaseUri(p.uri)
        }
    }

    private fun releaseUri(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            logger.d(TAG, "İzin bırakılamadı (zaten yok): $uri")
        }
    }

    // ---- TreeOpener ------------------------------------------------------------------------------

    override suspend fun openSource(binding: VolumeBinding): SourceTree =
        withContext(dispatchers.io) { SafSourceTree(contextFor(VolumeRole.SOURCE, binding)) }

    override suspend fun openTarget(binding: VolumeBinding): GuardedTargetTree =
        withContext(dispatchers.io) { SafTargetTree(contextFor(VolumeRole.TARGET, binding), probe) }

    /**
     * Her açılışta: kayıt/UUID tutarlılığı, birimin takılı ve tekil olması, docId öneki ve kalıcı izin
     * yeniden doğrulanır. İhlalde SyncException (VolumeMissing / VolumeMismatch / PermissionLost).
     */
    private fun contextFor(role: VolumeRole, binding: VolumeBinding): SafTreeContext {
        fun mismatch(actual: String?) = SyncException(SyncError.VolumeMismatch(role, binding.volume, actual))
        if (binding.role != role) throw mismatch("rol=${binding.role}")
        val stored = store.get(role)
        if (stored == null || stored.volume != binding.volume || stored.treeUri != binding.treeUri) {
            throw mismatch(stored?.volume?.uuid)
        }
        val prefix = DocumentIds.parse(binding.treeDocId)
        if (prefix == null || prefix.volume != binding.volume) throw mismatch(binding.treeDocId.substringBefore(':'))

        val treeUri = Uri.parse(binding.treeUri)
        val actualDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            throw mismatch("geçersiz URI")
        }
        if (actualDocId != binding.treeDocId) throw mismatch(actualDocId.substringBefore(':'))

        val scan = scanVolumes()
        if (binding.volume in scan.duplicates) throw mismatch("DUPLICATE_UUID")
        val volume = scan.byId[binding.volume] ?: throw SyncException(SyncError.VolumeMissing(role, binding.volume))
        if (role == VolumeRole.TARGET) {
            if (!volume.isWritable) throw SyncException(SyncError.VolumeReadOnly(role, binding.volume))
            if (!TargetLayout.isValidTargetRoot(prefix.relativePath)) throw mismatch("hedef kök: ${prefix.relativePath}")
        }
        if (!hasPermission(binding, resolver.persistedUriPermissions)) throw SyncException(SyncError.PermissionLost(role))

        return SafTreeContext(
            resolver = resolver,
            role = role,
            volume = binding.volume,
            treeUri = treeUri,
            rootDocId = binding.treeDocId,
            dispatchers = dispatchers,
            logger = logger,
            volumeMounted = { isMounted(binding.volume) },
            hasPermission = { hasPermission(binding, resolver.persistedUriPermissions) },
        )
    }

    private companion object {
        const val TAG = "VolumeRegistry"
        val RETRY_DELAYS_MS = longArrayOf(1_500L, 3_000L, 5_000L)
    }
}
