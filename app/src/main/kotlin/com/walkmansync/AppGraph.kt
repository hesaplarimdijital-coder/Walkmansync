// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync

import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.Logger
import com.walkmansync.storage.VolumeRegistry
import com.walkmansync.sync.SyncController
import kotlinx.coroutines.CoroutineScope

/**
 * Süreç ömürlü nesne grafiği (manuel DI). Uygulama (B1): `com.walkmansync.AppContainer(app: WalkmanSyncApp) : AppGraph`.
 *
 * AppContainer kurulum sırası:
 *  1. dispatchers = AppDispatchers.Default, clock = Clock.System, logger = AndroidLogger.
 *  2. appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).
 *  3. domain = DomainModules.create(MediaMetadataTagFallback(app, logger), dispatchers, clock, logger)  // B2 sınıfları
 *  4. engine = EngineModules.create(domain, dispatchers, clock, logger)                                // B1
 *  5. volumeRegistry = SafVolumeRegistry(app, dispatchers, clock, logger, appScope)
 *  6. coordinator = engine.createCoordinator(volumeRegistry.snapshot, volumeRegistry, appScope)
 *  7. syncController = DefaultSyncController(app, coordinator, volumeRegistry)
 * Kurulum ucuz olmalı (G/Ç yok); ilk tarama/registry yenilemesi IO'da tembel başlar.
 */
interface AppGraph {
    val dispatchers: AppDispatchers
    val clock: Clock
    val logger: Logger
    val appScope: CoroutineScope
    val volumeRegistry: VolumeRegistry
    val syncController: SyncController
}
