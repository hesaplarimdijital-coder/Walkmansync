package com.walkmansync.core.domain

import com.walkmansync.core.delta.DeltaEngine
import com.walkmansync.core.delta.DeltaRequest
import com.walkmansync.core.delta.DeltaResult
import com.walkmansync.core.fs.ReadableTree
import com.walkmansync.core.index.ContentKey
import com.walkmansync.core.index.SyncIndex
import com.walkmansync.core.index.TargetKeyEntry
import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.model.CopyReason
import com.walkmansync.core.model.FileEntry
import com.walkmansync.core.model.NodeHandle
import com.walkmansync.core.model.PlanWarning
import com.walkmansync.core.model.ScanPhase
import com.walkmansync.core.model.ScanProgress
import com.walkmansync.core.model.SkipReason
import com.walkmansync.core.model.SkippedItem
import com.walkmansync.core.model.SourceFile
import com.walkmansync.core.model.SyncItem
import com.walkmansync.core.model.SyncPlan
import com.walkmansync.core.model.TargetFile
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.path.FlatFolderRule
import com.walkmansync.core.path.PathPlanner
import com.walkmansync.core.path.PlacementMode
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.tags.TagService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Artımlı, APPEND-ONLY fark motoru (bkz. [DeltaEngine] algoritma belgesi). Yalnızca kopyalama planlar; hedefte
 * silme/üzerine yazma planlamaz. Saf hesaplar `dispatchers.cpu`'da, etiket okumaları en fazla 4 eşzamanlı yapılır.
 */
class DefaultDeltaEngine(
    private val tagService: TagService,
    private val pathPlanner: PathPlanner,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    private val logger: Logger,
) : DeltaEngine {

    override suspend fun computePlan(request: DeltaRequest, onProgress: (ScanProgress) -> Unit): DeltaResult {
        val builder = PlanBuilder(request, pathPlanner)
        withContext(dispatchers.cpu) { builder.classify() }

        // 5. adım için okunacak hedef dosyaları: yalnızca yeni aday varsa.
        val targetReads = if (builder.hasNewCandidates) builder.targetFilesNeedingTags() else emptyList()
        val total = builder.tagFiles.size + targetReads.size
        val reporter = ProgressReporter(total, clock, onProgress)
        reporter.start()

        val sourceTags = readAll(request.source, builder.tagFiles, reporter)
        builder.tagsById = HashMap<String, AudioTags?>(builder.tagFiles.size * 2).also { m ->
            builder.tagFiles.forEachIndexed { i, f -> m[f.relativePath] = sourceTags[i] }
        }

        if (builder.hasNewCandidates) {
            val targetTags = readAll(request.target, targetReads, reporter)
            builder.applyTargetKeys(targetReads, targetTags)
        }

        onProgress(ScanProgress(ScanPhase.PLANNING, itemsDone = total, itemsTotal = total))
        val result = withContext(dispatchers.cpu) { builder.finish() }
        logger.d(
            TAG,
            "Plan: ${result.plan.items.size} kopya, ${result.plan.skipped.size} atlanan, " +
                "${result.plan.totalBytes} bayt, hedef anahtarları değişti=${result.targetKeysChanged}",
        )
        return result
    }

    private suspend fun <H : NodeHandle> readAll(
        tree: ReadableTree<H>,
        files: List<FileEntry<H>>,
        reporter: ProgressReporter,
    ): List<AudioTags?> {
        if (files.isEmpty()) return emptyList()
        return coroutineScope {
            val gate = Semaphore(MAX_PARALLEL_TAG_READS)
            files.map { file ->
                async(dispatchers.cpu) {
                    gate.withPermit {
                        val tags = tagService.read(tree, file)
                        reporter.step(file.relativePath)
                        tags
                    }
                }
            }.awaitAll()
        }
    }

    /** READING_TAGS ilerlemesini en fazla ~10/sn yayar (son adım her zaman yayılır). */
    private class ProgressReporter(
        private val total: Int,
        private val clock: Clock,
        private val sink: (ScanProgress) -> Unit,
    ) {
        private val done = AtomicInteger()
        private val lastEmit = AtomicLong(0)

        fun start() {
            lastEmit.set(clock.monotonicNanos())
            if (total > 0) sink(ScanProgress(ScanPhase.READING_TAGS, 0, total))
        }

        fun step(path: String) {
            val d = done.incrementAndGet()
            val now = clock.monotonicNanos()
            val prev = lastEmit.get()
            if (d >= total || (now - prev >= MIN_INTERVAL_NANOS && lastEmit.compareAndSet(prev, now))) {
                sink(ScanProgress(ScanPhase.READING_TAGS, d, total, currentPath = path))
            }
        }
    }

    private sealed interface Decision {
        class Planned(val item: SyncItem) : Decision
        class Forceable(val reason: SkipReason, val detail: String?, val forced: SyncItem) : Decision
        class Existing(val targetPath: String) : Decision
        data object Blocked : Decision
    }

    private class Flag(val reason: SkipReason, val detail: String?)

    private sealed interface AuxPlan {
        /** Normal yerleştirme; [sizeAgnostic]=true ise hedefte varlık boyuttan bağımsız ALREADY_ON_TARGET sayılır. */
        class Place(val desired: String, val companionOf: String?, val reason: CopyReason) : AuxPlan
        class AlreadyThere(val path: String) : AuxPlan
        class Inherit(val reason: SkipReason, val detail: String?, val desired: String, val companionOf: String) : AuxPlan
        class DuplicateDestination(val path: String) : AuxPlan
        data object Orphan : AuxPlan
    }

    private class PlanBuilder(private val req: DeltaRequest, private val planner: PathPlanner) {
        private val index: SyncIndex = req.index
        private val sourceRoot: String = req.source.root.relativePath.trim('/')
        private val fs: TargetFileSystem = req.targetFileSystem

        private val files: List<SourceFile> =
            req.sourceScan.files.filter { it.format != null }.sortedBy { it.relativePath }

        private val targetByPath = HashMap<String, TargetFile>()
        private val targetNameSize = HashMap<String, String>()
        private val reserved = HashSet<String>()

        private val items = ArrayList<SyncItem>()
        private val skipped = ArrayList<SkippedItem>()
        private val decisions = HashMap<String, Decision>()
        private val flags = HashMap<String, Flag>()
        private val flagKeys = HashMap<String, ContentKey?>()

        /** Etiketi okunacak ses dosyaları (yol sırasıyla): yeni adaylar + indekse göre zorlanabilir olanlar. */
        val tagFiles = ArrayList<SourceFile>()
        private val newAudio = ArrayList<SourceFile>()
        val hasNewCandidates: Boolean get() = newAudio.isNotEmpty()

        var tagsById: Map<String, AudioTags?> = emptyMap()

        private var workingIndex: SyncIndex = index
        private var targetKeysChanged = false
        private var tooLargeOnUnknownFs = false

        init {
            for (t in req.targetScan.files) {
                targetByPath[fold(t.relativePath)] = t
                if (t.format?.isAudio == true) targetNameSize.putIfAbsent(nameSizeKey(t.name, t.size), t.relativePath)
            }
        }

        // ---------- 1-3: ön eleme, indeks, hedef ad+boyut ----------

        fun classify() {
            for (f in files) {
                val id = f.relativePath
                val format = f.format ?: continue
                if (f.size < 0) { skip(f, SkipReason.UNKNOWN_SIZE); block(f); continue }
                if (f.size == 0L) { skip(f, SkipReason.EMPTY_FILE); block(f); continue }

                val entry = index.findBySourcePath(id)
                if (entry != null) {
                    if (entry.sourceSize == f.size) {
                        val present = targetByPath[fold(entry.targetPath)]
                        if (present != null) {
                            skip(f, SkipReason.ALREADY_SYNCED, present.relativePath)
                            existing(f, present.relativePath)
                            continue
                        }
                        flags[id] = Flag(SkipReason.REMOVED_FROM_TARGET, entry.targetPath)
                    } else {
                        flags[id] = Flag(SkipReason.CHANGED_SINCE_SYNC, entry.targetPath)
                    }
                    flagKeys[id] = entry.contentKey
                }

                if (!format.isAudio) continue
                if (entry == null) {
                    val same = targetNameSize[nameSizeKey(f.name, f.size)]
                    if (same != null) {
                        skip(f, SkipReason.ALREADY_ON_TARGET, same)
                        existing(f, same)
                        continue
                    }
                    newAudio += f
                }
                tagFiles += f
            }
        }

        /** 5. adım: indeks kaydı ya da geçerli önbelleği olmayan hedef ses dosyaları. */
        fun targetFilesNeedingTags(): List<TargetFile> =
            req.targetScan.files.filter { t ->
                t.format?.isAudio == true &&
                    index.findByTargetPath(t.relativePath) == null &&
                    index.cachedTargetKey(t.relativePath, t.size) == null
            }

        fun applyTargetKeys(read: List<TargetFile>, tags: List<AudioTags?>) {
            val fresh = HashMap<String, TargetKeyEntry>()
            read.forEachIndexed { i, t ->
                fresh[fold(t.relativePath)] = TargetKeyEntry(t.relativePath, t.size, ContentKey.of(tags[i]))
            }
            val keys = ArrayList<TargetKeyEntry>()
            for (t in req.targetScan.files) {
                if (t.format?.isAudio != true) continue
                if (index.findByTargetPath(t.relativePath) != null) continue
                val entry = fresh[fold(t.relativePath)] ?: index.cachedTargetKey(t.relativePath, t.size) ?: continue
                keys += entry
            }
            keys.sortBy { it.targetPath }
            val previous = index.targetKeys.sortedBy { it.targetPath }
            targetKeysChanged = keys != previous
            workingIndex = if (targetKeysChanged) index.withTargetKeys(keys) else index
        }

        // ---------- 6-8, 9-11: karar, yerleştirme, yardımcı dosyalar, sonuç ----------

        fun finish(): DeltaResult {
            val seen = HashMap<ContentKey, String>()
            for (f in tagFiles) planAudio(f, seen)
            planAuxiliaries()

            val itemIds = items.mapTo(HashSet()) { it.id }
            val companions = items.filter { it.companionOfId != null && it.companionOfId in itemIds }
                .groupBy { it.companionOfId!! }
            val ordered = ArrayList<SyncItem>(items.size)
            for (item in items.sortedBy { it.id }) {
                if (item.companionOfId != null && item.companionOfId in itemIds) continue
                ordered += item
                companions[item.id]?.sortedBy { it.id }?.let { ordered.addAll(it) }
            }

            val warnings = LinkedHashSet(req.warnings)
            if (tooLargeOnUnknownFs) warnings += PlanWarning.TARGET_FS_UNKNOWN
            val free = req.targetFreeBytes
            if (free != null && ordered.sumOf { it.size } > free) warnings += PlanWarning.INSUFFICIENT_SPACE_FOR_ALL

            val plan = SyncPlan(
                pair = req.pair,
                createdAtMillis = req.nowMillis,
                items = ordered,
                skipped = skipped.sortedBy { it.source.relativePath },
                baseIndex = workingIndex,
                targetFileSystem = fs,
                targetFreeBytes = free,
                warnings = warnings,
                sourceAudioCount = files.count { it.format?.isAudio == true },
            )
            return DeltaResult(plan, targetKeysChanged)
        }

        private fun planAudio(f: SourceFile, seen: MutableMap<ContentKey, String>) {
            val id = f.relativePath
            val tags = tagsById[id]
            val flag = flags[id]
            val key = ContentKey.of(tags) ?: flagKeys[id]

            if (flag != null) {
                forceableSkip(f, tags, key, flag.reason, flag.detail)
                return
            }
            if (key != null) {
                val onTarget = workingIndex.targetsWithContent(key).firstOrNull()
                if (onTarget != null) {
                    forceableSkip(f, tags, key, SkipReason.DUPLICATE_CONTENT, onTarget)
                    return
                }
                val firstSource = key.candidates().firstNotNullOfOrNull { seen[it] }
                if (firstSource != null) {
                    forceableSkip(f, tags, key, SkipReason.DUPLICATE_IN_SOURCE, firstSource)
                    return
                }
                seen[key] = id
            }

            val format = f.format ?: return
            val mode = FlatFolderRule.classify(id, sourceRoot).mode
            val desired = planner.planAudio(f, sourceRoot, tags)
            val occupant = targetByPath[fold(desired)]
            if (occupant != null) {
                if (occupant.size == f.size) {
                    skip(f, SkipReason.ALREADY_ON_TARGET, occupant.relativePath)
                    existing(f, occupant.relativePath)
                } else {
                    forceableSkip(f, tags, key, SkipReason.TARGET_PATH_OCCUPIED, occupant.relativePath)
                }
                return
            }
            val path = if (fold(desired) in reserved) uniquePath(desired) else desired
            val reason = if (mode == PlacementMode.TAG_BASED) CopyReason.NEW_TAG_PLACED else CopyReason.NEW
            acceptNew(f, SyncItem(id, f, path, reason, format, tags, key))
        }

        /** Atlanan ses dosyası + (varsa) zorlanabilir kopya; yol plandaki her şeyle çakışmayacak şekilde ayrılır. */
        private fun forceableSkip(f: SourceFile, tags: AudioTags?, key: ContentKey?, reason: SkipReason, detail: String?) {
            val format = f.format ?: return
            val path = uniquePath(planner.planAudio(f, sourceRoot, tags))
            val forced = commitForceable(SyncItem(f.relativePath, f, path, CopyReason.FORCED, format, tags, key))
            skipped += SkippedItem(f, reason, detail, forced)
            decisions[f.relativePath] =
                if (forced != null) Decision.Forceable(reason, detail, forced) else Decision.Blocked
        }

        // ---------- yardımcı dosyalar (.lrc, cover.jpg, .m3u8) ----------

        private fun planAuxiliaries() {
            val byDir = HashMap<String, MutableList<Pair<SourceFile, Decision>>>()
            val byBody = HashMap<String, MutableList<Pair<SourceFile, Decision>>>()
            for (f in files) {
                if (f.format?.isAudio != true) continue
                val d = decisions[f.relativePath] ?: continue
                byDir.getOrPut(fold(f.parentPath)) { ArrayList() } += f to d
                byBody.getOrPut(fold(f.parentPath) + "\u0000" + fold(bodyOf(f.name))) { ArrayList() } += f to d
            }
            for (f in files) {
                val format = f.format ?: continue
                if (format.isAudio) continue
                if (decisions.containsKey(f.relativePath)) continue // ön elemede (boyut/indeks) karara bağlandı
                val plan = when (format) {
                    AudioFormat.LRC -> lrcPlan(f, byBody[fold(f.parentPath) + "\u0000" + fold(bodyOf(f.name))])
                    AudioFormat.COVER -> coverPlan(f, byDir[fold(f.parentPath)].orEmpty())
                    else -> standalonePlan(f)
                }
                emitAux(f, format, plan, flags[f.relativePath])
            }
        }

        private fun lrcPlan(f: SourceFile, sameBody: List<Pair<SourceFile, Decision>>?): AuxPlan {
            val best = sameBody?.minByOrNull { priority(it.second) }?.second
            return when (best) {
                is Decision.Planned ->
                    AuxPlan.Place(planner.planCompanion(best.item.targetPath, f), best.item.id, CopyReason.COMPANION)
                is Decision.Forceable ->
                    AuxPlan.Inherit(best.reason, best.detail, planner.planCompanion(best.forced.targetPath, f), best.forced.id)
                is Decision.Existing -> {
                    val path = planner.planCompanion(best.targetPath, f)
                    val there = targetByPath[fold(path)]
                    if (there != null) AuxPlan.AlreadyThere(there.relativePath)
                    else AuxPlan.Place(path, null, CopyReason.COMPANION)
                }
                else -> standalonePlan(f)
            }
        }

        private fun coverPlan(f: SourceFile, sameDir: List<Pair<SourceFile, Decision>>): AuxPlan {
            val dirs = LinkedHashMap<String, String>()
            var firstPlanned: SyncItem? = null
            var anyAudioPath: String? = null
            for ((_, d) in sameDir) {
                when (d) {
                    is Decision.Planned -> {
                        dirs.putIfAbsent(fold(d.item.targetDir), d.item.targetDir)
                        if (firstPlanned == null) firstPlanned = d.item
                        if (anyAudioPath == null) anyAudioPath = d.item.targetPath
                    }
                    is Decision.Existing -> {
                        val dir = d.targetPath.substringBeforeLast('/', "")
                        if (dir.isNotEmpty()) dirs.putIfAbsent(fold(dir), dir)
                        if (anyAudioPath == null) anyAudioPath = d.targetPath
                    }
                    else -> Unit
                }
            }
            val audioPath = anyAudioPath
            if (dirs.size != 1 || audioPath == null) return standalonePlan(f)
            val desired = planner.planCompanion(audioPath, f).let { p ->
                // Tek dizin çıkarımı: yol, dizinin gerçek harf büyüklüğüyle kurulur.
                dirs.values.first() + "/" + p.substringAfterLast('/')
            }
            val there = targetByPath[fold(desired)]
            return when {
                there != null -> AuxPlan.AlreadyThere(there.relativePath)
                fold(desired) in reserved -> AuxPlan.DuplicateDestination(desired)
                else -> AuxPlan.Place(desired, firstPlanned?.id, CopyReason.COMPANION)
            }
        }

        /** Eşi olmayan yardımcı dosya: PRESERVE kipte ve .m3u8 için `planStandalone`; aksi hâlde yetim. */
        private fun standalonePlan(f: SourceFile): AuxPlan {
            val mode = FlatFolderRule.classify(f.relativePath, sourceRoot).mode
            val isPlaylist = f.format == AudioFormat.M3U8
            if (!isPlaylist && mode != PlacementMode.PRESERVE_STRUCTURE) return AuxPlan.Orphan
            val reason = if (mode == PlacementMode.PRESERVE_STRUCTURE) CopyReason.NEW else CopyReason.NEW_TAG_PLACED
            return AuxPlan.Place(planner.planStandalone(f, sourceRoot), null, reason)
        }

        private fun emitAux(f: SourceFile, format: AudioFormat, plan: AuxPlan, flag: Flag?) {
            when (plan) {
                is AuxPlan.Orphan ->
                    if (flag != null) skip(f, flag.reason, flag.detail) else skip(f, SkipReason.ORPHAN_AUXILIARY)
                is AuxPlan.AlreadyThere ->
                    if (flag != null) skip(f, flag.reason, flag.detail) else skip(f, SkipReason.ALREADY_ON_TARGET, plan.path)
                is AuxPlan.DuplicateDestination ->
                    if (flag != null) skip(f, flag.reason, flag.detail) else skip(f, SkipReason.DUPLICATE_IN_SOURCE, plan.path)
                is AuxPlan.Inherit -> {
                    val forced = forcedAux(f, format, plan.desired, plan.companionOf)
                    skipped += SkippedItem(f, flag?.reason ?: plan.reason, flag?.detail ?: plan.detail, forced)
                }
                is AuxPlan.Place -> {
                    if (flag != null) {
                        val forced = forcedAux(f, format, plan.desired, plan.companionOf)
                        skipped += SkippedItem(f, flag.reason, flag.detail, forced)
                        return
                    }
                    val occupant = targetByPath[fold(plan.desired)]
                    if (occupant != null) {
                        if (occupant.size == f.size) {
                            skip(f, SkipReason.ALREADY_ON_TARGET, occupant.relativePath)
                        } else {
                            val forced = forcedAux(f, format, plan.desired, plan.companionOf)
                            skipped += SkippedItem(f, SkipReason.TARGET_PATH_OCCUPIED, occupant.relativePath, forced)
                        }
                        return
                    }
                    val path = if (fold(plan.desired) in reserved) uniquePath(plan.desired) else plan.desired
                    acceptNew(f, SyncItem(f.relativePath, f, path, plan.reason, format, null, null, plan.companionOf))
                }
            }
        }

        private fun forcedAux(f: SourceFile, format: AudioFormat, desired: String, companionOf: String?): SyncItem? =
            commitForceable(
                SyncItem(f.relativePath, f, uniquePath(desired), CopyReason.FORCED, format, null, null, companionOf),
            )

        // ---------- ortak yardımcılar ----------

        /** Normal kopya adayını planlar; FAT32/UNKNOWN 4 GiB sınırını uygular. */
        private fun acceptNew(f: SourceFile, item: SyncItem) {
            val max = fs.maxFileSize
            if (max != null && item.size > max) {
                if (fs == TargetFileSystem.FAT32) {
                    skip(f, SkipReason.TOO_LARGE_FOR_FAT32)
                    block(f)
                } else {
                    reserved += fold(item.targetPath)
                    tooLargeOnUnknownFs = true
                    val forced = item.copy(reason = CopyReason.FORCED)
                    skipped += SkippedItem(f, SkipReason.TOO_LARGE_FOR_FAT32, null, forced)
                    decisions[f.relativePath] = Decision.Forceable(SkipReason.TOO_LARGE_FOR_FAT32, null, forced)
                }
                return
            }
            reserved += fold(item.targetPath)
            items += item
            decisions[f.relativePath] = Decision.Planned(item)
        }

        /** Zorlanabilir kopyanın yolunu ayırır; FAT32'de 4 GiB üstü için zorlama yoktur (null). */
        private fun commitForceable(item: SyncItem): SyncItem? {
            val max = fs.maxFileSize
            if (max != null && item.size > max) {
                if (fs == TargetFileSystem.FAT32) return null
                tooLargeOnUnknownFs = true
            }
            reserved += fold(item.targetPath)
            return item
        }

        private fun uniquePath(desired: String): String = planner.uniquify(desired) { taken(it) }

        private fun taken(path: String): Boolean {
            val k = fold(path)
            return k in targetByPath || k in reserved
        }

        private fun skip(f: SourceFile, reason: SkipReason, detail: String? = null) {
            skipped += SkippedItem(f, reason, detail)
        }

        private fun block(f: SourceFile) {
            decisions[f.relativePath] = Decision.Blocked
        }

        private fun existing(f: SourceFile, targetPath: String) {
            decisions[f.relativePath] = Decision.Existing(targetPath)
        }

        private fun priority(d: Decision): Int = when (d) {
            is Decision.Planned -> 0
            is Decision.Forceable -> 1
            is Decision.Existing -> 2
            Decision.Blocked -> 3
        }

        private fun bodyOf(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }

        private fun fold(path: String): String = SyncIndex.foldPath(path)

        private fun nameSizeKey(name: String, size: Long): String = fold(name) + "|" + size
    }

    private companion object {
        const val TAG = "DeltaEngine"
        const val MAX_PARALLEL_TAG_READS = 4
        const val MIN_INTERVAL_NANOS = 100_000_000L
    }
}
