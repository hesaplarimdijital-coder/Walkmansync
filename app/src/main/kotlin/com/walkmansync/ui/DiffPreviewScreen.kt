package com.walkmansync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkmansync.R
import com.walkmansync.core.model.SyncPlan
import com.walkmansync.core.model.SyncState
import desktopkit.components.ColumnWidth
import desktopkit.components.DeskButton
import desktopkit.components.DeskIconButton
import desktopkit.components.DeskIcons
import desktopkit.components.DenseTable
import desktopkit.components.HorizontalRule
import desktopkit.components.HorizontalSplitPane
import desktopkit.components.MenuEntry
import desktopkit.components.SegmentedChoice
import desktopkit.components.TableColumn
import desktopkit.components.TableSort
import desktopkit.components.TooltipArea
import desktopkit.components.rememberSplitState
import desktopkit.screens.MessageScreen
import desktopkit.screens.PageHeader
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.theme.DeskTheme

private const val CHECK_MARK = "✓"

/** Tablonun görüntülenen sütunu ile sıralama sütun numarasını eşleştirir (dar pencerede bazı sütunlar gizlenir). */
private class ColumnDef(val logical: Int, val column: TableColumn<DiffRow>)

/**
 * Ekran 2: "Aktarılacak Yeni Müzikler". Sol: DenseTable (aktarılacaklar / atlananlar sekmeleri, arama,
 * süzgeçler, sıralama); sağ: ayrıntı bölmesi; altta toplam boyut / boş alan özeti ve aktarım düğmesi.
 */
@Composable
fun DiffPreviewScreen(viewModel: SyncViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui by viewModel.diff.collectAsStateWithLifecycle()
    val current = state
    val plan = current.readyPlan()

    if (current is SyncState.Scanning) {
        Column(modifier.fillMaxSize()) {
            PageHeader(title = stringResource(R.string.ui_nav_new_music))
            Column(Modifier.padding(horizontal = DeskTheme.dimens.pagePadding)) {
                ScanPanel(progress = current.progress, onCancel = viewModel::requestCancel)
            }
        }
        return
    }
    if (plan == null) {
        val canScan = current.canScan()
        MessageScreen(
            title = stringResource(R.string.ui_diff_empty_title),
            icon = WalkmanIcons.Sync,
            modifier = modifier,
            pageTitle = stringResource(R.string.ui_nav_new_music),
            description = stringResource(R.string.ui_diff_empty_desc),
            actionLabel = if (canScan) stringResource(R.string.ui_action_scan) else null,
            onAction = if (canScan) viewModel::scan else null,
        )
        return
    }
    if (ui.rows?.plan !== plan) {
        // Satırlar arka planda hazırlanıyor.
        Column(modifier.fillMaxSize()) {
            PageHeader(title = stringResource(R.string.ui_nav_new_music))
            Column(Modifier.padding(horizontal = DeskTheme.dimens.pagePadding)) { DeskProgressBar(null) }
        }
        return
    }
    PlanContent(viewModel = viewModel, ui = ui, plan = plan, modifier = modifier)
}

@Composable
private fun PlanContent(viewModel: SyncViewModel, ui: DiffUiState, plan: SyncPlan, modifier: Modifier) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val widthClass = currentWidthClass()
    val compact = widthClass == WidthClass.Compact
    val wide = widthClass.minWidthDp >= WidthClass.Expanded.minWidthDp
    val controls = ui.controls
    val rows = ui.rows
    val splitState = rememberSplitState(430.dp)

    val table: @Composable () -> Unit = {
        TableArea(viewModel = viewModel, ui = ui, compact = compact)
    }
    val detail: @Composable () -> Unit = {
        val row = ui.detail
        DetailPane(
            row = row,
            included = row != null && row.id in ui.selected,
            onIncludedChange = { on -> if (row != null) viewModel.setIncluded(row.id, on) },
        )
    }

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.ui_nav_new_music),
            subtitle = res.getString(R.string.ui_diff_subtitle, ui.transferCount, ui.skippedCount),
            actions = {
                DeskButton(
                    text = stringResource(R.string.ui_action_rescan),
                    onClick = viewModel::scan,
                    icon = WalkmanIcons.Sync,
                )
            },
        )
        Column(
            modifier = Modifier.padding(horizontal = dimens.pagePadding),
            verticalArrangement = Arrangement.spacedBy(dimens.gapS),
        ) {
            MessageBanner(viewModel)
            for (warning in plan.warnings) {
                StatusBanner(BannerTone.Warning, res.planWarningText(warning))
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            ) {
                SegmentedChoice(
                    options = listOf(
                        DiffTab.Transfer to res.getString(R.string.ui_tab_transfer, ui.transferCount),
                        DiffTab.Skipped to res.getString(R.string.ui_tab_skipped, ui.skippedCount),
                    ),
                    selected = controls.tab,
                    onSelect = viewModel::setTab,
                )
                MenuButton(
                    text = controls.format?.name ?: stringResource(R.string.ui_filter_all_formats),
                    icon = DeskIcons.ChevronDown,
                    entries = {
                        buildList<MenuEntry> {
                            add(
                                MenuEntry.Item(
                                    label = res.getString(R.string.ui_filter_all_formats),
                                    onClick = { viewModel.setFormatFilter(null) },
                                    checked = controls.format == null,
                                ),
                            )
                            for (format in rows?.formats.orEmpty()) {
                                add(
                                    MenuEntry.Item(
                                        label = format.name,
                                        onClick = { viewModel.setFormatFilter(format) },
                                        checked = controls.format == format,
                                    ),
                                )
                            }
                        }
                    },
                )
                if (controls.tab == DiffTab.Skipped) {
                    MenuButton(
                        text = controls.reason?.let { res.skipReasonLabel(it) } ?: stringResource(R.string.ui_filter_all_reasons),
                        icon = DeskIcons.ChevronDown,
                        entries = {
                            buildList<MenuEntry> {
                                add(
                                    MenuEntry.Item(
                                        label = res.getString(R.string.ui_filter_all_reasons),
                                        onClick = { viewModel.setReasonFilter(null) },
                                        checked = controls.reason == null,
                                    ),
                                )
                                for (reason in rows?.reasons.orEmpty()) {
                                    val count = rows?.skipped?.count { it.skipped?.reason == reason } ?: 0
                                    add(
                                        MenuEntry.Item(
                                            label = res.getString(R.string.ui_filter_reason_item, res.skipReasonLabel(reason), count),
                                            onClick = { viewModel.setReasonFilter(reason) },
                                            checked = controls.reason == reason,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
                if (controls.sortColumn >= 0) {
                    DeskButton(text = stringResource(R.string.ui_action_plan_order), onClick = viewModel::clearSort)
                }
            }
            FilterTextField(
                value = controls.query,
                onValueChange = viewModel::setQuery,
                hint = stringResource(R.string.ui_filter_hint),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(dimens.gapS))
        Box(Modifier.weight(1f)) {
            if (wide) {
                HorizontalSplitPane(
                    state = splitState,
                    start = table,
                    end = detail,
                    minStart = 380.dp,
                    minEnd = 300.dp,
                    dividerLabel = stringResource(R.string.ui_split_divider),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { table() }
                    if (ui.detail != null) {
                        HorizontalRule()
                        Box(Modifier.heightIn(max = 240.dp)) { detail() }
                    }
                }
            }
        }
        SummaryBar(viewModel = viewModel, ui = ui, plan = plan)
    }
}

@Composable
private fun TableArea(viewModel: SyncViewModel, ui: DiffUiState, compact: Boolean) {
    val res = appResources()
    val dimens = DeskTheme.dimens
    val colors = DeskTheme.colors
    val controls = ui.controls
    val onSkippedTab = controls.tab == DiffTab.Skipped
    val strong = DeskTheme.type.bodyStrong
    val titleLabel = stringResource(R.string.ui_col_title)
    val defs = remember(controls.tab, compact, ui.selected, res, strong, titleLabel) {
        buildColumns(res, onSkippedTab, compact, ui.selected, strong, titleLabel)
    }
    val columns = remember(defs) { defs.map { it.column } }
    val tableSort: TableSort? = if (controls.sortColumn >= 0) {
        defs.indexOfFirst { it.logical == controls.sortColumn }.takeIf { it >= 0 }?.let { TableSort(it, controls.sortAscending) }
    } else {
        null
    }
    val includable = ui.visible.any { it.canInclude }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.pagePadding - 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.gapXs),
        ) {
            DeskTriCheckbox(state = ui.visibleCheckState, onClick = viewModel::toggleVisible, enabled = includable)
            Text(
                text = if (ui.highlight.isEmpty()) {
                    stringResource(if (onSkippedTab) R.string.ui_select_all_shown_skipped else R.string.ui_select_all_shown)
                } else {
                    res.getString(R.string.ui_highlighted_count, ui.highlight.size)
                },
                style = DeskTheme.type.label,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DeskIconButton(
                icon = DeskIcons.Plus,
                contentDescription = stringResource(if (onSkippedTab) R.string.ui_action_force_selected else R.string.ui_action_include_selected),
                onClick = viewModel::includeHighlighted,
                enabled = ui.highlight.isNotEmpty(),
            )
            DeskIconButton(
                icon = DeskIcons.Close,
                contentDescription = stringResource(R.string.ui_action_exclude_selected),
                onClick = viewModel::excludeHighlighted,
                enabled = ui.highlight.isNotEmpty(),
            )
        }
        Box(Modifier.weight(1f)) {
            if (ui.visible.isEmpty()) {
                val emptyText = when {
                    !onSkippedTab && ui.transferCount == 0 -> R.string.ui_empty_nothing_new
                    onSkippedTab && ui.skippedCount == 0 -> R.string.ui_empty_nothing_skipped
                    else -> R.string.ui_empty_no_match
                }
                Box(Modifier.fillMaxSize().padding(dimens.pagePadding), contentAlignment = Alignment.Center) {
                    Text(stringResource(emptyText), style = DeskTheme.type.body, color = colors.textSecondary)
                }
            } else {
                DenseTable(
                    columns = columns,
                    rows = ui.visible,
                    key = { it.id },
                    onRowClick = { row, _ -> viewModel.onRowClick(row.id) },
                    modifier = Modifier.background(colors.background),
                    sort = tableSort,
                    onSortChange = { sort ->
                        defs.getOrNull(sort.column)?.let { viewModel.setSort(it.logical, sort.ascending) }
                    },
                    rowMenu = { row, _ ->
                        val count = viewModel.highlightedCount()
                        listOf(
                            MenuEntry.Item(
                                label = res.getString(
                                    if (onSkippedTab) R.string.ui_menu_force_selected else R.string.ui_menu_include_selected,
                                    count,
                                ),
                                onClick = viewModel::includeHighlighted,
                                icon = DeskIcons.Plus,
                                enabled = count > 0,
                            ),
                            MenuEntry.Item(
                                label = res.getString(R.string.ui_menu_exclude_selected, count),
                                onClick = viewModel::excludeHighlighted,
                                icon = DeskIcons.Close,
                                enabled = count > 0,
                            ),
                            MenuEntry.Separator,
                            MenuEntry.Item(
                                label = res.getString(R.string.ui_menu_details),
                                onClick = { viewModel.onRowClick(row.id) },
                                icon = DeskIcons.Info,
                            ),
                        )
                    },
                    rowMenuLabel = stringResource(R.string.ui_row_menu),
                    selectedKeys = ui.highlight,
                    onSelectionChange = viewModel::onHighlightChange,
                )
            }
        }
    }
}

private fun buildColumns(
    res: android.content.res.Resources,
    skippedTab: Boolean,
    compact: Boolean,
    selected: Set<String>,
    strong: androidx.compose.ui.text.TextStyle,
    titleLabel: String,
): List<ColumnDef> {
    val mark = ColumnDef(
        logical = -1,
        column = TableColumn<DiffRow>(title = "", width = ColumnWidth.Fixed(36.dp), sortable = false) {
            if (it.id in selected) CHECK_MARK else ""
        },
    )
    val title = ColumnDef(0, TableColumn<DiffRow>(titleLabel, ColumnWidth.Weight(1.6f), textStyle = strong) { it.title })
    val format = TableColumn<DiffRow>(res.getString(R.string.ui_col_format), ColumnWidth.Fixed(64.dp), mono = true) {
        it.format?.name ?: DASH
    }
    val size = TableColumn<DiffRow>(res.getString(R.string.ui_col_size), ColumnWidth.Fixed(88.dp), alignEnd = true, mono = true) {
        formatBytes(it.size)
    }
    return if (!skippedTab) {
        buildList {
            add(mark)
            add(title)
            add(ColumnDef(1, TableColumn<DiffRow>(res.getString(R.string.ui_col_artist), ColumnWidth.Weight(1.1f)) { it.artist }))
            if (!compact) add(ColumnDef(2, TableColumn<DiffRow>(res.getString(R.string.ui_col_album), ColumnWidth.Weight(1.1f)) { it.album }))
            add(ColumnDef(3, format))
            add(ColumnDef(4, size))
            if (!compact) {
                add(ColumnDef(5, TableColumn<DiffRow>(res.getString(R.string.ui_col_target), ColumnWidth.Weight(2f)) { it.targetPath }))
            }
        }
    } else {
        buildList {
            add(mark)
            add(title)
            add(
                ColumnDef(
                    1,
                    TableColumn<DiffRow>(res.getString(R.string.ui_col_reason), ColumnWidth.Weight(1.5f)) { row ->
                        row.skipped?.reason?.let { res.skipReasonLabel(it) } ?: DASH
                    },
                ),
            )
            add(ColumnDef(2, format))
            add(ColumnDef(3, size))
            if (!compact) {
                add(ColumnDef(4, TableColumn<DiffRow>(res.getString(R.string.ui_col_source), ColumnWidth.Weight(2f)) { it.sourcePath }))
            }
        }
    }
}

@Composable
private fun DetailPane(row: DiffRow?, included: Boolean, onIncludedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val res = appResources()
    if (row == null) {
        MessageScreen(
            title = stringResource(R.string.ui_detail_empty_title),
            icon = DeskIcons.Info,
            modifier = modifier,
            description = stringResource(R.string.ui_detail_empty_desc),
        )
        return
    }
    val item = row.item
    val skipped = row.skipped
    val tags = item?.tags
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(dimens.pagePadding),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.gapS)) {
            StatusGlyph(
                status = when {
                    skipped == null -> DiffStatus.New
                    row.canInclude -> DiffStatus.Conflict
                    else -> DiffStatus.Skipped
                },
                description = null,
                size = dimens.iconLarge,
            )
            Text(row.title, style = DeskTheme.type.title, color = colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            row.format?.let { FormatBadge(it) }
        }
        val subtitle = listOf(row.artist, row.album).filter { it != DASH }.joinToString(" • ")
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = DeskTheme.type.body, color = colors.textSecondary)
        }
        if (row.canInclude) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeskCheckbox(checked = included, onCheckedChange = onIncludedChange)
                Text(
                    text = stringResource(if (skipped != null) R.string.ui_detail_force else R.string.ui_detail_include),
                    style = DeskTheme.type.body,
                    color = colors.text,
                )
            }
        }
        HorizontalRule()
        if (skipped != null) {
            KeyValueRow(stringResource(R.string.ui_detail_skip_reason), res.skipReasonLabel(skipped.reason))
            Text(res.skipReasonDescription(skipped.reason), style = DeskTheme.type.labelSmall, color = colors.textTertiary)
            val extra = skipped.detail
            if (extra != null) KeyValueRow(stringResource(R.string.ui_detail_note), extra, mono = true, maxLines = 4)
        } else if (item != null) {
            KeyValueRow(stringResource(R.string.ui_detail_copy_reason), res.copyReasonLabel(item.reason))
        }
        KeyValueRow(stringResource(R.string.ui_detail_size), formatBytes(row.size))
        if (tags != null) {
            tags.trackNumber?.let { KeyValueRow(stringResource(R.string.ui_detail_track), it.toString()) }
            tags.discNumber?.let { KeyValueRow(stringResource(R.string.ui_detail_disc), it.toString()) }
            tags.durationMs?.let { KeyValueRow(stringResource(R.string.ui_detail_duration), formatDuration(it)) }
        }
        KeyValueRow(stringResource(R.string.ui_detail_source), row.sourcePath, mono = true, maxLines = 6)
        if (row.targetPath.isNotEmpty()) {
            KeyValueRow(stringResource(R.string.ui_detail_target), row.targetPath, mono = true, maxLines = 6)
        }
        val companionOf = item?.companionOfId
        if (companionOf != null) {
            KeyValueRow(stringResource(R.string.ui_detail_companion), companionOf, mono = true, maxLines = 4)
        }
    }
}

@Composable
private fun SummaryBar(viewModel: SyncViewModel, ui: DiffUiState, plan: SyncPlan) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val res = appResources()
    val free = plan.targetFreeBytes
    val over = free != null && ui.selectedBytes > free
    val selectedCount = ui.selected.size
    Column(Modifier.fillMaxWidth().background(colors.chrome)) {
        HorizontalRule()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.gapL),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = res.getString(R.string.ui_summary_selected, selectedCount, formatBytes(ui.selectedBytes)),
                    style = DeskTheme.type.bodyStrong,
                    color = colors.text,
                )
                Text(
                    text = res.getString(
                        R.string.ui_summary_free,
                        free?.let { formatBytes(it) } ?: res.getString(R.string.ui_unknown),
                        res.fileSystemLabel(plan.targetFileSystem),
                    ),
                    style = DeskTheme.type.label,
                    color = if (over) colors.danger else colors.textSecondary,
                )
                if (over) {
                    Text(stringResource(R.string.ui_summary_over), style = DeskTheme.type.labelSmall, color = colors.danger)
                }
            }
            TooltipArea(
                text = stringResource(R.string.ui_action_transfer_tip),
                shortcut = AppShortcuts.label(AppShortcuts.TRANSFER, res),
            ) {
                DeskButton(
                    text = res.getString(R.string.ui_action_transfer_n, selectedCount),
                    onClick = { viewModel.startTransfer() },
                    icon = WalkmanIcons.Transfer,
                    primary = true,
                    enabled = selectedCount > 0,
                )
            }
        }
    }
}
