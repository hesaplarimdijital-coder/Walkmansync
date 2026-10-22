// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.delta

import com.walkmansync.core.fs.SourceTree
import com.walkmansync.core.fs.TargetTree
import com.walkmansync.core.index.SyncIndex
import com.walkmansync.core.model.PlanWarning
import com.walkmansync.core.model.ScanProgress
import com.walkmansync.core.model.SourceHandle
import com.walkmansync.core.model.SyncPlan
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.model.TargetHandle
import com.walkmansync.core.model.VolumePair
import com.walkmansync.core.scan.TreeScanResult

/**
 * Fark motoru girdisi.
 *
 * @property targetScan Hedefin "MUSIC" altı taraması (kendi .part artıklarımız ayrıca listelenir, files'a girmez).
 * @property index Yüklenen (ya da Foreign/Corrupt/Missing durumunda boş) indeks; `targetVolume == pair.target`.
 */
data class DeltaRequest(
    val pair: VolumePair,
    val source: SourceTree,
    val target: TargetTree,
    val sourceScan: TreeScanResult<SourceHandle>,
    val targetScan: TreeScanResult<TargetHandle>,
    val index: SyncIndex,
    val targetFileSystem: TargetFileSystem,
    val targetFreeBytes: Long?,
    val warnings: Set<PlanWarning>,
    val nowMillis: Long,
)

/**
 * @property plan `plan.baseIndex` = girdi indeksi + güncellenmiş `targetKeys` önbelleği.
 * @property targetKeysChanged Önbellek değişti (budandı/eklendi) → ScanWorkflow indeksi kaydeder.
 */
data class DeltaResult(val plan: SyncPlan, val targetKeysChanged: Boolean)

/**
 * Artımlı fark motoru. Uygulama (B2): `DefaultDeltaEngine(tagService, pathPlanner, dispatchers, clock, logger)`, core.
 * APPEND-ONLY: yalnızca kopyalama planlar; hedefte silme/üzerine yazma ASLA planlanmaz.
 *
 * Algoritma (sıra bağlayıcıdır):
 *  1. Ön eleme: size == -1 → UNKNOWN_SIZE; size == 0 → EMPTY_FILE.
 *  2. Yol + boyut (indeks): `index.findBySourcePath(path)`:
 *     - aynı boyut ve kaydın hedef yolu hedef taramasında var → ALREADY_SYNCED;
 *     - aynı boyut, hedefte yok → REMOVED_FROM_TARGET (zorlanabilir; yol yeniden planlanır);
 *     - farklı boyut → CHANGED_SINCE_SYNC (zorlanabilir; mevcut hedef korunur, yeni ad uniquify ile).
 *  3. Ad + boyut (hedef): MUSIC altında aynı ad (büyük/küçük harf duyarsız) ve aynı boyutta dosya → ALREADY_ON_TARGET.
 *  4. Etiketler: kalan SES adaylarının etiketleri `TagService` ile okunur (eşzamanlılık ≤ 4, aşama READING_TAGS).
 *     `ContentKey.of(tags)` hesaplanır.
 *  5. Hedef içerik anahtarları: 4. adımda en az bir aday kaldıysa, hedefteki ses dosyalarından indeks
 *     kaydı ([SyncIndex.findByTargetPath]) ya da geçerli önbellek ([SyncIndex.cachedTargetKey]) OLMAYANLARIN
 *     etiketleri okunur ve `targetKeys`'e eklenir. Hedefte artık bulunmayan önbellek kayıtları budanır.
 *     Aday yoksa hedef etiketleri hiç okunmaz.
 *  6. Tekilleştirme: `index.targetsWithContent(key)` ya da güncel önbellekte eşleşme → DUPLICATE_CONTENT
 *     (zorlanabilir). Bu taramada aynı anahtarlı ikinci kaynak → DUPLICATE_IN_SOURCE (zorlanabilir; ilk
 *     kaynak yol sırasına göre kazanır).
 *  7. Yerleştirme: `FlatFolderRule.classify` + `PathPlanner.planAudio`. Çakışma kümesi = hedef taramasındaki
 *     tüm yollar + bu planda ayrılmış yollar (normal + zorlanabilir), `SyncIndex.foldPath` ile katlanmış.
 *     İstenen yol hedefte VARSA: aynı boyut → ALREADY_ON_TARGET; farklı boyut → TARGET_PATH_OCCUPIED
 *     (zorlanabilir, forceable yolu uniquify ile). Yalnızca plan içi çakışmada doğrudan uniquify.
 *  8. Boyut: `targetFileSystem.maxFileSize` aşılırsa TOO_LARGE_FOR_FAT32 (FAT32 → forceable YOK;
 *     UNKNOWN → forceable var + TARGET_FS_UNKNOWN uyarısı).
 *  9. Yardımcı dosyalar (.lrc, cover.jpg) — ses kararlarından SONRA:
 *     - .lrc: aynı kaynak dizinde aynı gövdeli (büyük/küçük harf duyarsız) ses dosyası:
 *         planlandıysa → COMPANION, `companionOfId` = ses id'si, yol = planCompanion;
 *         atlandıysa ve forceable'ı varsa → aynı SkipReason ile atlanır, forceable companionOfId = sesin forceable id'si;
 *         ALREADY_SYNCED/ALREADY_ON_TARGET ise → sesin GERÇEK hedef yolu (indeks/hedef taraması) ile COMPANION,
 *         companionOfId = null; planlanan yol hedefte varsa ALREADY_ON_TARGET.
 *       Eş yoksa: PRESERVE kipte planStandalone; TAG_BASED kipte ORPHAN_AUXILIARY.
 *     - cover.jpg: aynı kaynak dizindeki seslerin (planlanan ya da hedefteki) hedef DİZİNLERİ tek bir dizinse
 *       oraya COMPANION (companionOfId = o dizine planlanan ilk ses, yoksa null); birden çok dizinse
 *       PRESERVE kipte planStandalone, TAG_BASED kipte ORPHAN_AUXILIARY. Hedef dizinde cover.jpg varsa
 *       ALREADY_ON_TARGET (boyuttan bağımsız).
 *     - .m3u8: planStandalone; aynı yol hedefte varsa ALREADY_ON_TARGET/TARGET_PATH_OCCUPIED (7. adım gibi).
 *     Yardımcı dosyalar da 2. adımın indeks kontrolünden geçer (ALREADY_SYNCED).
 * 10. Sıralama: `items` kaynak yoluna göre sıralı; her yardımcı dosya bağlı olduğu sesten SONRA gelir.
 * 11. Uyarılar: girdi uyarıları + (varsayılan seçim toplamı > targetFreeBytes) INSUFFICIENT_SPACE_FOR_ALL.
 *
 * İlerleme: READING_TAGS sırasında (itemsDone/itemsTotal), sonra PLANNING. Saf hesaplama `dispatchers.cpu`'da.
 * Hatalar: etiket okuma hataları yutulur (etiketsiz devam); ölümcül birim hataları ve iptal yayılır.
 */
interface DeltaEngine {
    suspend fun computePlan(request: DeltaRequest, onProgress: (ScanProgress) -> Unit): DeltaResult
}
