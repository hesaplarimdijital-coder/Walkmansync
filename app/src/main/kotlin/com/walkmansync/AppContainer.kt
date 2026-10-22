package com.walkmansync

import com.walkmansync.core.domain.DomainModules
import com.walkmansync.core.engine.EngineModules
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.Logger
import com.walkmansync.media.MediaMetadataTagFallback
import com.walkmansync.platform.AndroidLogger
import com.walkmansync.storage.SafVolumeRegistry
import com.walkmansync.storage.VolumeRegistry
import com.walkmansync.sync.DefaultSyncController
import com.walkmansync.sync.SyncController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Süreç ömürlü manuel DI kökü (sıra: bkz. [AppGraph]). Kurulum ucuzdur: G/Ç yapmaz; registry'nin ilk
 * yenilemesi kendi IO coroutine'inde başlar.
 */
class AppContainer(app: WalkmanSyncApp) : AppGraph {
    override val dispatchers: AppDispatchers = AppDispatchers.Default
    override val clock: Clock = Clock.System
    override val logger: Logger = AndroidLogger
    override val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val domain = DomainModules.create(MediaMetadataTagFallback(app, logger), dispatchers, clock, logger)
    private val engine = EngineModules.create(domain, dispatchers, clock, logger)

    override val volumeRegistry: VolumeRegistry = SafVolumeRegistry(app, dispatchers, clock, logger, appScope)

    private val coordinator = engine.createCoordinator(volumeRegistry.snapshot, volumeRegistry, appScope)

    override val syncController: SyncController = DefaultSyncController(app, coordinator, volumeRegistry)
}
