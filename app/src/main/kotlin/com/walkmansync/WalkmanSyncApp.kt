// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync

import android.app.Application
import android.content.Context

/**
 * Uygulama sınıfı: tek [AppGraph] örneğini taşır. Erişim:
 *  - Activity/Service/Receiver: `WalkmanSyncApp.graph(context)`
 *  - ViewModel fabrikası: `(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as WalkmanSyncApp).graph`
 */
class WalkmanSyncApp : Application() {

    /** Tembel kurulur; ilk erişim ana thread'den olabilir (AppContainer kurulumu G/Ç yapmaz). */
    val graph: AppGraph by lazy { AppContainer(this) }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as WalkmanSyncApp).graph
    }
}
