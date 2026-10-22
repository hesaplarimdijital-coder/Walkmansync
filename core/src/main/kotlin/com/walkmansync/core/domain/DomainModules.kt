package com.walkmansync.core.domain

import com.walkmansync.core.delta.DeltaEngine
import com.walkmansync.core.index.IndexCodec
import com.walkmansync.core.index.json.JsonIndexCodec
import com.walkmansync.core.path.FileNameSanitizer
import com.walkmansync.core.path.PathPlanner
import com.walkmansync.core.path.impl.DefaultPathPlanner
import com.walkmansync.core.path.impl.FatFileNameSanitizer
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.DomainModule
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.tags.TagFallback
import com.walkmansync.core.tags.TagReader
import com.walkmansync.core.tags.TagService
import com.walkmansync.core.tags.parsers.CompositeTagReader

/** Alan katmanı fabrikası (bkz. [DomainModule]). Tüm örnekler durumsuzdur ve thread-safe'tir. */
object DomainModules {
    fun create(fallback: TagFallback, dispatchers: AppDispatchers, clock: Clock, logger: Logger): DomainModule {
        val nameSanitizer = FatFileNameSanitizer()
        val planner = DefaultPathPlanner(nameSanitizer)
        val tagParser = CompositeTagReader()
        val tags = DefaultTagService(tagParser, fallback, dispatchers, logger)
        val delta = DefaultDeltaEngine(tags, planner, dispatchers, clock, logger)
        val codec = JsonIndexCodec()
        return object : DomainModule {
            override val sanitizer: FileNameSanitizer = nameSanitizer
            override val pathPlanner: PathPlanner = planner
            override val tagReader: TagReader = tagParser
            override val tagService: TagService = tags
            override val deltaEngine: DeltaEngine = delta
            override val indexCodec: IndexCodec = codec
        }
    }
}
