package com.walkmansync.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.walkmansync.R
import com.walkmansync.core.model.SyncState
import desktopkit.DesktopKitRoot
import desktopkit.components.DeskButton
import desktopkit.components.DeskIcons
import desktopkit.components.HorizontalRule
import desktopkit.input.CommonShortcuts
import desktopkit.input.ShortcutScope
import desktopkit.screens.PageHeader
import desktopkit.settings.AppearanceSection
import desktopkit.settings.DeskPreferences
import desktopkit.settings.InputSection
import desktopkit.settings.ShortcutListSection
import desktopkit.settings.rememberDeskPreferences
import desktopkit.shell.DesktopShell
import desktopkit.shell.NavItem
import desktopkit.shell.NavSection
import desktopkit.shell.ShellNavigator
import desktopkit.shell.ShellSpec
import desktopkit.shell.WidthClass
import desktopkit.theme.DeskTheme

/** Kabuk rotaları. */
object Routes {
    const val CARDS = "cards"
    const val DIFF = "diff"
    const val TRANSFER = "transfer"
    const val SETTINGS = "settings"
}

/**
 * Compose ↔ etkinlik köprüsü: klavye kısayolları `Activity.dispatchKeyEvent` içinde eşleşir (odak
 * nerede olursa olsun) ve burada çalıştırılır.
 */
interface ShortcutHost {
    /** Kısayolu çalıştırır; işlendiyse true (tuş tüketilir). */
    fun perform(id: String): Boolean

    /** Şu an geçerli kısayol kapsamları. */
    fun activeScopes(): Set<ShortcutScope>
}

/**
 * Uygulama kökü: tema + yoğunluk (DesktopKitRoot), gezinme kabuğu (DesktopShell), üç ekran ve ayarlar.
 * [onHostChanged] kısayol köprüsünü etkinliğe bildirir (kompozisyondan ayrılınca null).
 */
@Composable
fun WalkmanSyncRoot(viewModel: SyncViewModel, onHostChanged: (ShortcutHost?) -> Unit) {
    val prefs = rememberDeskPreferences()
    DesktopKitRoot(prefs) { widthClass ->
        AppShell(viewModel = viewModel, prefs = prefs, widthClass = widthClass, onHostChanged = onHostChanged)
    }
}

private enum class Phase { Idle, Scanning, Plan, Running, Finished, ScanFailed }

private fun SyncState.phase(): Phase = when (this) {
    is SyncState.Idle, is SyncState.AwaitingVolumes -> Phase.Idle
    is SyncState.Scanning -> Phase.Scanning
    is SyncState.PlanReady -> Phase.Plan
    is SyncState.Transferring, is SyncState.Paused -> Phase.Running
    is SyncState.Completed -> Phase.Finished
    is SyncState.Failed -> if (summary != null) Phase.Finished else Phase.ScanFailed
}

@Composable
private fun AppShell(viewModel: SyncViewModel, prefs: DeskPreferences, widthClass: WidthClass, onHostChanged: (ShortcutHost?) -> Unit) {
    val resources = appResources()
    val navController = rememberNavController()
    val navigator = remember(navController) { ShellNavigator(navController) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val diff by viewModel.diff.collectAsStateWithLifecycle()
    val cancelDialog by viewModel.cancelDialog.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Routes.CARDS
    val routeState = rememberUpdatedState(route)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onPickResult(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
    }

    // Durum değişince ilgili ekrana geç (plan hazır → fark; aktarım/sonuç → aktarım; boşta → kartlar).
    var handledFirst by rememberSaveable { mutableStateOf(false) }
    val phase = state.phase()
    LaunchedEffect(phase) {
        val firstRun = !handledFirst
        handledFirst = true
        if (firstRun && phase == Phase.Idle) return@LaunchedEffect
        when (phase) {
            Phase.Plan -> navigator.navigate(Routes.DIFF)
            Phase.Running, Phase.Finished -> navigator.navigate(Routes.TRANSFER)
            Phase.Idle, Phase.ScanFailed -> navigator.navigate(Routes.CARDS)
            Phase.Scanning -> Unit
        }
    }

    val host = remember(navigator, prefs, viewModel) {
        object : ShortcutHost {
            override fun perform(id: String): Boolean {
                val current = viewModel.state.value
                return when (id) {
                    AppShortcuts.SCAN -> {
                        if (current.canScan()) viewModel.scan()
                        true
                    }
                    AppShortcuts.TRANSFER -> viewModel.startTransfer()
                    AppShortcuts.PAUSE_RESUME -> {
                        viewModel.togglePause()
                        true
                    }
                    AppShortcuts.CANCEL -> {
                        viewModel.requestCancel()
                        true
                    }
                    AppShortcuts.REFRESH -> {
                        viewModel.refreshVolumes()
                        true
                    }
                    AppShortcuts.GO_CARDS -> {
                        navigator.navigate(Routes.CARDS)
                        true
                    }
                    AppShortcuts.GO_DIFF -> {
                        navigator.navigate(Routes.DIFF)
                        true
                    }
                    AppShortcuts.GO_TRANSFER -> {
                        navigator.navigate(Routes.TRANSFER)
                        true
                    }
                    CommonShortcuts.SETTINGS, CommonShortcuts.SHORTCUT_LIST -> {
                        navigator.navigate(Routes.SETTINGS)
                        true
                    }
                    CommonShortcuts.TOGGLE_SIDEBAR -> {
                        prefs.updateSidebarCollapsed(!prefs.sidebarCollapsed)
                        true
                    }
                    else -> false
                }
            }

            override fun activeScopes(): Set<ShortcutScope> {
                val running = viewModel.state.value.let { it is SyncState.Transferring || it is SyncState.Paused }
                return if (running && routeState.value == Routes.TRANSFER) {
                    setOf(ShortcutScope.Global, AppShortcuts.TransferScope)
                } else {
                    setOf(ShortcutScope.Global)
                }
            }
        }
    }
    DisposableEffect(host) {
        onHostChanged(host)
        onDispose { onHostChanged(null) }
    }

    val cardsLabel = stringResource(R.string.ui_nav_cards)
    val diffLabel = stringResource(R.string.ui_nav_new_music)
    val diffShort = stringResource(R.string.ui_nav_new_music_short)
    val transferLabel = stringResource(R.string.ui_nav_transfer)
    val settingsLabel = stringResource(R.string.ui_nav_settings)
    val appTitle = stringResource(R.string.app_name)
    val spec = remember(cardsLabel, diffLabel, diffShort, transferLabel, settingsLabel, appTitle) {
        ShellSpec(
            appTitle = appTitle,
            sections = listOf(
                NavSection(
                    title = null,
                    items = listOf(
                        NavItem(Routes.CARDS, cardsLabel, WalkmanIcons.SdCard),
                        NavItem(Routes.DIFF, diffLabel, WalkmanIcons.Sync, shortLabel = diffShort),
                        NavItem(Routes.TRANSFER, transferLabel, WalkmanIcons.Transfer),
                    ),
                ),
            ),
            footer = NavItem(Routes.SETTINGS, settingsLabel, DeskIcons.Settings),
            bottomBar = listOf(Routes.CARDS, Routes.DIFF, Routes.TRANSFER, Routes.SETTINGS),
            rail = listOf(Routes.CARDS, Routes.DIFF, Routes.TRANSFER),
            startRoute = Routes.CARDS,
        )
    }
    val menus = remember(resources, host, viewModel) {
        AppShortcuts.registry.menus(
            resources = resources,
            perform = { id -> host.perform(id) },
            enabled = { id -> isShortcutEnabled(id, viewModel) },
        )
    }

    val current = state
    val badges: Map<String, String> = when (current) {
        is SyncState.PlanReady -> if (diff.transferCount > 0) mapOf(Routes.DIFF to diff.transferCount.toString()) else emptyMap()
        is SyncState.Transferring -> mapOf(Routes.TRANSFER to current.progress.remainingItems.toString())
        is SyncState.Paused -> mapOf(Routes.TRANSFER to current.progress.remainingItems.toString())
        else -> emptyMap()
    }

    DesktopShell(
        spec = spec,
        widthClass = widthClass,
        navController = navController,
        sidebarCollapsed = prefs.sidebarCollapsed,
        onSidebarCollapsedChange = prefs::updateSidebarCollapsed,
        badges = badges,
        menus = menus,
        bottomBar = {
            BusyStrip(state = current, route = route, onOpen = { target -> navigator.navigate(target) })
        },
    ) { currentRoute, _ ->
        when (currentRoute) {
            Routes.CARDS -> PairingScreen(viewModel = viewModel, onPickFolder = { intent -> picker.launch(intent) })
            Routes.DIFF -> DiffPreviewScreen(viewModel)
            Routes.TRANSFER -> TransferScreen(viewModel)
            else -> SettingsScreen(prefs)
        }
    }

    if (cancelDialog && (current is SyncState.Transferring || current is SyncState.Paused)) {
        DeskDialog(
            title = stringResource(R.string.ui_cancel_title),
            text = stringResource(R.string.ui_cancel_text),
            confirmLabel = stringResource(R.string.ui_cancel_confirm),
            dismissLabel = stringResource(R.string.ui_cancel_keep),
            onConfirm = viewModel::confirmCancel,
            onDismiss = viewModel::dismissCancelDialog,
        )
    }
}

/** Menü maddelerinin etkinliği; her menü açılışında güncel durumdan okunur. */
private fun isShortcutEnabled(id: String, viewModel: SyncViewModel): Boolean {
    val state = viewModel.state.value
    return when (id) {
        AppShortcuts.SCAN -> state.canScan()
        AppShortcuts.TRANSFER -> state is SyncState.PlanReady && viewModel.diff.value.selected.isNotEmpty()
        AppShortcuts.PAUSE_RESUME -> state is SyncState.Transferring || state is SyncState.Paused
        AppShortcuts.CANCEL -> state.isBusy
        else -> true
    }
}

/** Başka ekrandayken süren tarama/aktarımı gösteren alt şerit. */
@Composable
private fun BusyStrip(state: SyncState, route: String, onOpen: (String) -> Unit) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val res = appResources()
    val text: String
    val fraction: Float?
    val target: String
    val paused: Boolean
    when (state) {
        is SyncState.Transferring -> {
            text = res.getString(R.string.ui_strip_transferring, formatPercent(state.progress.fraction), state.progress.remainingItems)
            fraction = state.progress.fraction
            target = Routes.TRANSFER
            paused = false
        }
        is SyncState.Paused -> {
            text = res.getString(R.string.ui_strip_paused, formatPercent(state.progress.fraction), state.progress.remainingItems)
            fraction = state.progress.fraction
            target = Routes.TRANSFER
            paused = true
        }
        is SyncState.Scanning -> {
            text = res.scanPhaseText(state.progress)
            fraction = state.progress.fraction
            target = Routes.CARDS
            paused = false
        }
        else -> return
    }
    if (route == target || (state is SyncState.Scanning && route == Routes.DIFF)) return
    Column(Modifier.fillMaxWidth().background(colors.chrome)) {
        HorizontalRule()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.pagePadding, vertical = dimens.gapS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.gapM),
        ) {
            Column(Modifier.weight(1f)) {
                Text(text, style = DeskTheme.type.label, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(dimens.gapXs))
                DeskProgressBar(fraction, color = if (paused) colors.warning else colors.accent)
            }
            DeskButton(text = stringResource(R.string.ui_action_open), onClick = { onOpen(target) })
        }
    }
}

@Composable
private fun SettingsScreen(prefs: DeskPreferences) {
    val transferScope = stringResource(R.string.ui_scope_transfer)
    LazyColumn(Modifier.fillMaxSize()) {
        item { PageHeader(stringResource(R.string.ui_nav_settings)) }
        item { AppearanceSection(prefs) }
        item { InputSection(prefs) }
        item {
            ShortcutListSection(
                registry = AppShortcuts.registry,
                scopeLabel = { scope -> if (scope == AppShortcuts.TransferScope) transferScope else null },
            )
        }
    }
}
