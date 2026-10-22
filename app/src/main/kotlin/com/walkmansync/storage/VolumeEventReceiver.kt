package com.walkmansync.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.walkmansync.WalkmanSyncApp

/**
 * Kart takma/çıkarma ve USB okuyucu olayları. Olayın kendisine güvenilmez (kısıtlı/gecikmeli olabilir);
 * yalnızca kayıt defterini yeniden okutur, asıl doğrulama her açılışta ve her G/Ç hatasında yapılır.
 * Hem manifestte (MEDIA_*) hem de [SafVolumeRegistry] tarafından dinamik olarak (MEDIA_* + USB_*) kaydedilir.
 */
class VolumeEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val registry = WalkmanSyncApp.graph(context).volumeRegistry
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_DEVICE_DETACHED -> registry.refresh(withRetries = true)
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL -> registry.refresh()
            else -> Unit
        }
    }
}
