package com.walkmansync.core.path.impl

import com.walkmansync.core.model.AudioFormat
import com.walkmansync.core.model.AudioTags
import com.walkmansync.core.model.SourceFile
import com.walkmansync.core.model.TargetLayout
import com.walkmansync.core.model.TargetNameRules
import com.walkmansync.core.path.FileNameSanitizer
import com.walkmansync.core.path.FlatFolderRule
import com.walkmansync.core.path.PathPlanner
import com.walkmansync.core.path.PlacementMode
import java.util.Locale

/** Hedef yol planlayıcı: [FlatFolderRule] + etiket tabanlı adlandırma + çakışma çözümü. Saf, thread-safe. */
class DefaultPathPlanner(private val sanitizer: FileNameSanitizer) : PathPlanner {

    override fun planAudio(source: SourceFile, sourceRootPath: String, tags: AudioTags?): String {
        val cls = FlatFolderRule.classify(source.relativePath, sourceRootPath)
        val ext = AudioFormat.extensionOf(source.name)
        val original = originalBody(source.name)
        return if (cls.mode == PlacementMode.TAG_BASED) {
            val dirs = listOf(
                sanitizer.sanitizeDirComponent(tags?.folderArtist?.takeIf { it.isNotBlank() } ?: TargetNameRules.UNKNOWN_ARTIST),
                sanitizer.sanitizeDirComponent(tags?.album?.takeIf { it.isNotBlank() } ?: TargetNameRules.UNKNOWN_ALBUM),
            )
            join(dirs, sanitizer.sanitizeFileName(tagBody(tags, original), ext))
        } else {
            join(preservedDirs(cls.preservedDirs), sanitizer.sanitizeFileName(original, ext))
        }
    }

    override fun planStandalone(source: SourceFile, sourceRootPath: String): String {
        val cls = FlatFolderRule.classify(source.relativePath, sourceRootPath)
        val format = source.format
        val name = auxFileName(source, format)
        return if (cls.mode == PlacementMode.PRESERVE_STRUCTURE) {
            join(preservedDirs(cls.preservedDirs), name)
        } else if (format == AudioFormat.M3U8) {
            join(listOf(sanitizer.sanitizeDirComponent(TargetNameRules.PLAYLISTS_DIR)), name)
        } else {
            // Sözleşme (PathPlanner.planStandalone): fark motoru burayı çağırmaz (ORPHAN_AUXILIARY); toplam kalsın diye
            // geçerli, deterministik bir yedek yol döner.
            join(
                listOf(
                    sanitizer.sanitizeDirComponent(TargetNameRules.UNKNOWN_ARTIST),
                    sanitizer.sanitizeDirComponent(TargetNameRules.UNKNOWN_ALBUM),
                ),
                name,
            )
        }
    }

    override fun planCompanion(audioTargetPath: String, companion: SourceFile): String {
        val dir = audioTargetPath.substringBeforeLast('/', missingDelimiterValue = TargetLayout.MUSIC_DIR)
        val name = if (companion.format == AudioFormat.COVER) {
            AudioFormat.COVER_FILE_NAME
        } else {
            val audioName = audioTargetPath.substringAfterLast('/')
            val body = if (audioName.lastIndexOf('.') > 0) audioName.substringBeforeLast('.') else audioName
            sanitizer.sanitizeFileName(body, AudioFormat.extensionOf(companion.name).ifEmpty { "lrc" })
        }
        return "$dir/$name"
    }

    override fun uniquify(desired: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(desired)) return desired
        val dir = desired.substringBeforeLast('/', missingDelimiterValue = "")
        val name = desired.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val body = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (n in 2..MAX_ATTEMPTS) {
            val suffix = " ($n)"
            val room = TargetNameRules.MAX_FILE_NAME - ext.length - suffix.length
            var b = body
            if (b.length > room) {
                var cut = room.coerceAtLeast(1)
                if (cut > 0 && Character.isHighSurrogate(b[cut - 1])) cut--
                b = b.substring(0, cut).trimEnd(' ', '.').ifEmpty { "_" }
            }
            val candidate = (if (dir.isEmpty()) "" else "$dir/") + b + suffix + ext
            if (!isTaken(candidate)) return candidate
        }
        throw IllegalStateException("Benzersiz ad bulunamadı: $desired")
    }

    private fun tagBody(tags: AudioTags?, original: String): String {
        val title = tags?.title?.takeIf { it.isNotBlank() } ?: return original.ifBlank { UNTITLED }
        val track = tags.trackNumber?.takeIf { it > 0 } ?: return title
        val nn = if (track < 100) String.format(Locale.ROOT, "%02d", track) else track.toString()
        val disc = tags.discNumber?.takeIf { it >= 2 }
        val prefix = if (disc != null) "$disc-$nn" else nn
        return "$prefix - $title"
    }

    private fun preservedDirs(dirs: List<String>): List<String> {
        val kept = if (dirs.size > TargetNameRules.MAX_DIR_DEPTH) dirs.takeLast(TargetNameRules.MAX_DIR_DEPTH) else dirs
        return kept.map { sanitizer.sanitizeDirComponent(it) }
    }

    private fun auxFileName(source: SourceFile, format: AudioFormat?): String = when (format) {
        AudioFormat.COVER -> AudioFormat.COVER_FILE_NAME
        else -> sanitizer.sanitizeFileName(originalBody(source.name), AudioFormat.extensionOf(source.name))
    }

    private fun originalBody(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    private fun join(dirs: List<String>, fileName: String): String =
        (listOf(TargetLayout.MUSIC_DIR) + dirs + fileName).joinToString("/")

    private companion object {
        const val MAX_ATTEMPTS = 999
        const val UNTITLED = "Untitled"
    }
}
