package com.walkmansync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.walkmansync.ui.AppShortcuts
import com.walkmansync.ui.ShortcutHost
import com.walkmansync.ui.SyncViewModel
import com.walkmansync.ui.WalkmanSyncRoot

/**
 * Tek etkinlik. Yeniden oluşturma (DeX pencere değişimi) durumu [SyncViewModel] ve kabuk kaydetmeleriyle korunur.
 * Kısayollar `dispatchKeyEvent` içinde, odak nerede olursa olsun eşleşir.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SyncViewModel by viewModels { SyncViewModel.Factory }

    /** Kompozisyonun sağladığı kısayol köprüsü; kompozisyon yokken null. */
    private var shortcutHost: ShortcutHost? = null

    /**
     * Kısayol olarak tüketilen son tuşun kodu. Eşleşme yalnızca KeyDown'da yapılır; aynı tuşun KeyUp'ı odaktaki
     * Compose düğmesine ulaşırsa (Space/Enter tıklamayı KeyUp'ta tetikler) eylem iki kez çalışırdı.
     */
    private var consumedKeyCode = KeyEvent.KEYCODE_UNKNOWN

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Reddedilse de uygulama bildirimsiz çalışır; sonuç kullanılmaz.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLaunchIntent(intent)
        if (savedInstanceState == null) requestNotificationPermission()
        setContent {
            WalkmanSyncRoot(viewModel = viewModel, onHostChanged = { shortcutHost = it })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Kart takılıp çıkarılmış olabilir (arka plandayken); listeyi tazele.
        viewModel.refreshVolumes()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == consumedKeyCode) {
            consumedKeyCode = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        val host = shortcutHost
        if (host != null) {
            val shortcut = AppShortcuts.registry.matchNative(event, host.activeScopes(), allowPlainKeys = true)
            if (shortcut != null) {
                // Tuş basılı tutulunca tekrar eden olaylar tüketilir ama yeniden çalıştırılmaz.
                if (event.repeatCount > 0) return true
                if (host.perform(shortcut.id)) {
                    consumedKeyCode = event.keyCode
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Meta+/ (ya da sistemin kısayol ekranı) aynı tabloyu listeler. */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        data.addAll(AppShortcuts.registry.keyboardShortcutGroups(resources))
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) viewModel.onUsbDeviceAttached()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
