package com.walkmansync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkmansync.R
import com.walkmansync.core.model.ItemFailure
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncState
import com.walkmansync.core.model.TransferOutcome
import com.walkmansync.core.model.TransferProgress
import com.walkmansync.core.model.TransferSummary
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
import desktopkit.components.TooltipArea
import desktopkit.screens.MessageScreen
import desktopkit.screens.PageHeader
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.theme.DeskTheme

/**
 * Ekran 3: aktarım ilerlemesi ve sonuç özeti. Aktarım sürerken kalan öğe, hız, ETA, geçerli dosya ve
 * Duraklat/Sürdür/İptal; bitince özet ve başarısız öğeler.
 */
@Composable
fun TransferScreen(viewModel: SyncViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state
    when (current) {
        is SyncState.Transferring -> RunningView(viewModel, current.progress, paused = false, modifier = modifier)
        is SyncState.Paused -> RunningView(viewModel, current.progress, paused = true, modifier = modifier)
        is SyncState.Completed -> SummaryView(viewModel, current.summary, error = null, modifier = modifier)
        is SyncState.Failed -> {
            val summary = current.summary
            if (summary != null) {
                SummaryView(viewModel, summary, error = current.error, modifier = modifier)
            } else {
                FailedView(viewModel, current.error, modifier)
            }
        }
        else -> MessageScreen(
            title = stringResource(R.string.ui_transfer_empty_title),
            icon = WalkmanIcons.Transfer,
            modifier = modifier,
            pageTitle = stringResource(R.string.ui_nav_transfer),
            description = stringResource(R.string.ui_transfer_empty_desc),
        )
    }
}

@Composable
private fun RunningView(viewModel: SyncViewModel, progress: TransferProgress, paused: Boolean, modifier: Modifier) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val res = appResources()
    val compact = currentWidthClass() == WidthClass.Compact

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.ui_nav_transfer),
            subtitle = stringResource(if (paused) R.string.ui_transfer_paused else R.string.ui_transfer_running),
            actions = {
                TooltipArea(
                    text = stringResource(if (paused) R.string.ui_action_resume else R.string.ui_action_pause),
                    shortcut = AppShortcuts.label(AppShortcuts.PAUSE_RESUME, res),
                ) {
                    DeskButton(
                        text = stringResource(if (paused) R.string.ui_action_resume else R.string.ui_action_pause),
                        onClick = viewModel::togglePause,
                        icon = if (paused) DeskIcons.Play else DeskIcons.Pause,
                        primary = true,
                    )
                }
                TooltipArea(
                    text = stringResource(R.string.ui_action_cancel_transfer),
                    shortcut = AppShortcuts.label(AppShortcuts.CANCEL, res),
                ) {
                    DeskButton(
                        text = stringResource(R.string.ui_action_cancel),
                        onClick = viewModel::requestCancel,
                        icon = DeskIcons.Close,
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimens.pagePadding)
                .padding(bottom = dimens.gapL),
            verticalArrangement = Arrangement.spacedBy(dimens.gapL),
        ) {
            MessageBanner(viewModel)
            SurfaceCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = formatPercent(progress.fraction),
                        style = DeskTheme.type.display,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = res.getString(R.string.ui_bytes_of, formatBytes(progress.completedBytes), formatBytes(progress.totalBytes)),
                        style = DeskTheme.type.body,
                        color = colors.textSecondary,
                    )
                }
                DeskProgressBar(progress.fraction, color = if (paused) colors.warning else colors.accent)
            }

            val tiles = listOf(
                stringResource(R.string.ui_stat_remaining) to res.fileCount(progress.remainingItems),
                stringResource(R.string.ui_stat_speed) to formatRate(progress.bytesPerSecond),
                stringResource(R.string.ui_stat_eta) to if (paused) DASH else formatEta(progress.etaMillis),
                stringResource(R.string.ui_stat_done) to res.getString(R.string.ui_stat_done_value, progress.completedItems, progress.totalItems),
            )
            if (compact) {
                tiles.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.gapL)) {
                        pair.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.gapL)) {
                    tiles.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                }
            }

            CurrentFileCard(progress)

            if (progress.failedItems > 0) {
                StatusBanner(
                    tone = BannerTone.Warning,
                    text = res.getString(R.string.ui_transfer_failed_so_far, progress.failedItems),
                )
            }
            if (paused) {
                StatusBanner(tone = BannerTone.Info, text = stringResource(R.string.ui_transfer_paused_hint))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    SurfaceCard(modifier) {
        Text(label, style = DeskTheme.type.labelSmall, color = DeskTheme.colors.textTertiary, maxLines = 1)
        Text(value, style = DeskTheme.type.title, color = DeskTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CurrentFileCard(progress: TransferProgress) {
    val colors = DeskTheme.colors
    val res = appResources()
    val item = progress.currentItem
    SurfaceCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ui_current_file), style = DeskTheme.type.labelSmall, color = colors.textTertiary)
        if (item == null) {
            Text(stringResource(R.string.ui_current_none), style = DeskTheme.type.body, color = colors.textSecondary)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DeskTheme.dimens.gapS)) {
                FormatBadge(item.format)
                Text(
                    text = item.targetName,
                    style = DeskTheme.type.bodyStrong,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(formatBytes(item.size), style = DeskTheme.type.mono, color = colors.textSecondary, maxLines = 1)
            }
            Text(item.targetDir, style = DeskTheme.type.mono, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            DeskProgressBar(if (item.size > 0) progress.currentItemBytes.toFloat() / item.size else null)
            Text(
                text = res.getString(R.string.ui_bytes_of, formatBytes(progress.currentItemBytes), formatBytes(item.size)),
                style = DeskTheme.type.labelSmall,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun SummaryView(viewModel: SyncViewModel, summary: TransferSummary, error: SyncError?, modifier: Modifier) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val res = appResources()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fatal = summary.fatalError ?: error
    val status = when (summary.outcome) {
        TransferOutcome.COMPLETED -> DiffStatus.Done
        TransferOutcome.COMPLETED_WITH_ERRORS -> DiffStatus.Conflict
        TransferOutcome.CANCELLED -> DiffStatus.Skipped
        TransferOutcome.ABORTED -> DiffStatus.Error
    }
    val elapsed = summary.elapsedMillis
    val averageSpeed = if (elapsed > 0) summary.copiedBytes * 1000L / elapsed else 0L

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.ui_nav_transfer),
            subtitle = res.outcomeTitle(summary.outcome),
            actions = {
                DeskButton(text = stringResource(R.string.ui_action_done), onClick = viewModel::dismissResult)
                TooltipArea(
                    text = stringResource(R.string.ui_action_scan_tip),
                    shortcut = AppShortcuts.label(AppShortcuts.SCAN, res),
                ) {
                    DeskButton(
                        text = stringResource(R.string.ui_action_scan_again),
                        onClick = viewModel::scan,
                        icon = WalkmanIcons.Sync,
                        primary = true,
                        enabled = state.canScan(),
                    )
                }
            },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = dimens.pagePadding, end = dimens.pagePadding, bottom = dimens.gapL),
            verticalArrangement = Arrangement.spacedBy(dimens.gapL),
        ) {
            item(key = "message") { MessageBanner(viewModel) }
            item(key = "headline") {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dimens.gapM)) {
                        StatusGlyph(status, description = null, size = dimens.iconLarge)
                        Column(Modifier.weight(1f)) {
                            Text(res.outcomeTitle(summary.outcome), style = DeskTheme.type.title, color = colors.text)
                            Text(
                                text = res.getString(
                                    R.string.ui_summary_headline,
                                    summary.copiedItems,
                                    summary.totalItems,
                                    formatBytes(summary.copiedBytes),
                                ),
                                style = DeskTheme.type.body,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
            if (fatal != null && fatal !is SyncError.Cancelled) {
                item(key = "fatal") { StatusBanner(BannerTone.Error, res.errorText(fatal)) }
            }
            if (!summary.indexSaved) {
                item(key = "index") { StatusBanner(BannerTone.Warning, stringResource(R.string.ui_summary_index_not_saved)) }
            }
            item(key = "stats") {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    KeyValueRow(stringResource(R.string.ui_summary_copied), res.getString(R.string.ui_stat_done_value, summary.copiedItems, summary.totalItems))
                    KeyValueRow(stringResource(R.string.ui_summary_data), formatBytes(summary.copiedBytes))
                    KeyValueRow(stringResource(R.string.ui_summary_time), formatDuration(elapsed))
                    KeyValueRow(stringResource(R.string.ui_summary_speed), formatRate(averageSpeed))
                    KeyValueRow(stringResource(R.string.ui_summary_failed), summary.failures.size.toString())
                    KeyValueRow(stringResource(R.string.ui_summary_not_attempted), summary.notAttempted.toString())
                }
            }
            if (summary.failures.isNotEmpty()) {
                item(key = "failures_title") {
                    Text(
                        text = res.getString(R.string.ui_failures_title, summary.failures.size),
                        style = DeskTheme.type.title,
                        color = colors.text,
                    )
                }
                items(summary.failures, key = { "f:" + it.itemId }) { failure -> FailureRow(failure) }
            }
        }
    }
}

@Composable
private fun FailureRow(failure: ItemFailure) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val res = appResources()
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(dimens.gapM)) {
            StatusGlyph(DiffStatus.Error, description = null, size = dimens.icon)
            Column(Modifier.weight(1f)) {
                Text(failure.targetPath, style = DeskTheme.type.bodyStrong, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(res.errorText(failure.error), style = DeskTheme.type.body, color = colors.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FailedView(viewModel: SyncViewModel, error: SyncError, modifier: Modifier) {
    val dimens = DeskTheme.dimens
    val res = appResources()
    Column(modifier.fillMaxSize()) {
        PageHeader(title = stringResource(R.string.ui_nav_transfer))
        Column(Modifier.padding(horizontal = dimens.pagePadding)) {
            StatusBanner(
                tone = BannerTone.Error,
                text = res.errorText(error),
                actionLabel = stringResource(R.string.ui_action_dismiss),
                onAction = viewModel::dismissResult,
            )
        }
    }
}
