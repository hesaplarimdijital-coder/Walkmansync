// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.model

/**
 * Hedef (Walkman) kartındaki sabit yerleşim ve "kendi artığımız" tanımı. Append-only garantisinin
 * dayandığı tek doğruluk kaynağıdır:
 *
 *  - Uygulama hedefte YALNIZCA [isOwnArtifact] adlı dosyaları oluşturur, yeniden adlandırır, siler.
 *  - Yeni bir ses/yardımcı dosya, önce `<ad>`[PART_SUFFIX] olarak yazılır, sonra `<ad>` adına çevrilir;
 *    çevirme hedef ad mevcutsa başarısız olur (asla üzerine yazılmaz).
 *  - Ses/yardımcı dosyalar yalnızca [MUSIC_DIR] altına yazılır ([isValidTargetFilePath]).
 */
object TargetLayout {
    /** Walkman müzik kökü (volume köküne göre). */
    const val MUSIC_DIR = "MUSIC"

    /** İndeks dosyasının adı; hedef AĞAÇ KÖKÜNDE durur ([indexPathFor]). */
    const val INDEX_FILE_NAME = ".walkman_sync_index.json"

    /** Bir önceki geçerli indeks (atomik değiştirme sırasında ve bozulma kurtarmada kullanılır). */
    const val INDEX_BACKUP_NAME = ".walkman_sync_index.json.bak"

    /**
     * Yarım dosya soneki. Şartnamedeki `.part` uzantısını taşır; ".wsync" işareti, başka bir
     * araçtan kalmış kullanıcı `.part` dosyalarını asla silmememizi sağlar.
     */
    const val PART_SUFFIX = ".wsync.part"

    /** Hedef ağaç kökü geçerli mi: volume kökü ("") ya da "MUSIC" (büyük/küçük harf duyarsız). */
    fun isValidTargetRoot(treeRootPath: String): Boolean =
        treeRootPath.trim('/').let { it.isEmpty() || it.equals(MUSIC_DIR, ignoreCase = true) }

    /**
     * İndeksin volume-göreli yolu: ağaç kökü volume kökü ise ".walkman_sync_index.json", MUSIC ise
     * "MUSIC/.walkman_sync_index.json" (gerçek harf büyüklüğüyle). Yedek ve .part aynı dizindedir.
     */
    fun indexPathFor(treeRootPath: String): String = join(treeRootPath, INDEX_FILE_NAME)
    fun indexBackupPathFor(treeRootPath: String): String = join(treeRootPath, INDEX_BACKUP_NAME)
    fun indexPartPathFor(treeRootPath: String): String = join(treeRootPath, partNameFor(INDEX_FILE_NAME))

    private fun join(dir: String, name: String): String = dir.trim('/').let { if (it.isEmpty()) name else "$it/$name" }

    /** "01 - T.flac" → "01 - T.flac.wsync.part". */
    fun partNameFor(finalName: String): String = finalName + PART_SUFFIX

    /** "01 - T.flac.wsync.part" → "01 - T.flac"; sonek yoksa null. */
    fun finalNameOf(partName: String): String? =
        if (partName.endsWith(PART_SUFFIX, ignoreCase = true) && partName.length > PART_SUFFIX.length)
            partName.substring(0, partName.length - PART_SUFFIX.length) else null

    fun isPartFile(name: String): Boolean = finalNameOf(name) != null

    /** Uygulamanın oluşturduğu, yeniden adlandırabileceği ve silebileceği adlar. */
    fun isOwnArtifact(name: String): Boolean =
        isPartFile(name) || name == INDEX_FILE_NAME || name == INDEX_BACKUP_NAME

    /**
     * Bir ses/yardımcı dosya hedef yolunun geçerli olup olmadığı: "MUSIC/" ile başlar (büyük/küçük harf
     * duyarsız), en az bir alt parça içerir, boş/"."/".." parça, '\\' ve baş/son '/' yoktur ve son parça
     * kendi artığımız değildir.
     */
    fun isValidTargetFilePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith('/') || path.endsWith('/') || '\\' in path) return false
        val parts = path.split('/')
        if (parts.size < 2 || !parts[0].equals(MUSIC_DIR, ignoreCase = true)) return false
        if (parts.any { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }) return false
        return !isOwnArtifact(parts.last())
    }

    /** Bir dizin yolu: "MUSIC" ya da "MUSIC/…" (aynı parça kuralları). */
    fun isValidTargetDirPath(path: String): Boolean {
        if (path.equals(MUSIC_DIR, ignoreCase = true)) return true
        if (path.isEmpty() || path.startsWith('/') || path.endsWith('/') || '\\' in path) return false
        val parts = path.split('/')
        if (!parts[0].equals(MUSIC_DIR, ignoreCase = true)) return false
        return parts.none { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) }
    }
}

/**
 * exFAT/FAT32 ad kuralları ve uzunluk sınırları. [com.walkmansync.core.path.FileNameSanitizer]
 * uygulaması bu sabitleri kullanır.
 */
object TargetNameRules {
    /** FAT32/exFAT'ta yasak karakterler (kontrol karakterleri 0x00-0x1F ve 0x7F de yasak). */
    const val FORBIDDEN_CHARS = "\"*/:<>?\\|"

    /** Yasak karakterlerin yerine konan karakter. */
    const val REPLACEMENT = '_'

    /** Dizin parçası azami uzunluğu (UTF-16 birimi; vekil çiftler bölünmez). */
    const val MAX_DIR_COMPONENT = 120

    /**
     * Dosya adı azami uzunluğu, uzantı DAHİL, [TargetLayout.PART_SUFFIX] HARİÇ.
     * 255 (LFN sınırı) - 11 (".wsync.part") = 244; güvenlik payıyla 240.
     */
    const val MAX_FILE_NAME = 240

    /** "MUSIC/" sonrası azami dizin derinliği; aşan yapılar bu derinliğe kısaltılır (son parçalar korunur). */
    const val MAX_DIR_DEPTH = 8

    /** DOS/Windows ayrılmış adları (uzantısız kısmı eşleşirse sonuna '_' eklenir). */
    val RESERVED_NAMES: Set<String> = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (i in 1..9) { add("COM$i"); add("LPT$i") }
    }

    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"
    const val PLAYLISTS_DIR = "Playlists"
}
