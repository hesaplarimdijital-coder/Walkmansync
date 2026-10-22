package com.walkmansync.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkmansync.R
import com.walkmansync.core.model.RemovableVolume
import com.walkmansync.core.model.ScanProgress
import com.walkmansync.core.model.SyncState
import com.walkmansync.core.model.VolumeBinding
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumeKind
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.model.VolumeStatus
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
import desktopkit.components.TooltipArea
import desktopkit.screens.PageHeader
import desktopkit.shell.WidthClass
import desktopkit.shell.currentWidthClass
import desktopkit.theme.DeskTheme
import kotlinx.coroutines.delay

private const val MESSAGE_MILLIS = 8_000L

/**
 * Ekran 1: kaynak / hedef SD kart eşleştirme. Her kart için durum, UUID, dosya sistemi, boş alan,
 * klasör seçimi ve uyarılar. Meşgulken (tarama/aktarım) seçim kapalıdır.
 */
@Composable
fun PairingScreen(viewModel: SyncViewModel, onPickFolder: (Intent) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot by viewModel.volumes.collectAsStateWithLifecycle()
    val mounted by viewModel.mountedVolumes.collectAsStateWithLifecycle()
    val targetInfo by viewModel.targetInfo.collectAsStateWithLifecycle()
    val res = appResources()
    val dimens = DeskTheme.dimens
    val current = state
    val busy = current.isBusy
    val wide = currentWidthClass() != WidthClass.Compact

    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = stringResource(R.string.ui_nav_cards),
            subtitle = stringResource(if (snapshot.pair != null) R.string.ui_cards_ready else R.string.ui_cards_choose),
            actions = {
                DeskButton(
                    text = stringResource(R.string.ui_action_refresh),
                    onClick = viewModel::refreshVolumes,
                    icon = DeskIcons.Forward,
                )
                TooltipArea(
                    text = stringResource(R.string.ui_action_scan_tip),
                    shortcut = AppShortcuts.label(AppShortcuts.SCAN, res),
                ) {
                    DeskButton(
                        text = stringResource(R.string.ui_action_scan),
                        onClick = viewModel::scan,
                        icon = WalkmanIcons.Sync,
                        primary = true,
                        enabled = current.canScan(),
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
            if (current is SyncState.Failed) {
                val hint = if (current.recoverable) " " + stringResource(R.string.ui_failed_recoverable_hint) else ""
                StatusBanner(
                    tone = BannerTone.Error,
                    text = res.errorText(current.error) + hint,
                    actionLabel = stringResource(R.string.ui_action_dismiss),
                    onAction = viewModel::dismissResult,
                )
            }
            if (current is SyncState.Scanning) {
                ScanPanel(progress = current.progress, onCancel = viewModel::requestCancel)
            }
            if (busy) {
                StatusBanner(tone = BannerTone.Info, text = stringResource(R.string.ui_cards_locked))
            }

            val pick = { role: VolumeRole, hint: VolumeId?, expected: VolumeId? ->
                onPickFolder(viewModel.createPickerIntent(role, hint, expected))
            }
            val source: @Composable (Modifier) -> Unit = { cardModifier ->
                VolumeCard(
                    role = VolumeRole.SOURCE,
                    status = snapshot.source,
                    mounted = mounted,
                    targetInfo = null,
                    busy = busy,
                    onPick = { hint, expected -> pick(VolumeRole.SOURCE, hint, expected) },
                    onUnbind = { viewModel.unbind(VolumeRole.SOURCE) },
                    modifier = cardModifier,
                )
            }
            val target: @Composable (Modifier) -> Unit = { cardModifier ->
                VolumeCard(
                    role = VolumeRole.TARGET,
                    status = snapshot.target,
                    mounted = mounted,
                    targetInfo = targetInfo,
                    busy = busy,
                    onPick = { hint, expected -> pick(VolumeRole.TARGET, hint, expected) },
                    onUnbind = { viewModel.unbind(VolumeRole.TARGET) },
                    modifier = cardModifier,
                )
            }
            if (wide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dimens.gapL)) {
                    source(Modifier.weight(1f))
                    target(Modifier.weight(1f))
                }
            } else {
                source(Modifier.fillMaxWidth())
                target(Modifier.fillMaxWidth())
            }

            DetectedVolumes(mounted = mounted, snapshot = snapshot.let { listOf(it.source, it.target) })
        }
    }
}

private fun VolumeStatus.bindingOrNull(): VolumeBinding? = when (this) {
    VolumeStatus.Unbound -> null
    is VolumeStatus.NotMounted -> binding
    is VolumeStatus.PermissionMissing -> binding
    is VolumeStatus.ReadOnly -> binding
    is VolumeStatus.Ready -> binding
    is VolumeStatus.Conflict -> binding
}

private fun VolumeStatus.diffStatus(): DiffStatus = when (this) {
    VolumeStatus.Unbound -> DiffStatus.Skipped
    is VolumeStatus.NotMounted -> DiffStatus.Conflict
    is VolumeStatus.PermissionMissing -> DiffStatus.Conflict
    is VolumeStatus.ReadOnly -> DiffStatus.Error
    is VolumeStatus.Ready -> DiffStatus.Done
    is VolumeStatus.Conflict -> DiffStatus.Error
}

private fun VolumeStatus.labelRes(): Int = when (this) {
    VolumeStatus.Unbound -> R.string.ui_status_unbound
    is VolumeStatus.NotMounted -> R.string.ui_status_not_mounted
    is VolumeStatus.PermissionMissing -> R.string.ui_status_permission_missing
    is VolumeStatus.ReadOnly -> R.string.ui_status_read_only
    is VolumeStatus.Ready -> R.string.ui_status_ready
    is VolumeStatus.Conflict -> R.string.ui_status_conflict
}

@Composable
private fun StatusChip(status: VolumeStatus) {
    val kind = status.diffStatus()
    val tint = kind.tint()
    val shape = RoundedCornerShape(DeskTheme.dimens.radiusPill)
    Row(
        modifier = Modifier
            .background(tint.copy(alpha = 0.12f), shape)
            .border(1.dp, tint.copy(alpha = 0.45f), shape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(kind, description = null, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(status.labelRes()), style = DeskTheme.type.labelSmall, color = tint, maxLines = 1)
    }
}

@Composable
private fun VolumeCard(
    role: VolumeRole,
    status: VolumeStatus,
    mounted: List<RemovableVolume>,
    targetInfo: TargetInfo?,
    busy: Boolean,
    onPick: (hint: VolumeId?, expected: VolumeId?) -> Unit,
    onUnbind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val res = appResources()
    val binding = status.bindingOrNull()
    val volume = binding?.let { b -> mounted.firstOrNull { it.id == b.volume } }
    val isTarget = role == VolumeRole.TARGET

    SurfaceCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isTarget) WalkmanIcons.Walkman else WalkmanIcons.SdCard,
                contentDescription = null,
                tint = colors.accentText,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(dimens.gapM))
            Column(Modifier.weight(1f)) {
                Text(res.roleName(role), style = DeskTheme.type.title, color = colors.text, maxLines = 1)
                Text(
                    text = stringResource(if (isTarget) R.string.ui_role_target_hint else R.string.ui_role_source_hint),
                    style = DeskTheme.type.labelSmall,
                    color = colors.textTertiary,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(dimens.gapS))
            StatusChip(status)
        }

        if (binding != null) {
            KeyValueRow(stringResource(R.string.ui_card_name), volume?.label ?: binding.label)
            KeyValueRow(stringResource(R.string.ui_card_uuid), binding.volume.toString(), mono = true)
            KeyValueRow(stringResource(R.string.ui_card_folder), binding.treeDocId, mono = true)
            if (volume != null) {
                KeyValueRow(
                    label = stringResource(R.string.ui_card_reader),
                    value = stringResource(
                        when (volume.kind) {
                            VolumeKind.SD_CARD -> R.string.ui_kind_sd
                            VolumeKind.USB -> R.string.ui_kind_usb
                            VolumeKind.UNKNOWN -> R.string.ui_kind_unknown
                        },
                    ),
                )
                if (isTarget) {
                    KeyValueRow(
                        label = stringResource(R.string.ui_card_access),
                        value = stringResource(if (volume.isWritable) R.string.ui_access_rw else R.string.ui_access_ro),
                        valueColor = if (volume.isWritable) colors.text else colors.danger,
                    )
                }
            }
            if (isTarget) {
                val info = targetInfo?.takeIf { it.volume == binding.volume }
                KeyValueRow(stringResource(R.string.ui_card_filesystem), info?.let { res.fileSystemLabel(it.fileSystem) } ?: DASH)
                KeyValueRow(stringResource(R.string.ui_card_free), info?.freeBytes?.let { formatBytes(it) } ?: DASH)
                if (info == null) {
                    Text(
                        text = stringResource(R.string.ui_card_fs_after_scan),
                        style = DeskTheme.type.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
        }

        when (status) {
            VolumeStatus.Unbound -> StatusBanner(BannerTone.Info, stringResource(R.string.ui_hint_unbound))
            is VolumeStatus.NotMounted ->
                StatusBanner(BannerTone.Warning, stringResource(R.string.ui_hint_not_mounted, status.binding.volume.toString()))
            is VolumeStatus.PermissionMissing -> StatusBanner(BannerTone.Warning, stringResource(R.string.ui_hint_permission_missing))
            is VolumeStatus.Conflict -> StatusBanner(BannerTone.Error, stringResource(R.string.ui_hint_conflict))
            is VolumeStatus.ReadOnly -> StatusBanner(BannerTone.Error, stringResource(R.string.ui_hint_read_only))
            is VolumeStatus.Ready -> Unit
        }

        Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapS), verticalAlignment = Alignment.CenterVertically) {
            if (status is VolumeStatus.PermissionMissing) {
                DeskButton(
                    text = stringResource(R.string.ui_action_grant_again),
                    onClick = { onPick(status.binding.volume, status.binding.volume) },
                    icon = DeskIcons.Folder,
                    primary = true,
                    enabled = !busy,
                )
            }
            DeskButton(
                text = stringResource(R.string.ui_action_select_folder),
                onClick = { onPick(binding?.volume, null) },
                icon = DeskIcons.Folder,
                primary = binding == null,
                enabled = !busy,
            )
            if (binding != null) {
                DeskButton(
                    text = stringResource(R.string.ui_action_remove),
                    onClick = onUnbind,
                    icon = DeskIcons.Close,
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
private fun DetectedVolumes(mounted: List<RemovableVolume>, snapshot: List<VolumeStatus>) {
    val colors = DeskTheme.colors
    val res = appResources()
    val bound = snapshot.mapNotNull { it.bindingOrNull() }
    SurfaceCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ui_detected_title), style = DeskTheme.type.title, color = colors.text)
        if (mounted.isEmpty()) {
            Text(stringResource(R.string.ui_detected_none), style = DeskTheme.type.body, color = colors.textSecondary)
        }
        for (volume in mounted) {
            val role = bound.firstOrNull { it.volume == volume.id }?.role
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (volume.kind == VolumeKind.USB) WalkmanIcons.UsbCDongle else WalkmanIcons.SdCard,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(DeskTheme.dimens.iconLarge),
                )
                Spacer(Modifier.width(DeskTheme.dimens.gapM))
                Column(Modifier.weight(1f)) {
                    Text(volume.label, style = DeskTheme.type.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(volume.id.toString(), style = DeskTheme.type.mono, color = colors.textTertiary, maxLines = 1)
                }
                if (role != null) {
                    Text(res.roleName(role), style = DeskTheme.type.labelSmall, color = colors.accentText, maxLines = 1)
                }
            }
        }
    }
}

/** Tarama ilerleme paneli (kartlar ve fark ekranında ortak). */
@Composable
fun ScanPanel(progress: ScanProgress, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DeskTheme.colors
    val res = appResources()
    SurfaceCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ui_scanning_title),
                style = DeskTheme.type.title,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            DeskButton(text = stringResource(R.string.ui_action_cancel), onClick = onCancel)
        }
        DeskProgressBar(progress.fraction)
        Text(res.scanPhaseText(progress), style = DeskTheme.type.body, color = colors.textSecondary)
        val path = progress.currentPath
        if (path != null) {
            Text(path, style = DeskTheme.type.mono, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Kart bağlama sonucu / komut reddi iletisi; birkaç saniye sonra kendiliğinden kapanır. */
@Composable
fun MessageBanner(viewModel: SyncViewModel, modifier: Modifier = Modifier) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    val res = appResources()
    val current = message ?: return
    LaunchedEffect(current) {
        delay(MESSAGE_MILLIS)
        viewModel.clearMessage()
    }
    val tone: BannerTone
    val text: String
    when (current) {
        is UiMessage.Bound -> {
            tone = BannerTone.Success
            text = res.getString(R.string.ui_bound_ok, res.roleName(current.role), current.label)
        }
        is UiMessage.Rejected -> {
            tone = BannerTone.Error
            text = res.bindRejectionText(current.reason)
        }
        UiMessage.NotAccepted -> {
            tone = BannerTone.Warning
            text = res.getString(R.string.ui_msg_not_accepted)
        }
        UiMessage.Failure -> {
            tone = BannerTone.Error
            text = res.getString(R.string.ui_msg_failure)
        }
    }
    StatusBanner(
        tone = tone,
        text = text,
        modifier = modifier,
        closeLabel = stringResource(R.string.ui_action_dismiss),
        onClose = viewModel::clearMessage,
    )
}
