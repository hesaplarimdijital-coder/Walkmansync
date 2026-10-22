package com.walkmansync.core.domain

import com.walkmansync.core.delta.DeltaRequest
import com.walkmansync.core.delta.DeltaResult
import com.walkmansync.core.fs.ReadableTree
import com.walkmansync.core.index.ContentKey
import com.walkmansync.core.index.IndexEntry
import com.walkmansync.core.index.SyncIndex
import com.walkmansync.core.index.TargetKeyEntry
import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.model.CopyReason
import com.walkmansync.core.model.FileEntry
import com.walkmansync.core.model.NodeHandle
import com.walkmansync.core.model.PlanWarning
import com.walkmansync.core.model.ScanPhase
import com.walkmansync.core.model.ScanProgress
import com.walkmansync.core.model.SkipReason
import com.walkmansync.core.model.SourceFile
import com.walkmansync.core.model.SourceHandle
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.TargetFile
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.model.TargetHandle
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumePair
import com.walkmansync.core.model.VolumeRole
import com.walkmansync.core.path.impl.DefaultPathPlanner
import com.walkmansync.core.path.impl.FatFileNameSanitizer
import com.walkmansync.core.runtime.AppDispatchers
import com.walkmansync.core.runtime.Logger
import com.walkmansync.core.runtime.ManualClock
import com.walkmansync.core.scan.ScanExclusions
import com.walkmansync.core.scan.TreeScanResult
import com.walkmansync.core.tags.TagFallback
import com.walkmansync.core.tags.TagService
import com.walkmansync.core.tags.parsers.CompositeTagReader
import com.walkmansync.core.testing.InMemorySourceTree
import com.walkmansync.core.testing.InMemoryTargetTree
import com.walkmansync.core.testing.InMemoryVolume
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

class DeltaEngineTest {
    private val srcId = VolumeId.require("1A2B-3C4D")
    private val dstId = VolumeId.require("ABCD-1234")
    private val srcVol = InMemoryVolume(srcId)
    private val dstVol = InMemoryVolume(dstId)

    /** Yola göre etiket döndüren, okumaları kaydeden sahte servis. */
    private class FakeTags(val tags: MutableMap<String, AudioTags?> = HashMap()) : TagService {
        val reads: MutableList<String> = Collections.synchronizedList(ArrayList())
        var failWith: Throwable? = null
        override suspend fun <H : NodeHandle> read(tree: ReadableTree<H>, file: FileEntry<H>): AudioTags? {
            failWith?.let { throw it }
            reads += file.relativePath
            return tags[file.relativePath]
        }
    }

    private val fake = FakeTags()
    private val clock = ManualClock(1_000_000)
    private val engine = DefaultDeltaEngine(
        fake, DefaultPathPlanner(FatFileNameSanitizer()), AppDispatchers.Default, clock, Logger.None,
    )

    private fun sf(path: String, size: Long = 1000, mtime: Long = 0) =
        SourceFile(SourceHandle("1A2B-3C4D:$path"), path, path.substringAfterLast('/'), size, mtime)

    private fun tf(path: String, size: Long = 1000) =
        TargetFile(TargetHandle("ABCD-1234:$path"), path, path.substringAfterLast('/'), size, 0)

    private fun tags(artist: String, album: String, title: String, track: Int = 1, durationMs: Long? = 200_000) =
        AudioTags(artist = artist, album = album, title = title, trackNumber = track, durationMs = durationMs)

    private fun compute(
        source: List<SourceFile>,
        target: List<TargetFile> = emptyList(),
        index: SyncIndex = SyncIndex.empty(dstId, 0),
        fs: TargetFileSystem = TargetFileSystem.EXFAT,
        free: Long? = 1L shl 40,
        sourceRoot: String = "",
        progress: (ScanProgress) -> Unit = {},
    ): DeltaResult {
        val request = DeltaRequest(
            pair = VolumePair(srcId, dstId, "1A2B-3C4D:$sourceRoot", "ABCD-1234:"),
            source = InMemorySourceTree(srcVol.also { if (sourceRoot.isNotEmpty()) it.mkdirs(sourceRoot) }, sourceRoot),
            target = InMemoryTargetTree(dstVol),
            sourceScan = TreeScanResult(source.sortedBy { it.relativePath }, emptyList(), emptyList(), emptyList()),
            targetScan = TreeScanResult(target.sortedBy { it.relativePath }, emptyList(), emptyList(), emptyList()),
            index = index,
            targetFileSystem = fs,
            targetFreeBytes = free,
            warnings = emptySet(),
            nowMillis = 5_000,
        )
        return runBlocking { engine.computePlan(request, progress) }
    }

    private fun entry(src: String, size: Long, dst: String, key: ContentKey? = null) =
        IndexEntry(src, size, 0, dst, size, key, 1)

    private fun indexOf(vararg entries: IndexEntry, keys: List<TargetKeyEntry> = emptyList()) =
        SyncIndex.empty(dstId, 0).withEntries(entries.toList(), 1, srcId).withTargetKeys(keys)

    // ---------- yerleştirme ----------

    @Test fun flatFolderFilesArePlacedByTags() {
        fake.tags["Download/a.flac"] = tags("Artist", "Album", "Title", 1)
        fake.tags["Download/b.mp3"] = tags("Artist", "Album", "Other", 2)
        val r = compute(listOf(sf("Download/a.flac"), sf("Download/b.mp3")))
        assertEquals(
            listOf("MUSIC/Artist/Album/01 - Title.flac", "MUSIC/Artist/Album/02 - Other.mp3"),
            r.plan.items.map { it.targetPath },
        )
        assertTrue(r.plan.items.all { it.reason == CopyReason.NEW_TAG_PLACED })
        assertEquals(setOf("Download/a.flac", "Download/b.mp3"), r.plan.defaultSelection)
        assertNotNull(r.plan.items[0].contentKey)
        assertEquals(2, r.plan.sourceAudioCount)
        assertTrue(r.plan.skipped.isEmpty())
        assertFalse(r.targetKeysChanged)
        assertEquals(5_000L, r.plan.createdAtMillis)
    }

    @Test fun structuredFoldersKeepRelativePath() {
        val r = compute(listOf(sf("Music/Artist/Album/01.flac"), sf("HiRes/A/B/CD1/01.dsf")))
        assertEquals(
            listOf("MUSIC/Artist/Album/01.flac", "MUSIC/HiRes/A/B/CD1/01.dsf").sorted(),
            r.plan.items.map { it.targetPath }.sorted(),
        )
        assertTrue(r.plan.items.all { it.reason == CopyReason.NEW })
    }

    @Test fun sourceRootAffectsClassification() {
        // Kullanıcı "Music" klasörünü seçti: "Music/Album/x.flac" sığ → etiketlere göre.
        fake.tags["Music/Album/x.flac"] = tags("A", "B", "C")
        val r = compute(listOf(sf("Music/Album/x.flac")), sourceRoot = "Music")
        assertEquals("MUSIC/A/B/01 - C.flac", r.plan.items.single().targetPath)
    }

    @Test fun planInternalCollisionGetsCounter() {
        fake.tags["Download/a.flac"] = tags("A", "B", "Same", 1, 100_000)
        fake.tags["Download/b.flac"] = tags("A", "B", "Same", 1, 300_000) // süre farklı → yinelenen değil
        val r = compute(listOf(sf("Download/a.flac"), sf("Download/b.flac")))
        assertEquals(
            listOf("MUSIC/A/B/01 - Same.flac", "MUSIC/A/B/01 - Same (2).flac"),
            r.plan.items.map { it.targetPath },
        )
    }

    @Test fun collisionIsCaseAndNormalizationInsensitive() {
        fake.tags["Download/a.flac"] = tags("ARTIST", "B", "Şarkı", 1, 100_000)
        fake.tags["Download/b.flac"] = tags("artist", "B", "Şarkı".replace("Ş", "Ş"), 1, 300_000)
        val r = compute(listOf(sf("Download/a.flac"), sf("Download/b.flac")))
        assertEquals(2, r.plan.items.size)
        assertEquals(2, r.plan.items.map { it.targetPath.lowercase() }.toSet().size)
    }

    // ---------- atlama kararları ----------

    @Test fun preFiltersUnknownAndEmptySize() {
        val r = compute(listOf(sf("Download/neg.flac", -1), sf("Download/zero.flac", 0), sf("Download/zero.lrc", 0)))
        assertTrue(r.plan.items.isEmpty())
        assertEquals(
            mapOf(
                "Download/neg.flac" to SkipReason.UNKNOWN_SIZE,
                "Download/zero.flac" to SkipReason.EMPTY_FILE,
                "Download/zero.lrc" to SkipReason.EMPTY_FILE,
            ),
            r.plan.skipped.associate { it.id to it.reason },
        )
        assertTrue(r.plan.skipped.all { it.forceable == null })
        assertTrue(fake.reads.isEmpty())
    }

    @Test fun alreadySyncedByIndexPathAndSize() {
        val idx = indexOf(entry("Download/a.flac", 1000, "MUSIC/A/B/01 - T.flac"))
        val r = compute(listOf(sf("Download/a.flac")), listOf(tf("MUSIC/A/B/01 - T.flac")), idx)
        assertEquals(SkipReason.ALREADY_SYNCED, r.plan.skipped.single().reason)
        assertTrue(r.plan.items.isEmpty())
        assertTrue("etiket okunmamalı", fake.reads.isEmpty())
    }

    @Test fun indexedButMissingOnTargetIsRemovedAndForceable() {
        fake.tags["Download/a.flac"] = tags("A", "B", "T")
        val idx = indexOf(entry("download/A.FLAC", 1000, "MUSIC/A/B/01 - T.flac")) // büyük/küçük harf duyarsız eşleşme
        val r = compute(listOf(sf("Download/a.flac")), emptyList(), idx)
        val s = r.plan.skipped.single()
        assertEquals(SkipReason.REMOVED_FROM_TARGET, s.reason)
        assertEquals(CopyReason.FORCED, s.forceable!!.reason)
        assertEquals("MUSIC/A/B/01 - T.flac", s.forceable!!.targetPath)
        assertEquals(setOf("Download/a.flac"), r.plan.selectable.mapTo(HashSet()) { it.id })
        assertTrue(r.plan.defaultSelection.isEmpty())
    }

    @Test fun changedSinceSyncKeepsOldAndUsesNewName() {
        fake.tags["Download/a.flac"] = tags("A", "B", "T")
        val idx = indexOf(entry("Download/a.flac", 900, "MUSIC/A/B/01 - T.flac"))
        val r = compute(listOf(sf("Download/a.flac", 1000)), listOf(tf("MUSIC/A/B/01 - T.flac", 900)), idx)
        val s = r.plan.skipped.single()
        assertEquals(SkipReason.CHANGED_SINCE_SYNC, s.reason)
        assertEquals("MUSIC/A/B/01 - T (2).flac", s.forceable!!.targetPath)
    }

    @Test fun nameAndSizeOnTargetAnywhereUnderMusic() {
        val r = compute(listOf(sf("Download/Song.FLAC", 777)), listOf(tf("MUSIC/Other/Place/song.flac", 777)))
        assertEquals(SkipReason.ALREADY_ON_TARGET, r.plan.skipped.single().reason)
        assertTrue(fake.reads.isEmpty())
        // aynı ad, farklı boyut → yeni aday
        fake.tags["Download/Song.FLAC"] = tags("A", "B", "T")
        val r2 = compute(listOf(sf("Download/Song.FLAC", 778)), listOf(tf("MUSIC/Other/Place/song.flac", 777)))
        assertEquals(1, r2.plan.items.size)
    }

    @Test fun nfdSourceNameMatchesNfcTargetName() {
        val nfd = java.text.Normalizer.normalize("Şarkı.flac", java.text.Normalizer.Form.NFD)
        val nfc = java.text.Normalizer.normalize("Şarkı.flac", java.text.Normalizer.Form.NFC)
        val r = compute(listOf(sf("Download/$nfd", 55)), listOf(tf("MUSIC/X/Y/$nfc", 55)))
        assertEquals(SkipReason.ALREADY_ON_TARGET, r.plan.skipped.single().reason)
    }

    @Test fun duplicateContentFromIndexKeyWithNeighbourBucket() {
        val t = tags("Beyoncé", "Lemonade", "Hold Up", 3, 221_000)
        fake.tags["Download/x.mp3"] = t
        val key = ContentKey.of(t.copy(durationMs = 223_100))!! // komşu kova
        val idx = indexOf(entry("Old/y.flac", 5, "MUSIC/Beyonce/Lemonade/03 - Hold Up.flac", key))
        val r = compute(listOf(sf("Download/x.mp3")), emptyList(), idx)
        val s = r.plan.skipped.single()
        assertEquals(SkipReason.DUPLICATE_CONTENT, s.reason)
        assertEquals("MUSIC/Beyonce/Lemonade/03 - Hold Up.flac", s.detail)
        assertNotNull(s.forceable)
        assertEquals("MUSIC/Beyoncé/Lemonade/03 - Hold Up.mp3", s.forceable!!.targetPath)
    }

    @Test fun duplicateContentFromTargetTagsAndCache() {
        val t = tags("Artist", "Album", "Song", 1, 180_000)
        fake.tags["Download/new.flac"] = t
        fake.tags["MUSIC/Manual/Copy/song.mp3"] = t // kullanıcı başka araçla koymuş
        val target = listOf(tf("MUSIC/Manual/Copy/song.mp3", 4321))
        val r1 = compute(listOf(sf("Download/new.flac", 9999)), target)
        assertEquals(SkipReason.DUPLICATE_CONTENT, r1.plan.skipped.single().reason)
        assertTrue(r1.targetKeysChanged)
        assertEquals(1, r1.plan.baseIndex.targetKeys.size)
        assertEquals(ContentKey.of(t), r1.plan.baseIndex.targetKeys.single().contentKey)
        assertTrue(fake.reads.contains("MUSIC/Manual/Copy/song.mp3"))

        // İkinci tarama: önbellek geçerli → hedef etiketi tekrar okunmaz, değişiklik yok.
        fake.reads.clear()
        val r2 = compute(listOf(sf("Download/new.flac", 9999)), target, r1.plan.baseIndex)
        assertEquals(SkipReason.DUPLICATE_CONTENT, r2.plan.skipped.single().reason)
        assertFalse(r2.targetKeysChanged)
        assertEquals(listOf("Download/new.flac"), fake.reads.toList())
    }

    @Test fun targetCachePrunedWhenFileDisappearsAndNotReadWithoutCandidates() {
        val stale = TargetKeyEntry("MUSIC/Gone/x.mp3", 10, ContentKey("v1|a|b|0|0|x|-"))
        val idx = indexOf(keys = listOf(stale))
        fake.tags["Download/n.flac"] = tags("A", "B", "T")
        val r = compute(listOf(sf("Download/n.flac")), emptyList(), idx)
        assertTrue(r.targetKeysChanged)
        assertTrue(r.plan.baseIndex.targetKeys.isEmpty())

        // Aday yoksa (hepsi atlanmış) hedef etiketleri hiç okunmaz ve önbellek budanmaz.
        fake.reads.clear()
        val r2 = compute(listOf(sf("Download/same.flac", 5)), listOf(tf("MUSIC/q/same.flac", 5), tf("MUSIC/q/other.mp3", 7)), idx)
        assertEquals(SkipReason.ALREADY_ON_TARGET, r2.plan.skipped.single().reason)
        assertTrue(fake.reads.isEmpty())
        assertFalse(r2.targetKeysChanged)
        assertEquals(listOf(stale), r2.plan.baseIndex.targetKeys)
    }

    @Test fun indexedTargetFilesDoNotNeedTagReads() {
        val key = ContentKey("v1|a|b|0|1|t|10")
        val idx = indexOf(entry("Old/o.flac", 3, "MUSIC/A/B/o.flac", key))
        fake.tags["Download/n.flac"] = tags("X", "Y", "Z")
        compute(listOf(sf("Download/n.flac")), listOf(tf("MUSIC/A/B/o.flac", 3)), idx)
        assertEquals(listOf("Download/n.flac"), fake.reads.toList())
    }

    @Test fun duplicateInSourceFirstByPathWins() {
        val t = tags("A", "B", "T", 1, 100_000)
        fake.tags["Download/b.flac"] = t
        fake.tags["Download/a.mp3"] = t
        fake.tags["Download/c.flac"] = t.copy(durationMs = 101_500) // komşu kova
        val r = compute(listOf(sf("Download/b.flac"), sf("Download/a.mp3"), sf("Download/c.flac")))
        assertEquals(listOf("Download/a.mp3"), r.plan.items.map { it.id })
        val dup = r.plan.skipped.associateBy { it.id }
        assertEquals(SkipReason.DUPLICATE_IN_SOURCE, dup.getValue("Download/b.flac").reason)
        assertEquals("Download/a.mp3", dup.getValue("Download/b.flac").detail)
        assertEquals(SkipReason.DUPLICATE_IN_SOURCE, dup.getValue("Download/c.flac").reason)
        // zorlanabilir kopyalar benzersiz yollara sahip (SyncPlan.init denetler) ve seçilebilir
        assertEquals(3, r.plan.selectable.size)
        val forcedPaths = r.plan.skipped.mapNotNull { it.forceable?.targetPath }
        assertEquals(2, forcedPaths.toSet().size)
    }

    @Test fun targetPathOccupiedByDifferentSize() {
        fake.tags["Download/a.flac"] = tags("A", "B", "T")
        val r = compute(listOf(sf("Download/a.flac", 1000)), listOf(tf("music/a/b/01 - t.FLAC", 999)))
        val s = r.plan.skipped.single()
        assertEquals(SkipReason.TARGET_PATH_OCCUPIED, s.reason)
        assertEquals("music/a/b/01 - t.FLAC", s.detail)
        assertEquals("MUSIC/A/B/01 - T (2).flac", s.forceable!!.targetPath)
    }

    @Test fun plannedPathOccupiedBySameSizeIsAlreadyOnTarget() {
        fake.tags["Download/a.flac"] = tags("A", "B", "T")
        val r = compute(listOf(sf("Download/a.flac", 1000)), listOf(tf("MUSIC/A/B/01 - T.flac", 1000)))
        // ad+boyut (3. adım) zaten "01 - T.flac" ≠ "a.flac" → etiket yolu hedefte: ALREADY_ON_TARGET
        assertEquals(SkipReason.ALREADY_ON_TARGET, r.plan.skipped.single().reason)
    }

    // ---------- dosya sistemi ----------

    @Test fun fat32RejectsHugeFilesWithoutForce() {
        val huge = TargetFileSystem.FAT32_MAX_FILE_SIZE + 1
        fake.tags["Download/h.dsf"] = tags("A", "B", "Huge")
        val r = compute(listOf(sf("Download/h.dsf", huge), sf("Music/A/B/ok.flac")), fs = TargetFileSystem.FAT32)
        val s = r.plan.skipped.single()
        assertEquals(SkipReason.TOO_LARGE_FOR_FAT32, s.reason)
        assertNull(s.forceable)
        assertEquals(1, r.plan.items.size)
        assertFalse(PlanWarning.TARGET_FS_UNKNOWN in r.plan.warnings)
    }

    @Test fun unknownFsMakesHugeFilesForceableWithWarning() {
        val huge = TargetFileSystem.FAT32_MAX_FILE_SIZE + 1
        fake.tags["Download/h.dsf"] = tags("A", "B", "Huge")
        val r = compute(listOf(sf("Download/h.dsf", huge)), fs = TargetFileSystem.UNKNOWN)
        val s = r.plan.skipped.single()
        assertEquals(SkipReason.TOO_LARGE_FOR_FAT32, s.reason)
        assertEquals(CopyReason.FORCED, s.forceable!!.reason)
        assertTrue(PlanWarning.TARGET_FS_UNKNOWN in r.plan.warnings)
        // sınırda olan dosya (4 GiB - 1) sığar
        val r2 = compute(listOf(sf("Music/A/B/edge.dsf", TargetFileSystem.FAT32_MAX_FILE_SIZE)), fs = TargetFileSystem.FAT32)
        assertEquals(1, r2.plan.items.size)
        val r3 = compute(listOf(sf("Music/A/B/edge.dsf", huge)), fs = TargetFileSystem.EXFAT)
        assertEquals(1, r3.plan.items.size)
    }

    @Test fun insufficientSpaceWarning() {
        val r = compute(listOf(sf("Music/A/B/1.flac", 600), sf("Music/A/B/2.flac", 600)), free = 1000)
        assertTrue(PlanWarning.INSUFFICIENT_SPACE_FOR_ALL in r.plan.warnings)
        assertEquals(1200L, r.plan.totalBytes)
        assertFalse(PlanWarning.INSUFFICIENT_SPACE_FOR_ALL in compute(listOf(sf("Music/A/B/1.flac", 600)), free = 1000).plan.warnings)
        assertFalse(PlanWarning.INSUFFICIENT_SPACE_FOR_ALL in compute(listOf(sf("Music/A/B/1.flac", 600)), free = null).plan.warnings)
    }

    // ---------- yardımcı dosyalar ----------

    @Test fun lrcFollowsPlannedAudioAndOrdering() {
        fake.tags["Download/Song.flac"] = tags("A", "B", "Song", 7)
        val r = compute(listOf(sf("Download/cover.jpg", 50), sf("Download/Song.flac"), sf("Download/song.LRC", 20)))
        // yardımcılar bağlı oldukları sesten SONRA: ses, ardından (yola göre) kapak ve söz
        assertEquals(listOf("Download/Song.flac", "Download/cover.jpg", "Download/song.LRC"), r.plan.items.map { it.id })
        val byId = r.plan.items.associateBy { it.id }
        assertEquals("MUSIC/A/B/07 - Song.lrc", byId.getValue("Download/song.LRC").targetPath)
        assertEquals("Download/Song.flac", byId.getValue("Download/song.LRC").companionOfId)
        assertEquals(CopyReason.COMPANION, byId.getValue("Download/song.LRC").reason)
        assertEquals("MUSIC/A/B/cover.jpg", byId.getValue("Download/cover.jpg").targetPath)
        assertEquals("Download/Song.flac", byId.getValue("Download/cover.jpg").companionOfId)
    }

    @Test fun companionComesAfterItsAudioEvenWhenPathSortsFirst() {
        fake.tags["Download/Album/z.flac"] = tags("A", "B", "Z")
        val r = compute(listOf(sf("Download/Album/cover.jpg", 10), sf("Download/Album/z.flac")))
        assertEquals(listOf("Download/Album/z.flac", "Download/Album/cover.jpg"), r.plan.items.map { it.id })
    }

    @Test fun lrcOfAlreadySyncedAudioUsesRealTargetPath() {
        val idx = indexOf(entry("Download/Song.flac", 1000, "MUSIC/Real/Dir/01 - Song.flac"))
        val r = compute(
            listOf(sf("Download/Song.flac"), sf("Download/Song.lrc", 20)),
            listOf(tf("MUSIC/Real/Dir/01 - Song.flac")),
            idx,
        )
        val item = r.plan.items.single()
        assertEquals("MUSIC/Real/Dir/01 - Song.lrc", item.targetPath)
        assertNull(item.companionOfId)
        assertEquals(SkipReason.ALREADY_SYNCED, r.plan.skipped.single().reason)
        // hedefte .lrc zaten varsa
        val r2 = compute(
            listOf(sf("Download/Song.flac"), sf("Download/Song.lrc", 20)),
            listOf(tf("MUSIC/Real/Dir/01 - Song.flac"), tf("MUSIC/Real/Dir/01 - Song.lrc", 3)),
            idx,
        )
        assertTrue(r2.plan.items.isEmpty())
        assertEquals(
            setOf(SkipReason.ALREADY_SYNCED, SkipReason.ALREADY_ON_TARGET),
            r2.plan.skipped.map { it.reason }.toSet(),
        )
    }

    @Test fun lrcOfSkippedForceableAudioIsSkippedWithSameReason() {
        val t = tags("A", "B", "T", 1, 100_000)
        fake.tags["Download/dup.flac"] = t
        val idx = indexOf(entry("Old/o.flac", 3, "MUSIC/A/B/01 - T.flac", ContentKey.of(t)))
        val r = compute(listOf(sf("Download/dup.flac"), sf("Download/dup.lrc", 20)), emptyList(), idx)
        assertTrue(r.plan.items.isEmpty())
        val lrc = r.plan.skipped.single { it.id == "Download/dup.lrc" }
        assertEquals(SkipReason.DUPLICATE_CONTENT, lrc.reason)
        val audio = r.plan.skipped.single { it.id == "Download/dup.flac" }
        assertEquals(audio.forceable!!.id, lrc.forceable!!.companionOfId)
        assertEquals("MUSIC/A/B/01 - T.lrc", lrc.forceable!!.targetPath)
        assertEquals("MUSIC/A/B/01 - T.flac", audio.forceable!!.targetPath)
        // seçim: yardımcı seçili ama ses seçili değilse bağ kaldırılır
        val resolved = r.plan.resolveSelection(setOf("Download/dup.lrc"))
        assertNull(resolved.single().companionOfId)
    }

    @Test fun orphanLrcAndCoverInFlatFolderAreSkipped() {
        val r = compute(listOf(sf("Download/lonely.lrc", 20), sf("Download/cover.jpg", 30)))
        assertTrue(r.plan.items.isEmpty())
        assertEquals(setOf(SkipReason.ORPHAN_AUXILIARY), r.plan.skipped.map { it.reason }.toSet())
    }

    @Test fun orphanAuxiliariesInStructuredFoldersArePreserved() {
        val r = compute(listOf(sf("Music/Artist/Album/lonely.lrc", 20), sf("Music/Artist/Album/cover.jpg", 30)))
        assertEquals(
            setOf("MUSIC/Artist/Album/lonely.lrc", "MUSIC/Artist/Album/cover.jpg"),
            r.plan.items.map { it.targetPath }.toSet(),
        )
    }

    @Test fun coverWithMultipleTargetDirsIsOrphanInTagMode() {
        fake.tags["Download/a.flac"] = tags("A", "B1", "One")
        fake.tags["Download/b.flac"] = tags("A", "B2", "Two", 2)
        val r = compute(listOf(sf("Download/a.flac"), sf("Download/b.flac"), sf("Download/cover.jpg", 40)))
        assertEquals(2, r.plan.items.size)
        assertEquals(SkipReason.ORPHAN_AUXILIARY, r.plan.skipped.single().reason)
    }

    @Test fun coverAlreadyInTargetDirIsAlreadyOnTargetRegardlessOfSize() {
        fake.tags["Download/a.flac"] = tags("A", "B", "One")
        val r = compute(listOf(sf("Download/a.flac"), sf("Download/cover.jpg", 40)), listOf(tf("MUSIC/A/B/Cover.jpg", 99999)))
        assertEquals(1, r.plan.items.size)
        assertEquals(SkipReason.ALREADY_ON_TARGET, r.plan.skipped.single().reason)
    }

    @Test fun twoCoversForSameTargetDirDoNotCollide() {
        fake.tags["Download/x/a.flac"] = tags("A", "B", "One")
        fake.tags["Download/y/b.flac"] = tags("A", "B", "Two", 2)
        val r = compute(listOf(sf("Download/x/a.flac"), sf("Download/y/b.flac"), sf("Download/x/cover.jpg", 10), sf("Download/y/cover.jpg", 11)))
        assertEquals(1, r.plan.items.count { it.targetPath == "MUSIC/A/B/cover.jpg" })
        assertEquals(SkipReason.DUPLICATE_IN_SOURCE, r.plan.skipped.single().reason)
    }

    @Test fun playlistsGoToPlaylistsDirInTagModeAndCollideSafely() {
        val r = compute(listOf(sf("Download/mix.m3u8", 12)), listOf(tf("MUSIC/Playlists/mix.m3u8", 99)))
        val s = r.plan.skipped.single()
        assertEquals(SkipReason.TARGET_PATH_OCCUPIED, s.reason)
        assertEquals("MUSIC/Playlists/mix (2).m3u8", s.forceable!!.targetPath)
        val r2 = compute(listOf(sf("Download/mix.m3u8", 12)))
        assertEquals("MUSIC/Playlists/mix.m3u8", r2.plan.items.single().targetPath)
        val r3 = compute(listOf(sf("Download/mix.m3u8", 12)), listOf(tf("MUSIC/Playlists/MIX.M3U8", 12)))
        assertEquals(SkipReason.ALREADY_ON_TARGET, r3.plan.skipped.single().reason)
    }

    @Test fun auxiliaryIndexRulesApply() {
        val idx = indexOf(entry("Music/A/B/x.lrc", 20, "MUSIC/A/B/x.lrc"))
        val r = compute(listOf(sf("Music/A/B/x.lrc", 20)), listOf(tf("MUSIC/A/B/x.lrc", 20)), idx)
        assertEquals(SkipReason.ALREADY_SYNCED, r.plan.skipped.single().reason)
        val r2 = compute(listOf(sf("Music/A/B/x.lrc", 21)), listOf(tf("MUSIC/A/B/x.lrc", 20)), idx)
        val s = r2.plan.skipped.single()
        assertEquals(SkipReason.CHANGED_SINCE_SYNC, s.reason)
        assertEquals("MUSIC/A/B/x (2).lrc", s.forceable!!.targetPath)
    }

    // ---------- tüm planın değişmezleri, ilerleme, hatalar ----------

    @Test fun progressReportsTagReadingThenPlanning() {
        for (i in 0 until 25) fake.tags["Download/f$i.flac"] = tags("A", "B", "T$i", i + 1)
        val events = Collections.synchronizedList(ArrayList<ScanProgress>())
        compute((0 until 25).map { sf("Download/f$it.flac") }, progress = { events += it })
        val list = events.toList()
        assertEquals(ScanPhase.READING_TAGS, list.first().phase)
        assertEquals(25, list.first().itemsTotal)
        assertEquals(ScanPhase.PLANNING, list.last().phase)
        assertTrue(list.any { it.phase == ScanPhase.READING_TAGS && it.itemsDone == 25 })
    }

    @Test fun fatalVolumeErrorsPropagateAndCancellationIsNotSwallowed() {
        fake.failWith = SyncException(SyncError.VolumeMissing(VolumeRole.SOURCE, srcId))
        try {
            compute(listOf(sf("Download/a.flac")))
            fail()
        } catch (e: SyncException) {
            assertTrue(e.error is SyncError.VolumeMissing)
        }
        fake.failWith = null
    }

    @Test fun nullTagsStillPlanWithDefaults() {
        val r = compute(listOf(sf("Download/mystery.mp3")))
        val item = r.plan.items.single()
        assertEquals("MUSIC/Unknown Artist/Unknown Album/mystery.mp3", item.targetPath)
        assertNull(item.contentKey)
        assertEquals(CopyReason.NEW_TAG_PLACED, item.reason)
    }

    @Test fun planInvariantsHoldOnLargeMixedInput() {
        val source = ArrayList<SourceFile>()
        for (i in 0 until 300) {
            val folder = listOf("Download", "Telegram/Audio", "Music/Art$i/Alb", "Bluetooth")[i % 4]
            source += sf("$folder/Track ${i % 40}.${listOf("flac", "mp3", "m4a", "opus")[i % 4]}", 100L + i)
            if (i % 7 == 0) source += sf("$folder/Track ${i % 40}.lrc", 5)
            if (i % 11 == 0) source += sf("$folder/cover.jpg", 6)
            fake.tags["$folder/Track ${i % 40}.${listOf("flac", "mp3", "m4a", "opus")[i % 4]}"] =
                tags("Artist ${i % 5}", "Album ${i % 3}", "Title ${i % 40}", i % 40 + 1, 100_000L + (i % 3) * 7000)
        }
        val r = compute(source.distinctBy { it.relativePath })
        val ids = r.plan.items.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val companions = r.plan.items.filter { it.companionOfId != null }
        for (c in companions) assertTrue(ids.indexOf(c.companionOfId!!) in 0 until ids.indexOf(c.id))
        assertEquals(
            r.plan.items.size + r.plan.skipped.size,
            source.distinctBy { it.relativePath }.size,
        )
    }

    // ---------- gerçek TagService + bellek içi ağaçlarla uçtan uca ----------

    @Test fun endToEndWithRealParsersAndTrees() {
        val mp3 = Id3Fixture.mp3(
            Id3Fixture.tag(3, listOf(Id3Fixture.textFrame(3, "TPE1", "Real Artist"), Id3Fixture.textFrame(3, "TALB", "Real Album"),
                Id3Fixture.textFrame(3, "TIT2", "Real Title"), Id3Fixture.textFrame(3, "TRCK", "2/9"))), 20,
        )
        val flac = FlacFixture.file(44100, 44100L * 30, FlacFixture.vorbisComment("ARTIST" to "Real Artist", "ALBUM" to "Real Album", "TITLE" to "Other", "TRACKNUMBER" to "5"))
        srcVol.putFile("Download/one.mp3", mp3)
        srcVol.putFile("Download/two.flac", flac)
        srcVol.putFile("Download/note.txt", ByteArray(5))
        val src = InMemorySourceTree(srcVol)
        val dst = InMemoryTargetTree(dstVol)
        val scanned = runBlocking { scan(src, src.root.handle) }
        val real = DefaultDeltaEngine(
            DefaultTagService(CompositeTagReader(), TagFallback.None, AppDispatchers.Default, Logger.None),
            DefaultPathPlanner(FatFileNameSanitizer()), AppDispatchers.Default, clock, Logger.None,
        )
        val result = runBlocking {
            real.computePlan(
                DeltaRequest(
                    VolumePair(srcId, dstId, "1A2B-3C4D:", "ABCD-1234:"), src, dst,
                    TreeScanResult(scanned, emptyList(), emptyList(), emptyList()),
                    TreeScanResult(emptyList(), emptyList(), emptyList(), emptyList()),
                    SyncIndex.empty(dstId, 0), TargetFileSystem.EXFAT, 1L shl 30, emptySet(), 1,
                ),
            ) {}
        }
        assertEquals(
            listOf("MUSIC/Real Artist/Real Album/02 - Real Title.mp3", "MUSIC/Real Artist/Real Album/05 - Other.flac"),
            result.plan.items.map { it.targetPath },
        )
        assertEquals(30_000L, result.plan.items[1].tags!!.durationMs)
        assertEquals(20L * 417 * 8 / 128, result.plan.items[0].tags!!.durationMs)
    }

    private suspend fun scan(tree: InMemorySourceTree, dir: SourceHandle): List<SourceFile> {
        val out = ArrayList<SourceFile>()
        for (n in tree.list(dir)) {
            if (n.isDirectory) {
                if (!ScanExclusions.isExcludedDirectory(n.name, n.relativePath.substringBeforeLast('/', ""))) out += scan(tree, n.handle)
            } else if (ScanExclusions.isCandidateFile(n.name)) {
                out += n.toFileEntry()
            }
        }
        return out.sortedBy { it.relativePath }
    }
}
