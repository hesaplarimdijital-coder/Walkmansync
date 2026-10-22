// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.path

import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.model.SourceFile
import com.walkmansync.core.model.TargetLayout
import java.util.Locale

/**
 * exFAT/FAT32 ad temizleyici. Uygulama (B2): `FatFileNameSanitizer`, core. Saf, durumsuz, thread-safe.
 *
 * Kurallar ([com.walkmansync.core.model.TargetNameRules] sabitleriyle), sırasıyla:
 *  1. Unicode NFC normalizasyonu.
 *  2. Kontrol karakterleri (0x00-0x1F, 0x7F) ve `FORBIDDEN_CHARS` → `REPLACEMENT` ('_').
 *  3. Ardışık boşluklar tek boşluğa; baştaki boşluklar ve SONDAKİ boşluk/noktalar silinir (FAT kuralı).
 *  4. Uzunluk sınırı (dizin: `MAX_DIR_COMPONENT`, dosya: `MAX_FILE_NAME` uzantı dahil) — vekil çift
 *     bölünmeden kırpılır, kırpma sonrası 3. adım yeniden uygulanır; dosyada uzantı korunur, gövde kırpılır.
 *  5. Sonuç boşsa "_".
 *  6. Uzantısız gövde (büyük/küçük harf duyarsız) `RESERVED_NAMES` içindeyse gövdeye "_" eklenir.
 *  7. Sonuç '.' ile başlıyorsa başa '_' eklenir (gizli dosya olarak atlanmasın).
 * Uzantı küçük harfe çevrilir. Fonksiyonlar idempotenttir: f(f(x)) == f(x).
 */
interface FileNameSanitizer {
    /** Tek dizin parçası ("AC/DC" → "AC_DC", "Album..." → "Album"). */
    fun sanitizeDirComponent(raw: String): String

    /**
     * Dosya adı: [baseName] gövde, [extension] uzantı (noktasız; boşsa uzantısız ad).
     * ("What? ", "FLAC") → "What_.flac".
     */
    fun sanitizeFileName(baseName: String, extension: String): String
}

/** Bir kaynak dosyanın hedefe yerleştirme kipi. */
enum class PlacementMode {
    /** Göreli yapıyı koru: MUSIC/<kaynak göreli dizinleri>/<ad>. */
    PRESERVE_STRUCTURE,
    /** Etiketlere göre: MUSIC/{Artist}/{Album}/{NN} - {Title}.{ext}. */
    TAG_BASED,
}

/**
 * "DÜZ KLASÖR" KURALI — kesin tanım (B2 bunu DEĞİŞTİRMEDEN kullanır).
 *
 * Girdi: kaynak dosyanın volume-göreli yolu ve kaynak ağaç kökünün volume-göreli yolu.
 *  1. `rel` = dosya yolunun ağaç köküne göre kısmı (kök "" ise yolun kendisi). Dizin parçaları = `dirs`.
 *  2. `dirs` boş değilse ve ilk parça (büyük/küçük harf duyarsız) "Music" ise bu TEK parça atılır
 *     (kartın kendi müzik kökü; "MUSIC/Music/…" oluşmasın).
 *  3. TAG_BASED, şu koşullardan biri sağlanırsa:
 *     a) `dirs` boş (dosya doğrudan kökte ya da Music/ içinde),
 *     b) `dirs[0]` [FLAT_FOLDER_NAMES] içinde (büyük/küçük harf duyarsız) — altındaki her derinlik dahil
 *        (ör. "Download/Albüm X/01.flac" de etiketlere göre yerleşir),
 *     c) `dirs.size` < [MIN_STRUCTURED_DEPTH] (tek seviyeli klasör: "Albüm/01.flac" sığ sayılır).
 *  4. Aksi hâlde PRESERVE_STRUCTURE; korunan yol = `dirs` (2. adımdan sonra) + dosya adı.
 *
 * Örnekler (ağaç kökü = volume kökü):
 *  - "Download/x.mp3" → TAG_BASED
 *  - "Music/x.mp3" → TAG_BASED
 *  - "Music/Artist/Album/01.flac" → PRESERVE ["Artist","Album"]
 *  - "Music/Album/01.flac" → TAG_BASED (sığ)
 *  - "HiRes/Artist/Album/CD1/01.dsf" → PRESERVE ["HiRes","Artist","Album","CD1"]
 *  - "Telegram/Telegram Audio/x.opus" → TAG_BASED
 */
object FlatFolderRule {
    const val MIN_STRUCTURED_DEPTH = 2
    private const val MUSIC_ROOT = "music"

    /** Küçük harfle. İndirme/mesajlaşma uygulamalarının düz biçimde dosya bıraktığı klasörler. */
    val FLAT_FOLDER_NAMES: Set<String> = setOf(
        "download", "downloads", "telegram", "telegram audio", "whatsapp", "bluetooth",
        "documents", "recordings", "new music", "incoming", "inbox", "unsorted",
    )

    data class Classification(val mode: PlacementMode, val preservedDirs: List<String>)

    /**
     * @param sourceRelativePath dosyanın volume-göreli yolu ("Download/x.mp3")
     * @param sourceRootPath kaynak ağaç kökünün volume-göreli yolu ("" ya da "Music")
     */
    fun classify(sourceRelativePath: String, sourceRootPath: String): Classification {
        val path = sourceRelativePath.trim('/')
        val root = sourceRootPath.trim('/')
        val rel = when {
            root.isEmpty() -> path
            path.length > root.length && path.regionMatches(0, root, 0, root.length, ignoreCase = true) &&
                path[root.length] == '/' -> path.substring(root.length + 1)
            else -> path // kök dışı yol beklenmez; olduğu gibi değerlendirilir
        }
        var dirs = rel.split('/').dropLast(1).filter { it.isNotEmpty() }
        if (dirs.isNotEmpty() && dirs[0].lowercase(Locale.ROOT) == MUSIC_ROOT) dirs = dirs.drop(1)
        val flat = dirs.isEmpty() ||
            dirs[0].lowercase(Locale.ROOT) in FLAT_FOLDER_NAMES ||
            dirs.size < MIN_STRUCTURED_DEPTH
        return if (flat) Classification(PlacementMode.TAG_BASED, emptyList())
        else Classification(PlacementMode.PRESERVE_STRUCTURE, dirs)
    }
}

/**
 * Hedef yol planlayıcı. Uygulama (B2): `DefaultPathPlanner(sanitizer: FileNameSanitizer)`, core. Saf, thread-safe.
 * Döndürülen tüm yollar "MUSIC/" ile başlar ve [TargetLayout.isValidTargetFilePath] koşulunu sağlar.
 *
 * TAG_BASED adlandırma (her parça [FileNameSanitizer] ile temizlenir):
 *  - Dizin: `MUSIC/{folderArtist ?: UNKNOWN_ARTIST}/{album ?: UNKNOWN_ALBUM}`.
 *  - Dosya gövdesi:
 *      title varsa:  `{önek} - {title}`; önek = parça no 2 hane sıfır dolgulu (≥100 ise 3 hane);
 *                    discNumber ≥ 2 ise `{disc}-{NN}` ("2-01"). Parça no yoksa yalnızca `{title}`.
 *      title yoksa:  kaynağın ORİJİNAL gövdesi (önek eklenmez).
 *  - Uzantı: kaynağın uzantısı, küçük harf (".alac" korunur).
 * PRESERVE_STRUCTURE: `MUSIC/{preservedDirs…}/{ad}`; her parça temizlenir; dizin derinliği
 *   `TargetNameRules.MAX_DIR_DEPTH`'i aşarsa İLK parçalar atılır (son parçalar korunur).
 * M3U8: TAG_BASED kipte `MUSIC/{PLAYLISTS_DIR}/{ad}`; PRESERVE kipte korunan yol.
 */
interface PathPlanner {
    /** Ses dosyası için istenen hedef yol (henüz çakışma çözülmemiş). [tags] null olabilir. */
    fun planAudio(source: SourceFile, sourceRootPath: String, tags: AudioTags?): String

    /**
     * Eşi bulunmayan yardımcı dosya (.m3u8 ya da PRESERVE kipteki .lrc/cover.jpg) için istenen yol.
     *
     * TAG_BASED kipteki .lrc/cover.jpg (Team Lead kararı): fark motoru bu durumda ÇAĞIRMAZ, dosyayı
     * `SkipReason.ORPHAN_AUXILIARY` ile atlar (etiketsiz yardımcı dosyanın ait olduğu albüm bilinemez). Yine de
     * çağrılırsa fonksiyon toplamdır ve fırlatmaz: `MUSIC/{UNKNOWN_ARTIST}/{UNKNOWN_ALBUM}/{ad}` döner
     * (cover için "cover.jpg"), sonuç [TargetLayout.isValidTargetFilePath] koşulunu sağlar.
     */
    fun planStandalone(source: SourceFile, sourceRootPath: String): String

    /**
     * Yardımcı dosyanın ses dosyasını izleyen yolu.
     *  - .lrc: `{audioDir}/{audio gövdesi}.lrc` (ses "01 - T.flac" → "01 - T.lrc").
     *  - cover.jpg: `{audioDir}/cover.jpg` (her zaman küçük harf "cover.jpg").
     * @param audioTargetPath ses dosyasının (planlanan ya da indeksteki) hedef yolu.
     */
    fun planCompanion(audioTargetPath: String, companion: SourceFile): String

    /**
     * Büyük/küçük harf duyarsız çakışma çözümü: [desired] alınmışsa gövdeye " (2)", " (3)"… eklenir
     * (uzantı korunur, ad sınırı aşılırsa gövde kırpılır). [isTaken] KATLANMAMIŞ yol alır; karşılaştırma
     * katlamasını çağıran yapar. En fazla 999 deneme; hepsi doluysa [IllegalStateException].
     */
    fun uniquify(desired: String, isTaken: (String) -> Boolean): String
}
