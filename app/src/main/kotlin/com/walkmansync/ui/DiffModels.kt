package com.walkmansync.ui

import androidx.compose.runtime.Immutable
import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.SkipReason
import com.walkmansync.core.model.SkippedItem
import com.walkmansync.core.model.SyncItem
import com.walkmansync.core.model.SyncPlan
import com.walkmansync.core.model.SyncState
import java.util.Locale

/** Fark ekranındaki iki sekme. */
enum class DiffTab { Transfer, Skipped }

/** Tablo kontrolleri: sekme, sıralama, süzgeçler. [sortColumn] < 0 → plan sırası. */
data class DiffControls(
    val tab: DiffTab = DiffTab.Transfer,
    val sortColumn: Int = NO_SORT,
    val sortAscending: Boolean = true,
    val query: String = "",
    val format: AudioFormat? = null,
    val reason: SkipReason? = null,
) {
    companion object {
        const val NO_SORT = -1
    }
}

/**
 * Tablo satırı: aktarılacak öğe ya da atlanan öğe. Atlanan öğe zorlanabilirse [item] dolu olur
 * (kullanıcı seçerse kopyalanacak öğe).
 */
@Immutable
class DiffRow(
    val id: String,
    val item: SyncItem?,
    val skipped: SkippedItem?,
    val title: String,
    val artist: String,
    val album: String,
    val format: AudioFormat?,
    val size: Long,
    val sourcePath: String,
    val targetPath: String,
    val searchText: String,
) {
    val isSkipped: Boolean get() = skipped != null

    /** Aktarım kümesine eklenebilir mi (plan öğesi ya da zorlanabilir atlanan). */
    val canInclude: Boolean get() = item != null

    companion object {
        fun of(item: SyncItem): DiffRow = build(
            id = item.id,
            item = item,
            skipped = null,
            fileName = item.source.name,
            tags = item,
            size = item.size,
            sourcePath = item.source.relativePath,
            targetPath = item.targetPath,
        )

        fun of(skipped: SkippedItem): DiffRow = build(
            id = skipped.id,
            item = skipped.forceable,
            skipped = skipped,
            fileName = skipped.source.name,
            tags = skipped.forceable,
            size = skipped.source.size,
            sourcePath = skipped.source.relativePath,
            targetPath = skipped.forceable?.targetPath.orEmpty(),
        )

        private fun build(
            id: String,
            item: SyncItem?,
            skipped: SkippedItem?,
            fileName: String,
            tags: SyncItem?,
            size: Long,
            sourcePath: String,
            targetPath: String,
        ): DiffRow {
            val t = tags?.tags
            val title = t?.title ?: fileName.substringBeforeLast('.', fileName)
            val artist = t?.artist ?: t?.albumArtist ?: DASH
            val album = t?.album ?: DASH
            val format = tags?.format ?: item?.format ?: skipped?.source?.format
            val search = buildString {
                append(title).append('\n').append(artist).append('\n').append(album).append('\n')
                append(sourcePath).append('\n').append(targetPath)
            }.lowercase(Locale.ROOT)
            return DiffRow(id, item, skipped, title, artist, album, format, size, sourcePath, targetPath, search)
        }
    }
}

/** Bir plandan türetilmiş, tabloya hazır satır kümesi (arka planda bir kez hesaplanır). */
class PlanRows private constructor(
    val plan: SyncPlan,
    val items: List<DiffRow>,
    val skipped: List<DiffRow>,
    val selectableIds: Set<String>,
    val formats: List<AudioFormat>,
    val reasons: List<SkipReason>,
    private val byId: Map<String, DiffRow>,
) {
    fun row(id: String?): DiffRow? = if (id == null) null else byId[id]

    /** Seçili sekmenin süzülmüş ve sıralanmış satırları. */
    fun visibleFor(controls: DiffControls): List<DiffRow> {
        val query = controls.query.trim().lowercase(Locale.ROOT)
        val onTransferTab = controls.tab == DiffTab.Transfer
        val source = if (onTransferTab) items else skipped
        val filtered = source.filter { row ->
            (controls.format == null || row.format == controls.format) &&
                (onTransferTab || controls.reason == null || row.skipped?.reason == controls.reason) &&
                (query.isEmpty() || row.searchText.contains(query))
        }
        if (controls.sortColumn < 0) return filtered
        val primary = if (onTransferTab) transferComparator(controls.sortColumn) else skippedComparator(controls.sortColumn)
        val ordered = if (controls.sortAscending) primary else primary.reversed()
        return filtered.sortedWith(ordered.thenComparing(PATH_ORDER))
    }

    companion object {
        fun from(plan: SyncPlan): PlanRows {
            val itemRows = plan.items.map { DiffRow.of(it) }
            val skippedRows = plan.skipped.map { DiffRow.of(it) }
            val all = HashMap<String, DiffRow>(itemRows.size + skippedRows.size)
            for (row in itemRows) all[row.id] = row
            for (row in skippedRows) all[row.id] = row
            val selectable = LinkedHashSet<String>()
            for (row in itemRows) selectable.add(row.id)
            for (row in skippedRows) if (row.canInclude) selectable.add(row.id)
            val formats = (itemRows + skippedRows).mapNotNull { it.format }.distinct().sortedBy { it.ordinal }
            val reasons = plan.skipped.map { it.reason }.distinct().sortedBy { it.ordinal }
            return PlanRows(plan, itemRows, skippedRows, selectable, formats, reasons, all)
        }

        private val TEXT_ORDER: Comparator<String> = String.CASE_INSENSITIVE_ORDER
        private val PATH_ORDER: Comparator<DiffRow> = compareBy<DiffRow, String>(TEXT_ORDER) { it.sourcePath }

        /** Sütunlar: 0 başlık, 1 sanatçı, 2 albüm, 3 biçim, 4 boyut, 5 hedef yol. */
        private fun transferComparator(column: Int): Comparator<DiffRow> = when (column) {
            0 -> compareBy<DiffRow, String>(TEXT_ORDER) { it.title }
            1 -> compareBy<DiffRow, String>(TEXT_ORDER) { it.artist }
            2 -> compareBy<DiffRow, String>(TEXT_ORDER) { it.album }
            3 -> compareBy<DiffRow> { it.format?.ordinal ?: Int.MAX_VALUE }
            4 -> compareBy<DiffRow> { it.size }
            else -> compareBy<DiffRow, String>(TEXT_ORDER) { it.targetPath }
        }

        /** Sütunlar: 0 ad, 1 neden, 2 biçim, 3 boyut, 4 kaynak yol. */
        private fun skippedComparator(column: Int): Comparator<DiffRow> = when (column) {
            0 -> compareBy<DiffRow, String>(TEXT_ORDER) { it.title }
            1 -> compareBy<DiffRow> { it.skipped?.reason?.ordinal ?: Int.MAX_VALUE }
            2 -> compareBy<DiffRow> { it.format?.ordinal ?: Int.MAX_VALUE }
            3 -> compareBy<DiffRow> { it.size }
            else -> compareBy<DiffRow, String>(TEXT_ORDER) { it.sourcePath }
        }
    }
}

/**
 * Aktarım kümesi (işaretli öğeler). Büyük listelerde kayıt boyutu küçük kalsın diye, varsayılandan
 * (tüm plan öğeleri) ya da boş kümeden farkın küçük olanı saklanır.
 *
 * @property planStamp Seçimin ait olduğu planın [SyncPlan.createdAtMillis]; farklı planda varsayılan geçerlidir.
 * @property startEmpty true: taban boş küme; false: taban plan öğelerinin tamamı.
 * @property toggled Tabana göre ters çevrilen kimlikler.
 */
data class SelectionModel(
    val planStamp: Long,
    val startEmpty: Boolean,
    val toggled: Set<String>,
) {
    fun resolve(rows: PlanRows): Set<String> {
        val plan = rows.plan
        if (planStamp != plan.createdAtMillis) return plan.defaultSelection
        val result: MutableSet<String> = if (startEmpty) LinkedHashSet() else LinkedHashSet(plan.defaultSelection)
        for (id in toggled) {
            if (id !in rows.selectableIds) continue
            if (!result.remove(id)) result.add(id)
        }
        return result
    }

    companion object {
        const val NO_PLAN = Long.MIN_VALUE

        val None = SelectionModel(NO_PLAN, startEmpty = false, toggled = emptySet())

        fun encode(plan: SyncPlan, selected: Set<String>): SelectionModel {
            val default = plan.defaultSelection
            val againstDefault = LinkedHashSet<String>()
            for (id in default) if (id !in selected) againstDefault.add(id)
            for (id in selected) if (id !in default) againstDefault.add(id)
            return if (againstDefault.size <= selected.size) {
                SelectionModel(plan.createdAtMillis, startEmpty = false, toggled = againstDefault)
            } else {
                SelectionModel(plan.createdAtMillis, startEmpty = true, toggled = LinkedHashSet(selected))
            }
        }
    }
}

/** Fark ekranının tek seferde tükettiği durum. */
data class DiffUiState(
    val rows: PlanRows?,
    val controls: DiffControls,
    val visible: List<DiffRow>,
    val selected: Set<String>,
    val highlight: Set<String>,
    val detail: DiffRow?,
    val selectedBytes: Long,
) {
    val transferCount: Int get() = rows?.items?.size ?: 0
    val skippedCount: Int get() = rows?.skipped?.size ?: 0

    /** Görünür satırlardan eklenebilir olanların işaret durumu: 0 hiçbiri, 1 bazıları, 2 hepsi. */
    val visibleCheckState: Int
        get() {
            var includable = 0
            var included = 0
            for (row in visible) {
                if (!row.canInclude) continue
                includable++
                if (row.id in selected) included++
            }
            return when {
                included == 0 -> 0
                included == includable -> 2
                else -> 1
            }
        }

    companion object {
        val Empty = DiffUiState(null, DiffControls(), emptyList(), emptySet(), emptySet(), null, 0L)
    }
}

/** Plan taşıyan durumdaki plan (yalnızca PlanReady). */
fun SyncState.readyPlan(): SyncPlan? = (this as? SyncState.PlanReady)?.plan

/** Bu durumda tarama başlatılabilir mi (kart çifti hazırsa; meşgul değilse). */
fun SyncState.canScan(): Boolean = when (this) {
    is SyncState.Idle -> pair != null
    is SyncState.PlanReady, is SyncState.Completed, is SyncState.Failed -> true
    is SyncState.AwaitingVolumes, is SyncState.Scanning, is SyncState.Transferring, is SyncState.Paused -> false
}
