package com.walkmansync.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.walkmansync.WalkmanSyncApp
import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.BindRejection
import com.walkmansync.core.model.BindResult
import com.walkmansync.core.model.RemovableVolume
import com.walkmansync.core.model.SkipReason
import com.walkmansync.core.model.SyncState
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.workflow.VolumeSnapshot
import com.walkmansync.sync.SyncController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Kullanıcıya kısa süreli gösterilen sonuç iletileri (UI metne çevirir). */
sealed interface UiMessage {
    data class Bound(val role: VolumeRole, val label: String) : UiMessage
    data class Rejected(val reason: BindRejection, val detail: String?) : UiMessage

    /** Komut şu anki durumda kabul edilmedi (ör. başka bir iş sürüyor). */
    data object NotAccepted : UiMessage

    /** Beklenmeyen hata (ayrıntı günlük içindir, kullanıcıya gösterilmez). */
    data object Failure : UiMessage
}

/** Son taramada görülen hedef kart bilgisi (dosya sistemi ve boş alan yalnızca planla bilinir). */
data class TargetInfo(
    val volume: VolumeId,
    val fileSystem: TargetFileSystem,
    val freeBytes: Long?,
)

/**
 * UI durumu. Yalnızca [SyncController] görülür. Seçim, süzgeç ve sıralama [SavedStateHandle] içinde
 * tutulur: DeX pencere yeniden oluşturmasında ve süreç ölümünde korunur.
 */
class SyncViewModel(
    private val controller: SyncController,
    private val saved: SavedStateHandle,
) : ViewModel() {

    val state: StateFlow<SyncState> = controller.state
    val volumes: StateFlow<VolumeSnapshot> = controller.volumes
    val mountedVolumes: StateFlow<List<RemovableVolume>> = controller.mountedVolumes

    private val controls = MutableStateFlow(restoreControls())
    private val selection = MutableStateFlow(restoreSelection())
    private val highlight = MutableStateFlow<Set<String>>(emptySet())
    private val focusId = MutableStateFlow(saved.get<String>(KEY_FOCUS))
    private val cancelDialogState = MutableStateFlow(saved.get<Boolean>(KEY_CANCEL_DIALOG) ?: false)
    private val messageState = MutableStateFlow<UiMessage?>(null)
    private val targetInfoState = MutableStateFlow<TargetInfo?>(null)

    /** Kart bağlama sonuçları vb. (kapatılana dek görünür). */
    val message: StateFlow<UiMessage?> = messageState.asStateFlow()

    /** "Aktarımı iptal et?" onay penceresi açık mı. */
    val cancelDialog: StateFlow<Boolean> = cancelDialogState.asStateFlow()

    /** Son planlı taramadan hedef kart bilgisi; hiç plan görülmediyse null. */
    val targetInfo: StateFlow<TargetInfo?> = targetInfoState.asStateFlow()

    @Volatile
    private var latest: Visible = Visible.Empty

    /** Fark ekranı durumu: plan satırları, süzme/sıralama sonucu, seçim, ayrıntı. Arka planda hesaplanır. */
    val diff: StateFlow<DiffUiState>

    init {
        val planRows: Flow<PlanRows?> = state
            .map { it.readyPlan() }
            .distinctUntilChanged { old, new -> old === new }
            .map { plan -> plan?.let { PlanRows.from(it) } }
            .onEach { highlight.value = emptySet() }
        val visible: Flow<Visible> = combine(planRows, controls) { rows, c ->
            Visible(rows, c, rows?.visibleFor(c) ?: emptyList())
        }.onEach { latest = it }
        diff = combine(visible, selection, highlight, focusId) { v, sel, hl, focus ->
            val rows = v.rows
            val selected = if (rows == null) emptySet() else sel.resolve(rows)
            var bytes = 0L
            if (rows != null) {
                for (id in selected) bytes += rows.row(id)?.size ?: 0L
            }
            val detail = rows?.row(focus) ?: rows?.let { r -> hl.singleOrNull()?.let { r.row(it) } }
            DiffUiState(
                rows = rows,
                controls = v.controls,
                visible = v.list,
                selected = selected,
                highlight = if (rows == null) emptySet() else hl,
                detail = detail,
                selectedBytes = bytes,
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, DiffUiState.Empty)

        viewModelScope.launch {
            state.collect { s ->
                val plan = s.readyPlan() ?: return@collect
                targetInfoState.value = TargetInfo(plan.pair.target, plan.targetFileSystem, plan.targetFreeBytes)
            }
        }
    }

    // ------------------------------------------------------------------ komutlar

    fun scan() {
        if (!controller.scan()) messageState.value = UiMessage.NotAccepted
    }

    /** Seçili öğeleri aktarır; seçim boşsa ya da plan değiştiyse false. */
    fun startTransfer(): Boolean {
        val current = state.value.readyPlan() ?: return false
        val rows = latest.rows ?: return false
        if (rows.plan !== current) return false
        val ids = selection.value.resolve(rows)
        if (ids.isEmpty()) return false
        val accepted = controller.startTransfer(ids)
        if (!accepted) messageState.value = UiMessage.NotAccepted
        return accepted
    }

    fun togglePause() {
        when (state.value) {
            is SyncState.Transferring -> controller.pause()
            is SyncState.Paused -> controller.resume()
            else -> Unit
        }
    }

    /** Taramayı hemen, aktarımı onay penceresiyle iptal eder. */
    fun requestCancel() {
        when (state.value) {
            is SyncState.Scanning -> controller.cancel()
            is SyncState.Transferring, is SyncState.Paused -> setCancelDialog(true)
            else -> Unit
        }
    }

    fun confirmCancel() {
        setCancelDialog(false)
        controller.cancel()
    }

    fun dismissCancelDialog() = setCancelDialog(false)

    fun dismissResult() {
        controller.dismiss()
    }

    fun refreshVolumes() = controller.refreshVolumes()

    /** Etkinlik USB okuyucu takılmasıyla açıldı/öne geldi: birim bağlanana dek gecikmeli yeniden okunur. */
    fun onUsbDeviceAttached() = controller.refreshVolumes(afterUsbAttach = true)

    fun clearMessage() {
        messageState.value = null
    }

    private fun setCancelDialog(open: Boolean) {
        cancelDialogState.value = open
        saved[KEY_CANCEL_DIALOG] = open
    }

    // ------------------------------------------------------------------ kart seçimi

    /**
     * Seçici Intent'ini hazırlar. [hint] seçicinin açılacağı kart; [expected] doluysa yalnızca o kart
     * kabul edilir (izin yenileme). Bekleyen istek, etkinlik yeniden oluşsa da korunur.
     */
    fun createPickerIntent(role: VolumeRole, hint: VolumeId?, expected: VolumeId?): Intent {
        saved[KEY_PICK_ROLE] = role.name
        saved[KEY_PICK_EXPECTED] = expected?.toString()
        return controller.createPickerIntent(role, hint)
    }

    fun onPickResult(uri: Uri?) {
        val role = saved.get<String>(KEY_PICK_ROLE)?.let { name -> VolumeRole.entries.firstOrNull { it.name == name } }
        val expected = VolumeId.of(saved.get<String>(KEY_PICK_EXPECTED))
        saved.remove<String>(KEY_PICK_ROLE)
        saved.remove<String>(KEY_PICK_EXPECTED)
        if (role == null || uri == null) return
        viewModelScope.launch {
            messageState.value = try {
                when (val result = controller.bindVolume(role, uri, expected)) {
                    is BindResult.Bound -> UiMessage.Bound(role, result.binding.label)
                    is BindResult.Rejected -> UiMessage.Rejected(result.reason, result.detail)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiMessage.Failure
            }
        }
    }

    fun unbind(role: VolumeRole) {
        viewModelScope.launch {
            try {
                controller.unbindVolume(role)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageState.value = UiMessage.Failure
            }
        }
    }

    // ------------------------------------------------------------------ fark ekranı: görünüm

    fun setTab(tab: DiffTab) {
        updateControls { it.copy(tab = tab, sortColumn = DiffControls.NO_SORT, sortAscending = true) }
        highlight.value = emptySet()
    }

    fun setSort(column: Int, ascending: Boolean) {
        updateControls { it.copy(sortColumn = column, sortAscending = ascending) }
    }

    fun clearSort() {
        updateControls { it.copy(sortColumn = DiffControls.NO_SORT, sortAscending = true) }
    }

    fun setQuery(query: String) {
        updateControls { it.copy(query = query) }
    }

    fun setFormatFilter(format: AudioFormat?) {
        updateControls { it.copy(format = format) }
    }

    fun setReasonFilter(reason: SkipReason?) {
        updateControls { it.copy(reason = reason) }
    }

    /** Tablonun satır vurgusu (çoklu seçim); aktarım kümesinden bağımsızdır. */
    fun onHighlightChange(keys: Set<Any>) {
        highlight.value = keys.filterIsInstance<String>().toSet()
    }

    /** Tablo vurgusundaki satır sayısı (satır menüsü, güncel değeri eşzamanlı okur). */
    fun highlightedCount(): Int = highlight.value.size

    fun onRowClick(id: String) {
        focusId.value = id
        saved[KEY_FOCUS] = id
    }

    // ------------------------------------------------------------------ fark ekranı: aktarım kümesi

    fun setIncluded(id: String, included: Boolean) = mutateSelection { current, rows ->
        if (id !in rows.selectableIds) current else if (included) current + id else current - id
    }

    fun includeHighlighted() = mutateSelection { current, rows ->
        current + highlight.value.filter { it in rows.selectableIds }
    }

    fun excludeHighlighted() = mutateSelection { current, _ -> current - highlight.value }

    /** Görünür (süzülmüş) satırların hepsini ekler. */
    fun includeVisible() = mutateSelection { current, rows ->
        current + latest.list.map { it.id }.filter { it in rows.selectableIds }
    }

    fun excludeVisible() = mutateSelection { current, _ -> current - latest.list.map { it.id }.toSet() }

    /** Üstteki işaret kutusu: hepsi seçiliyse temizler, değilse hepsini ekler. */
    fun toggleVisible() {
        val rows = latest.rows ?: return
        val current = selection.value.resolve(rows)
        val allIncluded = latest.list.filter { it.canInclude }.all { it.id in current }
        if (allIncluded) excludeVisible() else includeVisible()
    }

    private inline fun mutateSelection(transform: (Set<String>, PlanRows) -> Set<String>) {
        val rows = latest.rows ?: return
        val current = selection.value.resolve(rows)
        val next = transform(current, rows)
        if (next == current) return
        val model = SelectionModel.encode(rows.plan, next)
        selection.value = model
        saved[KEY_SEL_STAMP] = model.planStamp
        saved[KEY_SEL_EMPTY] = model.startEmpty
        saved[KEY_SEL_TOGGLED] = ArrayList(model.toggled)
    }

    private inline fun updateControls(transform: (DiffControls) -> DiffControls) {
        val next = transform(controls.value)
        if (next == controls.value) return
        controls.value = next
        saved[KEY_TAB] = next.tab.name
        saved[KEY_SORT_COL] = next.sortColumn
        saved[KEY_SORT_ASC] = next.sortAscending
        saved[KEY_QUERY] = next.query
        saved[KEY_FORMAT] = next.format?.name
        saved[KEY_REASON] = next.reason?.name
    }

    private fun restoreControls(): DiffControls = DiffControls(
        tab = saved.get<String>(KEY_TAB)?.let { n -> DiffTab.entries.firstOrNull { it.name == n } } ?: DiffTab.Transfer,
        sortColumn = saved.get<Int>(KEY_SORT_COL) ?: DiffControls.NO_SORT,
        sortAscending = saved.get<Boolean>(KEY_SORT_ASC) ?: true,
        query = saved.get<String>(KEY_QUERY).orEmpty(),
        format = saved.get<String>(KEY_FORMAT)?.let { n -> AudioFormat.entries.firstOrNull { it.name == n } },
        reason = saved.get<String>(KEY_REASON)?.let { n -> SkipReason.entries.firstOrNull { it.name == n } },
    )

    private fun restoreSelection(): SelectionModel {
        val stamp = saved.get<Long>(KEY_SEL_STAMP) ?: return SelectionModel.None
        return SelectionModel(
            planStamp = stamp,
            startEmpty = saved.get<Boolean>(KEY_SEL_EMPTY) ?: false,
            toggled = saved.get<ArrayList<String>>(KEY_SEL_TOGGLED)?.toSet() ?: emptySet(),
        )
    }

    /** Süzme/sıralama sonucu; [latest] komutların güncel görünüme erişmesi içindir. */
    private class Visible(val rows: PlanRows?, val controls: DiffControls, val list: List<DiffRow>) {
        companion object {
            val Empty = Visible(null, DiffControls(), emptyList())
        }
    }

    companion object {
        private const val KEY_TAB = "diff_tab"
        private const val KEY_SORT_COL = "diff_sort_col"
        private const val KEY_SORT_ASC = "diff_sort_asc"
        private const val KEY_QUERY = "diff_query"
        private const val KEY_FORMAT = "diff_format"
        private const val KEY_REASON = "diff_reason"
        private const val KEY_FOCUS = "diff_focus"
        private const val KEY_SEL_STAMP = "sel_stamp"
        private const val KEY_SEL_EMPTY = "sel_empty"
        private const val KEY_SEL_TOGGLED = "sel_toggled"
        private const val KEY_CANCEL_DIALOG = "cancel_dialog"
        private const val KEY_PICK_ROLE = "pick_role"
        private const val KEY_PICK_EXPECTED = "pick_expected"

        /** Fabrika: bağımlılık `WalkmanSyncApp.graph.syncController`. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WalkmanSyncApp
                SyncViewModel(app.graph.syncController, createSavedStateHandle())
            }
        }
    }
}
