// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.model

/**
 * Çıkarılabilir bir depolama biriminin kimliği: `StorageVolume.getUuid()` değeri
 * (FAT/exFAT için "ABCD-1234" biçimi). SAF belge kimliklerinde (`docId`) iki nokta
 * öncesindeki önekle birebir aynıdır: "ABCD-1234:MUSIC/x.flac".
 *
 * Değer her zaman BÜYÜK harfe normalize edilir; karşılaştırmalar normalize değerle yapılır.
 * "primary", "home" gibi dahili depolama kimlikleri geçerli bir VolumeId DEĞİLDİR
 * (bkz. [DocumentIds.parse]); uygulama yalnızca çıkarılabilir kartlarla çalışır.
 */
@JvmInline
value class VolumeId private constructor(val uuid: String) {
    override fun toString(): String = uuid

    companion object {
        private val RESERVED = setOf("PRIMARY", "HOME", "RAW", "DOWNLOADS")

        /** Geçersiz/ayrılmış değerlerde `null` döner. */
        fun of(raw: String?): VolumeId? {
            val v = raw?.trim()?.uppercase(java.util.Locale.ROOT) ?: return null
            if (v.isEmpty() || v in RESERVED) return null
            if (v.any { it == ':' || it == '/' || it == '\\' || it.isWhitespace() || it.isISOControl() }) return null
            return VolumeId(v)
        }

        /** [of] ile aynı, geçersizse [IllegalArgumentException]. */
        fun require(raw: String): VolumeId =
            requireNotNull(of(raw)) { "Geçersiz volume UUID: '$raw'" }
    }
}

/** Bir kartın senkronizasyondaki rolü. Kaynak salt-okunur, hedef okuma/yazma. */
enum class VolumeRole { SOURCE, TARGET }

/**
 * Doğrulanmış kaynak/hedef eşleşmesi. Yalnızca VolumeRegistry doğrulamasından geçmiş
 * çiftler için oluşturulur (bkz. docs/ARCHITECTURE.md "UUID güvenlik kuralları").
 *
 * @property sourceTreeDocId Kullanıcının SAF ile seçtiği kaynak ağaç kökünün belge kimliği
 *   (ör. "1A2B-3C4D:" ya da "1A2B-3C4D:Music"). Öneki [source] ile eşleşmek ZORUNDA.
 * @property targetTreeDocId Hedef ağaç kökü: volume kökü ("UUID:") YA DA "UUID:MUSIC" (büyük/küçük harf
 *   duyarsız). Android 11+ seçicisi bazı cihazlarda SD kart kökünün seçilmesine izin vermediği için MUSIC
 *   de kabul edilir. İndeks dosyası ağaç kökünde durur (bkz. [TargetLayout.indexPathFor]).
 */
data class VolumePair(
    val source: VolumeId,
    val target: VolumeId,
    val sourceTreeDocId: String,
    val targetTreeDocId: String,
) {
    init {
        require(source != target) { "Kaynak ve hedef aynı volume olamaz ($source)" }
        val s = DocumentIds.parse(sourceTreeDocId)
        require(s != null && s.volume == source) { "Kaynak ağaç docId'si ($sourceTreeDocId) $source ile eşleşmiyor" }
        val t = DocumentIds.parse(targetTreeDocId)
        require(t != null && t.volume == target) { "Hedef ağaç docId'si ($targetTreeDocId) $target ile eşleşmiyor" }
        require(TargetLayout.isValidTargetRoot(t.relativePath)) { "Hedef ağaç volume kökü ya da MUSIC olmalı: $targetTreeDocId" }
    }

    fun idFor(role: VolumeRole): VolumeId = if (role == VolumeRole.SOURCE) source else target
}

/**
 * Hedef kartın dosya sistemi. Tespit edilemezse [UNKNOWN]; UNKNOWN, 4 GiB sınırı açısından
 * FAT32 gibi (muhafazakâr) değerlendirilir.
 */
enum class TargetFileSystem {
    EXFAT, FAT32, UNKNOWN;

    /** Bu dosya sisteminde tek dosya için izin verilen azami boyut; sınırsızsa `null`. */
    val maxFileSize: Long?
        get() = if (this == EXFAT) null else FAT32_MAX_FILE_SIZE

    companion object {
        /** FAT32'de tek dosya en fazla 4 GiB - 1 bayt olabilir. */
        const val FAT32_MAX_FILE_SIZE: Long = 0xFFFF_FFFFL
    }
}

/** Takılı çıkarılabilir birimin türü (StorageVolume açıklamasından tahmin; güvenlik için KULLANILMAZ). */
enum class VolumeKind { SD_CARD, USB, UNKNOWN }

/**
 * Şu an takılı (MOUNTED) çıkarılabilir birim. Yalnızca `isRemovable && !isPrimary && uuid != null`
 * olan StorageVolume'lardan üretilir.
 *
 * @property label Kullanıcıya gösterilecek ad (StorageVolume.getDescription).
 * @property isWritable Birim MOUNTED (MOUNTED_READ_ONLY değil) mi.
 */
data class RemovableVolume(
    val id: VolumeId,
    val label: String,
    val kind: VolumeKind,
    val isWritable: Boolean,
)

/**
 * Kalıcı olarak kaydedilmiş rol bağlaması (uygulama verisinde saklanır).
 *
 * @property treeUri SAF ağaç URI'sinin `toString()` hâli (core Android'den bağımsız kalsın diye String).
 * @property treeDocId `DocumentsContract.getTreeDocumentId(treeUri)`; öneki [volume] olmak ZORUNDA.
 */
data class VolumeBinding(
    val role: VolumeRole,
    val volume: VolumeId,
    val treeUri: String,
    val treeDocId: String,
    val label: String,
    val boundAtMillis: Long,
) {
    init {
        require(DocumentIds.belongsTo(treeDocId, volume)) { "treeDocId ($treeDocId) $volume'a ait değil" }
    }
}

/** Kart bağlama girişiminin reddedilme nedeni. */
enum class BindRejection {
    /** Seçilen ağaç dahili depolamada (primary) ya da docId öneki geçerli UUID değil. */
    NOT_REMOVABLE,
    /** UUID şu an takılı çıkarılabilir birimler arasında yok. */
    VOLUME_NOT_MOUNTED,
    /** Kullanıcının seçtiği birim, istenen birimden (ör. Intent'te hedeflenen) farklı. */
    WRONG_VOLUME,
    /** Aynı UUID diğer role zaten bağlı (kaynak = hedef olamaz). */
    SAME_AS_OTHER_ROLE,
    /** Takılı birimlerden ikisi aynı UUID'yi bildiriyor (klonlanmış kart) — güvenle ayırt edilemez. */
    DUPLICATE_UUID,
    /** Hedef için volume kökü ("UUID:") ya da "UUID:MUSIC" seçilmeli; başka klasör seçildi. */
    TARGET_INVALID_FOLDER,
    /** Hedef birim salt-okunur bağlanmış. */
    TARGET_READ_ONLY,
    /** takePersistableUriPermission başarısız ya da gerekli izin bayrakları verilmedi. */
    PERMISSION_NOT_GRANTED,
}

/** Kart bağlama sonucu. */
sealed interface BindResult {
    data class Bound(val binding: VolumeBinding) : BindResult
    data class Rejected(val reason: BindRejection, val detail: String? = null) : BindResult
}

/**
 * SAF belge kimliği ("UUID:göreli/yol") ayrıştırıcısı. Kimliğin volume önekine dayanan TÜM
 * güvenlik kontrolleri bu nesne üzerinden yapılır.
 */
object DocumentIds {
    data class Parsed(val volume: VolumeId, val relativePath: String)

    /**
     * "ABCD-1234:Music/A" → (ABCD-1234, "Music/A"). Göreli yol baştaki/sondaki '/'
     * karakterlerinden arındırılır. İki nokta yoksa ya da önek geçerli bir [VolumeId]
     * değilse (ör. "primary:") `null`.
     */
    fun parse(docId: String): Parsed? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = VolumeId.of(docId.substring(0, colon)) ?: return null
        return Parsed(volume, docId.substring(colon + 1).trim('/'))
    }

    /** (volume, "Music/A") → "ABCD-1234:Music/A". */
    fun build(volume: VolumeId, relativePath: String): String = "${volume.uuid}:${relativePath.trim('/')}"

    /** docId'nin [volume]'a ait olup olmadığı. Volume öneki büyük/küçük harf duyarsız karşılaştırılır. */
    fun belongsTo(docId: String, volume: VolumeId): Boolean = parse(docId)?.volume == volume
}
