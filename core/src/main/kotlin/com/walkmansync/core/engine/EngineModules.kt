package com.walkmansync.core.engine

import com.walkmansync.core.index.IndexStore
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.DomainModule
import com.walkmansync.core.runtime.EngineModule
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.scan.TreeScanner
import com.walkmansync.core.transfer.TransferRunner
import com.walkmansync.core.workflow.ScanWorkflow
import com.walkmansync.core.workflow.SyncCoordinator
import com.walkmansync.core.workflow.TreeOpener
import com.walkmansync.core.workflow.VolumeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Motor katmanının fabrikası (bkz. runtime/Modules.kt). */
object EngineModules {
    fun create(domain: DomainModule, dispatchers: AppDispatchers, clock: Clock, logger: Logger): EngineModule =
        DefaultEngineModule(domain, dispatchers, clock, logger)
}

private class DefaultEngineModule(
    domain: DomainModule,
    dispatchers: AppDispatchers,
    private val clock: Clock,
    private val logger: Logger,
) : EngineModule {
    override val treeScanner: TreeScanner = DefaultTreeScanner(dispatchers, logger, clock)
    override val indexStore: IndexStore = TargetIndexStore(domain.indexCodec, logger, dispatchers)
    override val scanWorkflow: ScanWorkflow =
        DefaultScanWorkflow(indexStore, treeScanner, domain.deltaEngine, clock, dispatchers, logger)
    override val transferRunner: TransferRunner = AtomicTransferRunner(indexStore, clock, dispatchers, logger)

    override fun createCoordinator(
        volumes: StateFlow<VolumeSnapshot>,
        opener: TreeOpener,
        scope: CoroutineScope,
    ): SyncCoordinator =
        DefaultSyncCoordinator(volumes, opener, scanWorkflow, transferRunner, clock, logger, scope)
}
