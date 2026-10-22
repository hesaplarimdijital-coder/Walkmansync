// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.model

/**
 * Uygulamanın tüm alan hataları. Portlar bunları [SyncException] içinde fırlatır; durum makinesi
 * [SyncState.Failed] ya da [ItemFailure] olarak taşır. Mesajlar UI'da DOĞRUDAN gösterilmez; UI
 * tipe göre yerelleştirilmiş metin üretir ([message] günlük içindir).
 *
 * [isFatal]: true → aktarımın tamamı durur; false → yalnızca o dosya başarısız sayılır, sonrakine geçilir.
 */
sealed class SyncError(val message: String, val isFatal: Boolean) {

    /** Rolün kartı takılı değil / çıkarıldı. */
    class VolumeMissing(val role: VolumeRole, val volume: VolumeId?) :
        SyncError("$role birimi takılı değil (${volume ?: "kayıtsız"})", true)

    /** Beklenen UUID yerine başka bir kart / docId öneki eşleşmiyor / iki kart aynı UUID. */
    class VolumeMismatch(val role: VolumeRole, val expected: VolumeId?, val actual: String?) :
        SyncError("$role birimi eşleşmiyor: beklenen=$expected, bulunan=$actual", true)

    /** Birim takılı ama salt-okunur bağlanmış (hedef rolü); yazma yapılamaz. Kart kilidi/dosya sistemi hatası. */
    class VolumeReadOnly(val role: VolumeRole, val volume: VolumeId) :
        SyncError("$role birimi salt-okunur ($volume)", true)

    /** Kalıcı SAF izni kaybolmuş ya da SecurityException. Kullanıcı ağacı yeniden seçmeli. */
    class PermissionLost(val role: VolumeRole) : SyncError("$role için erişim izni yok", true)

    /** Hedefte yer yok (ön kontrol ya da yazarken ENOSPC). */
    class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long?) :
        SyncError("Yetersiz alan: gereken=$requiredBytes, boş=$availableBytes", true)

    /** FAT32 hedefte > 4 GiB - 1 dosya. Dosya bazlı. */
    class FileTooLargeForFat32(val path: String, val size: Long) :
        SyncError("FAT32 için çok büyük: $path ($size B)", false)

    /** Yazma sonrası boyut doğrulaması tutmadı. Dosya bazlı; .part silinir. */
    class SizeMismatch(val path: String, val expected: Long, val actual: Long) :
        SyncError("Boyut doğrulaması başarısız: $path beklenen=$expected gerçek=$actual", false)

    /** Hedef ad .part yazılırken başkası tarafından oluşturuldu; append-only → üzerine yazılmaz. */
    class TargetExists(val path: String) : SyncError("Hedefte zaten var: $path", false)

    /** Kaynak dosya tarama ile kopya arasında silinmiş/taşınmış. */
    class SourceGone(val path: String) : SyncError("Kaynak dosya bulunamadı: $path", false)

    /**
     * Genel G/Ç hatası. [fatal]=true: kartın düştüğü, ağacın erişilemez olduğu durumlar (birim kontrolü
     * sonrası belirlenir); false: tek dosyaya özgü.
     */
    class IoFailure(val path: String?, val causeMessage: String?, fatal: Boolean) :
        SyncError("G/Ç hatası${path?.let { " ($it)" } ?: ""}: $causeMessage", fatal)

    /**
     * Karttaki indeks bu uygulamanın desteklediğinden YENİ bir şema sürümüyle yazılmış. Eski sürüm
     * üzerine yazmamalı → tarama durur, kullanıcı uygulamayı güncellemeli. Kurtarılamaz.
     */
    class IndexUnsupported(val schemaVersion: Int) : SyncError("Desteklenmeyen indeks şeması: $schemaVersion", true)

    /** İndeks okunamadı/yazılamadı (yedekten kurtarma da başarısız). */
    class IndexCorrupt(val detail: String) : SyncError("İndeks hatası: $detail", false)

    /** Kullanıcı iptali. */
    object Cancelled : SyncError("İptal edildi", true)

    /** Beklenmeyen durum (programlama hatası). Günlüğe yazılır. */
    class Unexpected(val causeMessage: String?) : SyncError("Beklenmeyen hata: $causeMessage", true)

    override fun toString(): String = "${this::class.simpleName}($message)"
}

/**
 * Portlardan fırlatılan alan istisnası. [CancellationException][kotlinx.coroutines.CancellationException]
 * ASLA bu tipe sarılmaz (iptal yayılımı bozulmasın diye) — bkz. ARCHITECTURE "İptal".
 */
class SyncException(val error: SyncError, cause: Throwable? = null) : Exception(error.message, cause)
