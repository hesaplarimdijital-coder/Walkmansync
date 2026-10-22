package com.walkmansync.ui

import android.content.res.Resources
import androidx.compose.ui.input.key.Key
import com.walkmansync.R
import desktopkit.input.CommonShortcuts
import desktopkit.input.KeyChord
import desktopkit.input.Shortcut
import desktopkit.input.ShortcutGroup
import desktopkit.input.ShortcutRegistry
import desktopkit.input.ShortcutScope

/** Uygulamanın klavye kısayolları: menü çubuğu, ipuçları ve Meta+/ listesi aynı tablodan beslenir. */
object AppShortcuts {

    const val SCAN = "ws.scan"
    const val TRANSFER = "ws.transfer"
    const val PAUSE_RESUME = "ws.pause_resume"
    const val CANCEL = "ws.cancel"
    const val REFRESH = "ws.refresh"
    const val GO_CARDS = "ws.go_cards"
    const val GO_DIFF = "ws.go_diff"
    const val GO_TRANSFER = "ws.go_transfer"

    /** Space ve Esc yalnızca aktarım ekranında ve aktarım sürerken geçerlidir (metin alanlarını bozmaz). */
    val TransferScope = ShortcutScope("transfer")

    val SyncGroup = ShortcutGroup("sync", R.string.ui_menu_sync)

    val registry = ShortcutRegistry(
        groups = listOf(SyncGroup, CommonShortcuts.ViewGroup, CommonShortcuts.HelpGroup),
        shortcuts = listOf(
            Shortcut(SCAN, SyncGroup, R.string.ui_sc_scan, KeyChord(Key.R, ctrl = true)),
            Shortcut(TRANSFER, SyncGroup, R.string.ui_sc_transfer, KeyChord(Key.Enter, ctrl = true)),
            Shortcut(PAUSE_RESUME, SyncGroup, R.string.ui_sc_pause_resume, KeyChord(Key.Spacebar), TransferScope),
            Shortcut(CANCEL, SyncGroup, R.string.ui_sc_cancel, KeyChord(Key.Escape), TransferScope),
            Shortcut(REFRESH, SyncGroup, R.string.ui_sc_refresh, KeyChord(Key.F5)),
            Shortcut(GO_CARDS, CommonShortcuts.ViewGroup, R.string.ui_nav_cards, KeyChord(Key.One, ctrl = true)),
            Shortcut(GO_DIFF, CommonShortcuts.ViewGroup, R.string.ui_nav_new_music, KeyChord(Key.Two, ctrl = true)),
            Shortcut(GO_TRANSFER, CommonShortcuts.ViewGroup, R.string.ui_nav_transfer, KeyChord(Key.Three, ctrl = true)),
            CommonShortcuts.Settings,
            CommonShortcuts.ToggleSidebar,
            CommonShortcuts.ShortcutList,
        ),
    )

    /** "Ctrl+R" gibi kısayol metni (ipuçları için). */
    fun label(id: String, resources: Resources): String? = registry.chordLabel(id, resources)
}
