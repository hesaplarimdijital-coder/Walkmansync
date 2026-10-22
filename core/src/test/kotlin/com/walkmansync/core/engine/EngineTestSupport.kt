package com.walkmansync.core.engine

import com.walkmansync.core.fs.TargetTree
import com.walkmansync.core.index.ContentKey
import com.walkmansync.core.index.IndexCodec
import com.walkmansync.core.index.IndexEntry
import com.walkmansync.core.index.IndexFormatException
import com.walkmansync.core.index.SyncIndex
import com.walkmansync.core.index.TargetKeyEntry
import com.walkmansync.core.index.UnsupportedIndexVersionException
import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.CopyReason
import com.walkmansync.core.model.DocumentIds
import com.walkmansync.core.model.FileEntry
import com.walkmansync.core.model.SourceFile
import com.walkmansync.core.model.SourceHandle
import com.walkmansync.core.model.SyncItem
import com.walkmansync.core.model.SyncPlan
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.model.TransferSummary
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumePair
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Clock
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.testing.InMemorySourceTree
import com.walkmansync.core.testing.InMemoryTargetTree
import com.walkmansync.core.testing.InMemoryVolume
import com.walkmansync.core.transfer.TransferConfig
import com.walkmansync.core.transfer.TransferControl
import com.walkmansync.core.transfer.TransferEvent
import com.walkmansync.core.transfer.TransferRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

val SRC_ID: VolumeId = VolumeId.require("1A2B-3C4D")
val DST_ID: VolumeId = VolumeId.require("ABCD-1234")

/** B2'nin codec'ine bağımlı olmayan, satır tabanlı sahte codec. */
class FakeIndexCodec : IndexCodec {
    override fun encode(index: SyncIndex): String = buildString {
        appendLine("v=${index.schemaVersion}")
        appendLine("target=${index.targetVolume.uuid}")
        appendLine("lastSource=${index.lastSourceVolume?.uuid ?: "-"}")
        appendLine("times=${index.createdAtMillis},${index.updatedAtMillis}")
        for (e in index.entries) {
            appendLine("E\t${e.sourcePath}\t${e.sourceSize}\t${e.sourceModified}\t${e.targetPath}\t${e.targetSize}\t${e.contentKey?.value ?: "-"}\t${e.syncedAtMillis}")
        }
        for (k in index.targetKeys) appendLine("K\t${k.targetPath}\t${k.targetSize}\t${k.contentKey?.value ?: "-"}")
    }

    override fun decode(json: String): SyncIndex {
        try {
            val lines = json.lines().filter { it.isNotEmpty() }
            val version = lines.firstOrNull()?.removePrefix("v=")?.toIntOrNull()
                ?: throw IndexFormatException("v eksik")
            if (version > SyncIndex.CURRENT_SCHEMA) throw UnsupportedIndexVersionException(version)
            val target = VolumeId.of(lines[1].removePrefix("target=")) ?: throw IndexFormatException("target")
            val last = VolumeId.of(lines[2].removePrefix("lastSource="))
            val (created, updated) = lines[3].removePrefix("times=").split(',').map { it.toLong() }
            val entries = ArrayList<IndexEntry>()
            val keys = ArrayList<TargetKeyEntry>()
            for (l in lines.drop(4)) {
                val p = l.split('\t')
                fun key(s: String) = if (s == "-") null else ContentKey(s)
                when (p[0]) {
                    "E" -> entries.add(IndexEntry(p[1], p[2].toLong(), p[3].toLong(), p[4], p[5].toLong(), key(p[6]), p[7].toLong()))
                    "K" -> keys.add(TargetKeyEntry(p[1], p[2].toLong(), key(p[3])))
                    else -> throw IndexFormatException("satır: $l")
                }
            }
            return SyncIndex(version, target, last, created, updated, entries, keys)
        } catch (e: IndexFormatException) {
            throw e
        } catch (e: UnsupportedIndexVersionException) {
            throw e
        } catch (e: Exception) {
            throw IndexFormatException("bozuk: ${e.message}", e)
        }
    }
}

/** Küçük, çalıştırmaya hazır donanım: iki bellek içi birim + ağaçlar + çift. */
class Rig(
    targetRoot: String = "",
    fs: TargetFileSystem = TargetFileSystem.EXFAT,
    val clock: Clock = Clock.System,
) {
    val srcVol = InMemoryVolume(SRC_ID)
    val dstVol = InMemoryVolume(DST_ID)
    val source = InMemorySourceTree(srcVol)
    val target = InMemoryTargetTree(dstVol, fs, targetRoot)
    val pair = VolumePair(SRC_ID, DST_ID, "1A2B-3C4D:", "ABCD-1234:$targetRoot")
    val codec = FakeIndexCodec()
    val store = TargetIndexStore(codec, Logger.None)
    val runner = AtomicTransferRunner(store, clock, AppDispatchers.Default, Logger.None)

    fun src(path: String, size: Int, mtime: Long = 1000L): SourceFile {
        srcVol.putFile(path, size, mtime)
        return entry(path, size.toLong(), mtime)
    }

    fun entry(path: String, size: Long, mtime: Long = 1000L): SourceFile =
        FileEntry(SourceHandle(DocumentIds.build(SRC_ID, path)), path, path.substringAfterLast('/'), size, mtime)

    fun item(
        srcPath: String,
        size: Int,
        targetPath: String = "MUSIC/" + srcPath.substringAfterLast('/'),
        reason: CopyReason = CopyReason.NEW,
        companionOf: String? = null,
    ): SyncItem {
        val file = src(srcPath, size)
        return SyncItem(srcPath, file, targetPath, reason, file.format ?: AudioFormat.FLAC, companionOfId = companionOf)
    }

    fun plan(items: List<SyncItem>, fs: TargetFileSystem = TargetFileSystem.EXFAT) = SyncPlan(
        pair = pair, createdAtMillis = 0, items = items, skipped = emptyList(),
        baseIndex = SyncIndex.empty(DST_ID, 0), targetFileSystem = fs, targetFreeBytes = null,
    )

    fun request(items: List<SyncItem>, config: TransferConfig = TransferConfig(bufferSize = 4096)) =
        TransferRequest(plan(items), items, source, target, config = config)

    /** Aktarımı sonuna dek çalıştırır. */
    fun runAll(
        items: List<SyncItem>,
        control: TransferControl = TransferControl(),
        config: TransferConfig = TransferConfig(bufferSize = 4096),
    ): Run = runBlocking {
        withTimeout(20_000) {
            val events = runner.run(request(items, config), control).toList()
            Run(events)
        }
    }

    suspend fun loadIndex(tree: TargetTree = target) = store.load(tree)

    fun partFiles(): List<String> = dstVol.allFiles().filter { it.endsWith(".wsync.part", ignoreCase = true) }

    class Run(val events: List<TransferEvent>) {
        val summary: TransferSummary get() = (events.last() as TransferEvent.Finished).summary
        val progress get() = events.filterIsInstance<TransferEvent.Progress>().map { it.progress }
    }
}
