// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.runtime

import com.walkmansync.core.delta.DeltaEngine
import com.walkmansync.core.index.IndexCodec
import com.walkmansync.core.index.IndexStore
import com.walkmansync.core.path.FileNameSanitizer
import com.walkmansync.core.path.PathPlanner
import com.walkmansync.core.scan.TreeScanner
import com.walkmansync.core.tags.TagReader
import com.walkmansync.core.tags.TagService
import com.walkmansync.core.transfer.TransferRunner
import com.walkmansync.core.workflow.ScanWorkflow
import com.walkmansync.core.workflow.SyncCoordinator
import com.walkmansync.core.workflow.TreeOpener
import com.walkmansync.core.workflow.VolumeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Basit manuel DI (Hilt YOK). İki ajan birbirinin sınıf adlarını bilmeden yalnızca bu iki fabrika
 * imzasıyla bağlanır:
 *
 *  - B2 yazar: `com.walkmansync.core.domain.DomainModules`
 *    ```
 *    object DomainModules {
 *        fun create(fallback: TagFallback, dispatchers: AppDispatchers, clock: Clock, logger: Logger): DomainModule
 *    }
 *    ```
 *  - B1 yazar: `com.walkmansync.core.engine.EngineModules`
 *    ```
 *    object EngineModules {
 *        fun create(domain: DomainModule, dispatchers: AppDispatchers, clock: Clock, logger: Logger): EngineModule
 *    }
 *    ```
 * `AppContainer` (app, B1) ikisini çağırır. Tüm örnekler süreç boyunca tekildir.
 */
interface DomainModule {
    val sanitizer: FileNameSanitizer
    val pathPlanner: PathPlanner
    val tagReader: TagReader
    val tagService: TagService
    val deltaEngine: DeltaEngine
    val indexCodec: IndexCodec
}

interface EngineModule {
    val treeScanner: TreeScanner
    val indexStore: IndexStore
    val scanWorkflow: ScanWorkflow
    val transferRunner: TransferRunner

    /** Süreç başına BİR kez çağrılır (AppContainer). [scope] uygulama ömürlü olmalı. */
    fun createCoordinator(
        volumes: StateFlow<VolumeSnapshot>,
        opener: TreeOpener,
        scope: CoroutineScope,
    ): SyncCoordinator
}
