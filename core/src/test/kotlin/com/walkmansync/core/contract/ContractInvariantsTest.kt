// MİMAR — değiştirilmez. Sözleşme değişmezlerinin testleri.
package com.walkmansync.core.contract

import com.walkmansync.core.fs.TargetGuardViolation
import com.walkmansync.core.index.ContentKey
import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.model.DocumentIds
import com.walkmansync.core.model.TargetLayout
import com.walkmansync.core.model.VolumeId
import com.walkmansync.core.model.VolumePair
import com.walkmansync.core.path.FlatFolderRule
import com.walkmansync.core.path.PlacementMode
import com.walkmansync.core.testing.InMemorySourceTree
import com.walkmansync.core.testing.InMemoryTargetTree
import com.walkmansync.core.testing.InMemoryVolume
import com.walkmansync.core.transfer.TransferControl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContractInvariantsTest {
    private val a = VolumeId.require("abcd-1234")
    private val b = VolumeId.require("1A2B-3C4D")

    @Test fun volumeIdNormalizesAndRejectsReserved() {
        assertEquals("ABCD-1234", a.uuid)
        assertNull(VolumeId.of("primary"))
        assertNull(VolumeId.of(" "))
        assertNull(VolumeId.of("AB:CD"))
    }

    @Test fun documentIdsParse() {
        assertEquals(DocumentIds.Parsed(a, "Music/A"), DocumentIds.parse("ABCD-1234:Music/A/"))
        assertNull(DocumentIds.parse("primary:Music"))
        assertNull(DocumentIds.parse("nocolon"))
        assertTrue(DocumentIds.belongsTo("abcd-1234:x", a))
        assertFalse(DocumentIds.belongsTo("1A2B-3C4D:x", a))
    }

    @Test fun volumePairEnforcesUuidRules() {
        VolumePair(b, a, "1A2B-3C4D:Music", "ABCD-1234:")
        expectIae { VolumePair(a, a, "ABCD-1234:", "ABCD-1234:") }
        expectIae { VolumePair(b, a, "ABCD-1234:Music", "ABCD-1234:") } // kaynak öneki yanlış
        VolumePair(b, a, "1A2B-3C4D:", "ABCD-1234:Music") // MUSIC kökü kabul
        expectIae { VolumePair(b, a, "1A2B-3C4D:", "ABCD-1234:MUSIC/Pop") } // başka klasör
    }

    @Test fun audioFormatRecognition() {
        assertEquals(AudioFormat.FLAC, AudioFormat.fromFileName("x.FLAC"))
        assertEquals(AudioFormat.ALAC, AudioFormat.fromFileName("x.alac"))
        assertEquals(AudioFormat.COVER, AudioFormat.fromFileName("Cover.JPG"))
        assertNull(AudioFormat.fromFileName("front.jpg"))
        assertNull(AudioFormat.fromFileName(".flac"))
    }

    @Test fun targetLayoutPaths() {
        assertTrue(TargetLayout.isValidTargetFilePath("MUSIC/A/B/01 - T.flac"))
        assertTrue(TargetLayout.isValidTargetFilePath("music/x.mp3"))
        assertFalse(TargetLayout.isValidTargetFilePath("Download/x.mp3"))
        assertFalse(TargetLayout.isValidTargetFilePath("MUSIC/../x.mp3"))
        assertFalse(TargetLayout.isValidTargetFilePath("MUSIC/x.mp3.wsync.part"))
        assertEquals("a.flac", TargetLayout.finalNameOf("a.flac.wsync.part"))
        assertNull(TargetLayout.finalNameOf("a.flac.part"))
    }

    @Test fun flatFolderRule() {
        fun mode(p: String, root: String = "") = FlatFolderRule.classify(p, root)
        assertEquals(PlacementMode.TAG_BASED, mode("Download/x.mp3").mode)
        assertEquals(PlacementMode.TAG_BASED, mode("Download/Album/x.mp3").mode)
        assertEquals(PlacementMode.TAG_BASED, mode("Music/x.mp3").mode)
        assertEquals(PlacementMode.TAG_BASED, mode("Music/Album/01.flac").mode)
        assertEquals(PlacementMode.TAG_BASED, mode("Telegram/Telegram Audio/x.opus").mode)
        assertEquals(FlatFolderRule.Classification(PlacementMode.PRESERVE_STRUCTURE, listOf("Artist", "Album")),
            mode("Music/Artist/Album/01.flac"))
        assertEquals(listOf("HiRes", "Artist", "Album", "CD1"), mode("HiRes/Artist/Album/CD1/01.dsf").preservedDirs)
        // kullanıcı kökü "Music" seçtiyse göreli yapı aynı
        assertEquals(listOf("Artist", "Album"), mode("Music/Artist/Album/01.flac", "Music").preservedDirs)
    }

    @Test fun contentKeyNormalizationAndCandidates() {
        val k1 = ContentKey.of(AudioTags(artist = "Beyoncé", album = "X", title = "Halo", trackNumber = 3, durationMs = 241_000))
        val k2 = ContentKey.of(AudioTags(artist = "beyonce ", album = "x", title = "HALO", trackNumber = 3, durationMs = 241_400))
        assertEquals(k1, k2)
        assertEquals("v1|beyonce|x|0|3|halo|121", k1!!.value)
        assertEquals(3, k1.candidates().size)
        assertNull(ContentKey.of(AudioTags(album = "x", title = "t")))
        assertEquals("isik", ContentKey.normalize("Işık"))
        assertEquals("ac dc", ContentKey.normalize("AC/DC"))
    }

    @Test fun guardedTargetTreeBlocksNonAppendOnlyOps() = runBlocking {
        val vol = InMemoryVolume(a)
        vol.putFile("MUSIC/A/x.flac", 10)
        val t = InMemoryTargetTree(vol)
        val existing = t.resolve("MUSIC/A/x.flac")!!
        expectGuard { t.delete(existing) }
        expectGuard { t.createFile(t.resolve("MUSIC/A")!!.handle, "y.flac") }
        expectGuard { t.ensureDirectory("Download") }
        expectGuard { t.openOutput(existing) }
        val dir = t.ensureDirectory("music/a/B")
        assertEquals("MUSIC/A/B", dir.relativePath) // mevcut harf büyüklüğü korunur
        val part = t.createFile(dir.handle, "y.flac.wsync.part")
        t.openOutput(part).use { it.write(byteArrayOf(1, 2, 3), 0, 3); it.sync() }
        expectGuard { t.rename(part, "z.flac") }
        val done = t.rename(part, "y.flac")
        assertEquals("MUSIC/A/B/y.flac", done.relativePath)
        assertEquals(3L, done.size)
        expectGuard { t.delete(done) }
        // MUSIC köklü ağaçta indeks MUSIC altında; yedek yalnızca orada silinebilir
        val tm = InMemoryTargetTree(vol, rootPath = "MUSIC")
        assertEquals("MUSIC/.walkman_sync_index.json", TargetLayout.indexPathFor(tm.root.relativePath))
        val ip = tm.createIndexPart()
        assertEquals("MUSIC/.walkman_sync_index.json.wsync.part", ip.relativePath)
        tm.rename(ip, TargetLayout.INDEX_FILE_NAME)
        assertTrue(vol.exists("MUSIC/.walkman_sync_index.json"))
        val src = InMemorySourceTree(InMemoryVolume(b))
        assertNotEquals(src.volume, t.volume)
    }

    @Test fun transferControlCancelReleasesPause() = runBlocking {
        val c = TransferControl()
        c.pause(); c.cancel()
        assertTrue(c.awaitResumedOrCancelled())
    }

    private inline fun expectIae(block: () -> Unit) {
        try { block(); fail("IllegalArgumentException bekleniyordu") } catch (_: IllegalArgumentException) {}
    }

    private suspend fun expectGuard(block: suspend () -> Unit) {
        try { block(); fail("TargetGuardViolation bekleniyordu") } catch (_: TargetGuardViolation) {}
    }
}
