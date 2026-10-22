package com.walkmansync.core.engine

import com.walkmansync.core.index.IndexEntry
import com.walkmansync.core.index.IndexLoadResult
import com.walkmansync.core.index.SyncIndex
import com.walkmansync.core.model.SyncError
import com.walkmansync.core.model.SyncException
import com.walkmansync.core.model.TargetFileSystem
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.testing.InMemoryTargetTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class TargetIndexStoreTest {
    private val INDEX = ".walkman_sync_index.json"
    private val BAK = ".walkman_sync_index.json.bak"

    private fun entry(n: Int) = IndexEntry("Download/$n.flac", 100L + n, 5, "MUSIC/$n.flac", 100L + n, null, 9)
    private fun indexWith(n: Int, volume: VolumeId = DST_ID): SyncIndex =
        SyncIndex.empty(volume, 1).withEntries((1..n).map(::entry), 2, SRC_ID)

    @Test fun missingWhenNothingOnCard() = runBlocking {
        val rig = Rig()
        assertEquals(IndexLoadResult.Missing, rig.store.load(rig.target))
    }

    @Test fun saveLoadRoundTrip() = runBlocking {
        val rig = Rig()
        rig.store.save(rig.target, indexWith(3))
        val loaded = rig.store.load(rig.target) as IndexLoadResult.Loaded
        assertFalse(loaded.recoveredFromBackup)
        assertEquals(3, loaded.index.entries.size)
        assertEquals("MUSIC/2.flac", loaded.index.entries[1].targetPath)
        assertTrue(rig.partFiles().isEmpty())
        assertFalse(rig.dstVol.exists(BAK))
    }

    @Test fun secondSaveRotatesPreviousToBackup() = runBlocking {
        val rig = Rig()
        rig.store.save(rig.target, indexWith(1))
        rig.store.save(rig.target, indexWith(2))
        rig.store.save(rig.target, indexWith(3))
        assertEquals(3, (rig.store.load(rig.target) as IndexLoadResult.Loaded).index.entries.size)
        val bak = rig.codec.decode(String(rig.dstVol.read(BAK)!!, Charsets.UTF_8))
        assertEquals(2, bak.entries.size)
        assertTrue(rig.partFiles().isEmpty())
    }

    @Test fun corruptMainRecoversFromBackup() = runBlocking {
        val rig = Rig()
        rig.store.save(rig.target, indexWith(1))
        rig.store.save(rig.target, indexWith(2))
        rig.dstVol.putFile(INDEX, "{{ garbage".toByteArray())
        val loaded = rig.store.load(rig.target) as IndexLoadResult.Loaded
        assertTrue(loaded.recoveredFromBackup)
        assertEquals(1, loaded.index.entries.size)
    }

    @Test fun missingMainWithBackupRecoversMidSwapState() = runBlocking {
        val rig = Rig()
        rig.dstVol.putFile(BAK, rig.codec.encode(indexWith(2)).toByteArray())
        val loaded = rig.store.load(rig.target) as IndexLoadResult.Loaded
        assertTrue(loaded.recoveredFromBackup)
        assertEquals(2, loaded.index.entries.size)
    }

    @Test fun corruptMainAndBackupReportsCorrupt() = runBlocking {
        val rig = Rig()
        rig.dstVol.putFile(INDEX, "xx".toByteArray())
        rig.dstVol.putFile(BAK, "yy".toByteArray())
        assertTrue(rig.store.load(rig.target) is IndexLoadResult.Corrupt)
        val rig2 = Rig()
        rig2.dstVol.putFile(INDEX, "xx".toByteArray())
        assertTrue(rig2.store.load(rig2.target) is IndexLoadResult.Corrupt)
    }

    @Test fun foreignVolumeIndexIsIgnoredNotDeleted() = runBlocking {
        val rig = Rig()
        val other = VolumeId.require("FFFF-0000")
        val bytes = rig.codec.encode(indexWith(2, other)).toByteArray()
        rig.dstVol.putFile(INDEX, bytes)
        val r = rig.store.load(rig.target)
        assertEquals(IndexLoadResult.Foreign("FFFF-0000"), r)
        assertTrue(bytes.contentEquals(rig.dstVol.read(INDEX)))
    }

    @Test fun newerSchemaIsUnsupportedAndLeftAlone() = runBlocking {
        val rig = Rig()
        val newer = "v=99\nwhatever\n".toByteArray()
        rig.dstVol.putFile(INDEX, newer)
        assertEquals(IndexLoadResult.Unsupported(99), rig.store.load(rig.target))
        assertTrue(newer.contentEquals(rig.dstVol.read(INDEX)))
    }

    @Test fun oversizedIndexCountsAsCorrupt() = runBlocking {
        val rig = Rig()
        rig.dstVol.putFile(INDEX, ByteArray((SyncIndex.MAX_FILE_BYTES + 1).toInt()))
        assertTrue(rig.store.load(rig.target) is IndexLoadResult.Corrupt)
    }

    @Test fun staleIndexPartIsReplacedOnSave() = runBlocking {
        val rig = Rig()
        rig.dstVol.putFile("$INDEX.wsync.part", "stale".toByteArray())
        rig.store.save(rig.target, indexWith(1))
        assertEquals(1, (rig.store.load(rig.target) as IndexLoadResult.Loaded).index.entries.size)
        assertTrue(rig.partFiles().isEmpty())
    }

    @Test fun failedSaveKeepsPreviousIndexAndLeavesNoPart() = runBlocking {
        val rig = Rig()
        rig.store.save(rig.target, indexWith(2))
        rig.dstVol.onWrite = { path, _ -> if (path.endsWith(".wsync.part")) throw IOException("EIO") }
        try {
            rig.store.save(rig.target, indexWith(5))
            fail("fırlatmalıydı")
        } catch (e: SyncException) {
            assertTrue(e.error is SyncError.IoFailure)
        }
        rig.dstVol.onWrite = null
        assertTrue(rig.partFiles().isEmpty())
        assertEquals(2, (rig.store.load(rig.target) as IndexLoadResult.Loaded).index.entries.size)
    }

    @Test fun ejectedCardDuringSaveIsVolumeMissing() = runBlocking {
        val rig = Rig()
        rig.store.save(rig.target, indexWith(1))
        rig.dstVol.onWrite = { _, _ -> rig.dstVol.mounted = false; throw IOException("EIO") }
        try {
            rig.store.save(rig.target, indexWith(2))
            fail("fırlatmalıydı")
        } catch (e: SyncException) {
            assertTrue(e.error is SyncError.VolumeMissing)
        }
        rig.dstVol.mounted = true
        rig.dstVol.onWrite = null
        assertEquals(1, (rig.store.load(rig.target) as IndexLoadResult.Loaded).index.entries.size)
    }

    @Test fun musicRootKeepsIndexUnderMusic() = runBlocking {
        val rig = Rig(targetRoot = "MUSIC")
        rig.store.save(rig.target, indexWith(1))
        rig.store.save(rig.target, indexWith(2))
        assertTrue(rig.dstVol.exists("MUSIC/$INDEX"))
        assertTrue(rig.dstVol.exists("MUSIC/$BAK"))
        assertFalse(rig.dstVol.exists(INDEX))
        assertEquals(2, (rig.store.load(rig.target) as IndexLoadResult.Loaded).index.entries.size)
    }

    @Test fun rootTreeFileSystemDoesNotMatter() = runBlocking {
        val rig = Rig()
        val fat = InMemoryTargetTree(rig.dstVol, TargetFileSystem.FAT32)
        rig.store.save(fat, indexWith(1))
        assertEquals(1, (rig.store.load(fat) as IndexLoadResult.Loaded).index.entries.size)
    }
}
