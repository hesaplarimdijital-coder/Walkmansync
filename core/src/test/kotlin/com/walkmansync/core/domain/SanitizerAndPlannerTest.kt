package com.walkmansync.core.domain

import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.model.SourceFile
import com.walkmansync.core.model.SourceHandle
import com.walkmansync.core.model.TargetLayout
import com.walkmansync.core.model.TargetNameRules
import com.walkmansync.core.path.impl.DefaultPathPlanner
import com.walkmansync.core.path.impl.FatFileNameSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.text.Normalizer

class SanitizerAndPlannerTest {
    private val s = FatFileNameSanitizer()
    private val planner = DefaultPathPlanner(s)

    private fun src(path: String, size: Long = 1000) =
        SourceFile(SourceHandle("1A2B-3C4D:$path"), path, path.substringAfterLast('/'), size, 0)

    // ---------- sanitizer ----------

    @Test fun forbiddenCharactersAndTrailingJunk() {
        assertEquals("AC_DC", s.sanitizeDirComponent("AC/DC"))
        assertEquals("Album", s.sanitizeDirComponent("Album..."))
        assertEquals("What_.flac", s.sanitizeFileName("What? ", "FLAC"))
        assertEquals("a_b_c_d_e_f_g_h_i.mp3", s.sanitizeFileName("a\"b*c/d:e<f>g?h\\i", "mp3"))
        assertEquals("x_y.mp3", s.sanitizeFileName("x|y", "mp3"))
        assertEquals("a_b", s.sanitizeDirComponent("a\u0001b"))
        assertEquals("a_b", s.sanitizeDirComponent("a\u007Fb"))
        assertEquals("a_b", s.sanitizeDirComponent("a\u0085b")) // C1 kontrol: GuardedTargetTree isISOControl ile reddeder
    }

    @Test fun whitespaceCollapsingAndTrimming() {
        assertEquals("a b c", s.sanitizeDirComponent("  a   b    c  "))
        assertEquals("a", s.sanitizeDirComponent("a. . ."))
    }

    @Test fun emptyBecomesUnderscore() {
        assertEquals("_", s.sanitizeDirComponent(""))
        assertEquals("_", s.sanitizeDirComponent("..."))
        assertEquals("_.flac", s.sanitizeFileName("   ", "flac"))
    }

    @Test fun reservedNames() {
        for (n in listOf("CON", "prn", "Aux", "NUL", "COM1", "lpt9")) {
            assertEquals("${n}_", s.sanitizeDirComponent(n))
            assertEquals("${n}_.flac", s.sanitizeFileName(n, "flac"))
        }
        assertEquals("COM10", s.sanitizeDirComponent("COM10"))
        assertEquals("CONSOLE", s.sanitizeDirComponent("CONSOLE"))
    }

    @Test fun leadingDotIsPrefixed() {
        assertEquals("_.hidden", s.sanitizeDirComponent(".hidden"))
        assertEquals("_.hidden.flac", s.sanitizeFileName(".hidden", "flac"))
    }

    @Test fun lengthLimitsKeepExtensionAndSurrogates() {
        val long = "a".repeat(500)
        assertEquals(TargetNameRules.MAX_DIR_COMPONENT, s.sanitizeDirComponent(long).length)
        val file = s.sanitizeFileName(long, "flac")
        assertEquals(TargetNameRules.MAX_FILE_NAME, file.length)
        assertTrue(file.endsWith(".flac"))
        // 😀 = vekil çift; sınırda bölünmemeli.
        val emoji = "😀".repeat(200)
        val dir = s.sanitizeDirComponent(emoji)
        assertTrue(dir.length <= TargetNameRules.MAX_DIR_COMPONENT)
        assertFalse(Character.isHighSurrogate(dir.last()))
        val f = s.sanitizeFileName("a" + "😀".repeat(300), "opus")
        assertTrue(f.length <= TargetNameRules.MAX_FILE_NAME)
        assertTrue(f.endsWith(".opus"))
        assertFalse(Character.isHighSurrogate(f[f.length - 6]))
    }

    @Test fun nfcNormalization() {
        val nfd = Normalizer.normalize("Şarkı Ünlü é", Normalizer.Form.NFD)
        assertEquals(Normalizer.normalize(nfd, Normalizer.Form.NFC), s.sanitizeDirComponent(nfd))
        assertTrue(Normalizer.isNormalized(s.sanitizeFileName(nfd, "mp3"), Normalizer.Form.NFC))
    }

    @Test fun lonelySurrogatesReplaced() {
        assertEquals("a_b", s.sanitizeDirComponent("a\uD800b"))
    }

    @Test fun idempotentOnTrickyInputs() {
        val inputs = listOf(
            "AC/DC", "  .. x ..", "CON", "con.txt", "a".repeat(400), "😀".repeat(250), ".hidden", "What? ", "x\u0000y", "...",
            "Normal Name", "ends with dot.", "  ", "a  b", "COM1.x", "\u0301combining",
        )
        for (i in inputs) {
            val d = s.sanitizeDirComponent(i)
            assertEquals("dir idempotent: $i", d, s.sanitizeDirComponent(d))
            val f = s.sanitizeFileName(i, "FLAC")
            val body = f.removeSuffix(".flac")
            assertEquals("file idempotent: $i", f, s.sanitizeFileName(body, "flac"))
            assertTrue(f.none { it.isISOControl() })
        }
    }

    // ---------- planner ----------

    @Test fun tagBasedNamingWithTrackAndDisc() {
        val tags = AudioTags(artist = "Art", albumArtist = "AlbArt", album = "Alb", title = "T:itle", trackNumber = 3)
        assertEquals("MUSIC/AlbArt/Alb/03 - T_itle.flac", planner.planAudio(src("Download/x.FLAC"), "", tags))
        assertEquals(
            "MUSIC/Art/Alb/2-03 - Song.mp3",
            planner.planAudio(src("Download/x.mp3"), "", AudioTags(artist = "Art", album = "Alb", title = "Song", trackNumber = 3, discNumber = 2)),
        )
        assertEquals(
            "MUSIC/Art/Alb/03 - Song.mp3",
            planner.planAudio(src("Download/x.mp3"), "", AudioTags(artist = "Art", album = "Alb", title = "Song", trackNumber = 3, discNumber = 1)),
        )
        assertEquals(
            "MUSIC/Art/Alb/105 - Song.opus",
            planner.planAudio(src("x.opus"), "", AudioTags(artist = "Art", album = "Alb", title = "Song", trackNumber = 105)),
        )
        assertEquals(
            "MUSIC/Art/Alb/Song.m4a",
            planner.planAudio(src("Download/s.m4a"), "", AudioTags(artist = "Art", album = "Alb", title = "Song")),
        )
        assertEquals(
            "MUSIC/Art/Alb/01 - Song.alac",
            planner.planAudio(src("Download/s.alac"), "", AudioTags(artist = "Art", album = "Alb", title = "Song", trackNumber = 1)),
        )
    }

    @Test fun missingTagsUseDefaultsAndOriginalBody() {
        assertEquals("MUSIC/Unknown Artist/Unknown Album/My Song.mp3", planner.planAudio(src("Download/My Song.mp3"), "", null))
        assertEquals(
            "MUSIC/Unknown Artist/Unknown Album/orig name.flac",
            planner.planAudio(src("Download/orig name.flac"), "", AudioTags(trackNumber = 4)),
        )
        assertEquals(
            "MUSIC/Art/Unknown Album/orig.flac",
            planner.planAudio(src("Download/orig.flac"), "", AudioTags(artist = "Art")),
        )
    }

    @Test fun preserveStructureKeepsRelativeDirsAndLimitsDepth() {
        assertEquals("MUSIC/Artist/Album/01.flac", planner.planAudio(src("Music/Artist/Album/01.flac"), "", AudioTags(title = "Ignored")))
        assertEquals("MUSIC/Artist/Album/01.flac", planner.planAudio(src("Artist/Album/01.flac"), "", null))
        assertEquals("MUSIC/HiRes/A_B/Album/CD1/01.dsf", planner.planAudio(src("HiRes/A:B/Album/CD1/01.DSF"), "", null))
        val deep = (1..12).joinToString("/") { "d$it" }
        val out = planner.planAudio(src("Lib/$deep/x.flac"), "", null)
        val parts = out.split('/')
        assertEquals("MUSIC", parts[0])
        assertEquals(TargetNameRules.MAX_DIR_DEPTH, parts.size - 2)
        assertEquals("d12", parts[parts.size - 2]) // son parçalar korunur
        assertTrue(TargetLayout.isValidTargetFilePath(out))
    }

    @Test fun sourceRootSubfolderIsHonoured() {
        // Kaynak kökü "Music": "Music/Album/01.flac" sığ → TAG_BASED
        val tags = AudioTags(artist = "A", album = "B", title = "C", trackNumber = 1)
        assertEquals("MUSIC/A/B/01 - C.flac", planner.planAudio(src("Music/Album/01.flac"), "Music", tags))
    }

    @Test fun standaloneAndCompanionPaths() {
        assertEquals("MUSIC/Playlists/Road Trip.m3u8", planner.planStandalone(src("Download/Road Trip.m3u8"), ""))
        assertEquals("MUSIC/Artist/Album/list.m3u8", planner.planStandalone(src("Music/Artist/Album/list.m3u8"), ""))
        assertEquals("MUSIC/Artist/Album/cover.jpg", planner.planStandalone(src("Music/Artist/Album/Cover.JPG"), ""))
        assertEquals("MUSIC/A/B/01 - C.lrc", planner.planCompanion("MUSIC/A/B/01 - C.flac", src("Download/zzz.lrc")))
        assertEquals("MUSIC/A/B/cover.jpg", planner.planCompanion("MUSIC/A/B/01 - C.flac", src("Download/COVER.JPG")))
        assertEquals("MUSIC/A/B/x.lrc", planner.planCompanion("MUSIC/A/B/x.dsf", src("x.LRC")))
    }

    @Test fun standaloneTagBasedAuxiliaryIsTotalAndValid() {
        // Fark motoru bunu çağırmaz (ORPHAN_AUXILIARY); sözleşme yine de geçerli bir yedek yol garanti eder.
        val lrc = planner.planStandalone(src("Download/Song?.LRC"), "")
        val cover = planner.planStandalone(src("Download/Cover.jpg"), "")
        assertEquals("MUSIC/${TargetNameRules.UNKNOWN_ARTIST}/${TargetNameRules.UNKNOWN_ALBUM}/Song_.lrc", lrc)
        assertEquals("MUSIC/${TargetNameRules.UNKNOWN_ARTIST}/${TargetNameRules.UNKNOWN_ALBUM}/cover.jpg", cover)
        assertTrue(TargetLayout.isValidTargetFilePath(lrc) && TargetLayout.isValidTargetFilePath(cover))
    }

    @Test fun uniquifyAddsCounterAndKeepsExtension() {
        val taken = setOf("MUSIC/A/B/01 - C.flac", "MUSIC/A/B/01 - C (2).flac")
        assertEquals("MUSIC/A/B/01 - C (3).flac", planner.uniquify("MUSIC/A/B/01 - C.flac") { it in taken })
        assertEquals("MUSIC/A/B/free.flac", planner.uniquify("MUSIC/A/B/free.flac") { it in taken })
        // ad sınırı aşılırsa gövde kırpılır
        val longName = "MUSIC/A/" + "x".repeat(TargetNameRules.MAX_FILE_NAME - 5) + ".flac"
        val u = planner.uniquify(longName) { it == longName }
        assertTrue(u.substringAfterLast('/').length <= TargetNameRules.MAX_FILE_NAME)
        assertTrue(u.endsWith(" (2).flac"))
        try {
            planner.uniquify("MUSIC/a.flac") { true }
            fail()
        } catch (e: IllegalStateException) {
            // beklenen
        }
    }

    @Test fun plannedPathsAlwaysValid() {
        val nasty = listOf("CON", "..", "...", "a/b", "x\u0000", "", "  ", "😀", "AUX.flac", "a".repeat(300))
        for (t in nasty) for (a in nasty) {
            val p = planner.planAudio(src("Download/$t.flac".replace("//", "/")), "", AudioTags(artist = a, album = t, title = t, trackNumber = 1))
            assertTrue("geçersiz: $p", TargetLayout.isValidTargetFilePath(p))
            assertTrue(p.split('/').all { it.length <= TargetNameRules.MAX_FILE_NAME })
        }
    }
}
