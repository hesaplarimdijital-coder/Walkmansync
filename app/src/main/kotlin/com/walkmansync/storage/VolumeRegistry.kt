// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.storage

import android.content.Intent
import android.net.Uri
import com.walkmansync.core.model.BindResult
import com.walkmansync.core.model.RemovableVolume
import com.walkmansync.core.model.VolumeBinding
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.workflow.TreeOpener
import com.walkmansync.core.workflow.VolumeSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Kart takma/çıkarma olayı (StorageVolumeCallback + MEDIA_* yayınları + USB_DEVICE_ATTACHED). */
sealed interface VolumeEvent {
    data class Mounted(val volume: RemovableVolume) : VolumeEvent
    data class Unmounted(val id: VolumeId) : VolumeEvent
}

/**
 * Çift SD kart kaydı: StorageManager + SAF. Uygulama (B1): `SafVolumeRegistry` (bağlamaları
 * SharedPreferences "volume_bindings" içinde saklar). Süreç başına tekil (AppContainer).
 *
 * Takılı birim keşfi: `StorageManager.storageVolumes` → `isRemovable && !isPrimary && uuid != null` ve
 * durum MOUNTED/MOUNTED_READ_ONLY. Değişiklikler `registerStorageVolumeCallback` (API 30+) ve
 * [VolumeEventReceiver] → [refresh] ile izlenir.
 *
 * [bind] DOĞRULAMA KURALLARI (sırayla; ilk ihlal → Rejected):
 *  1. `treeDocId = DocumentsContract.getTreeDocumentId(uri)`; authority
 *     "com.android.externalstorage.documents" değilse ya da `DocumentIds.parse(treeDocId)` null → NOT_REMOVABLE.
 *  2. Önek UUID'si takılı birimler arasında yok → VOLUME_NOT_MOUNTED.
 *  3. Takılı birimlerde aynı UUID birden fazla → DUPLICATE_UUID.
 *  4. [expected] verildiyse ve önek ≠ expected → WRONG_VOLUME.
 *  5. Diğer rolün bağlaması aynı UUID → SAME_AS_OTHER_ROLE.
 *  6. TARGET: göreli yol `TargetLayout.isValidTargetRoot` değil ("UUID:" ya da "UUID:MUSIC" değil) →
 *     TARGET_INVALID_FOLDER; birim salt-okunur → TARGET_READ_ONLY.
 *  7. `takePersistableUriPermission` (SOURCE: yalnızca READ; TARGET: READ|WRITE). SecurityException ya da
 *     `persistedUriPermissions` içinde doğrulanamazsa → PERMISSION_NOT_GRANTED.
 *  Başarıda aynı rolün ESKİ bağlamasının izni (başka rol kullanmıyorsa) `releasePersistableUriPermission` ile bırakılır.
 *
 * [TreeOpener] (openSource/openTarget) her çağrıda yukarıdaki 2-3 ve izin kontrolünü TEKRARLAR.
 */
interface VolumeRegistry : TreeOpener {
    /** Şu an takılı çıkarılabilir birimler. */
    val mounted: StateFlow<List<RemovableVolume>>

    /** Rol durumları (Unbound / NotMounted / PermissionMissing / Ready / Conflict). */
    val snapshot: StateFlow<VolumeSnapshot>

    /** Takma/çıkarma olayları (sıcak akış, tekrar oynatma yok). */
    val events: Flow<VolumeEvent>

    fun bindingFor(role: VolumeRole): VolumeBinding?

    /**
     * Birim listesini ve izinleri yeniden okur; ana thread'den çağrılabilir (iş IO'da yapılır).
     * [withRetries]: USB okuyucu yeni takıldığında birimin bağlanması saniyeler sürer → hemen ve ardından
     * birkaç kez gecikmeli yeniden okunur (USB_DEVICE_ATTACHED yalnızca dinamik alıcıya ve etkinliğe gelir).
     */
    fun refresh(withRetries: Boolean = false)

    /**
     * SAF ağaç seçici Intent'i. [volume] takılıysa `StorageVolume.createOpenDocumentTreeIntent()` ile o birimin
     * kökünde açılır; değilse genel `ACTION_OPEN_DOCUMENT_TREE`. Bayraklar: READ (+ TARGET için WRITE) | PERSISTABLE.
     * UI `ActivityResultContracts.StartActivityForResult` ile başlatır, dönen URI'yi [bind]'e verir.
     */
    fun createPickerIntent(role: VolumeRole, volume: VolumeId?): Intent

    suspend fun bind(role: VolumeRole, treeUri: Uri, expected: VolumeId?): BindResult

    /** Bağlamayı siler ve (diğer rol kullanmıyorsa) kalıcı izni bırakır. */
    suspend fun unbind(role: VolumeRole)
}
