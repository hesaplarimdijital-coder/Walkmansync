package com.walkmansync.core.path.impl

import com.walkmansync.core.model.TargetNameRules
import com.walkmansync.core.path.FileNameSanitizer
import java.text.Normalizer
import java.util.Locale

/** exFAT/FAT32 ad temizleyici (bkz. [FileNameSanitizer] kuralları). Durumsuz, thread-safe, idempotent. */
class FatFileNameSanitizer : FileNameSanitizer {

    override fun sanitizeDirComponent(raw: String): String {
        var s = clean(raw)
        s = truncate(s, TargetNameRules.MAX_DIR_COMPONENT)
        s = s.ifEmpty { FALLBACK }
        s = guardReserved(s)
        if (s.startsWith('.')) s = "_$s"
        if (s.length > TargetNameRules.MAX_DIR_COMPONENT) s = truncate(s, TargetNameRules.MAX_DIR_COMPONENT).ifEmpty { FALLBACK }
        return s
    }

    override fun sanitizeFileName(baseName: String, extension: String): String {
        val ext = cleanExtension(extension)
        val extPart = if (ext.isEmpty()) "" else ".$ext"
        // Çok uzun uzantı gövdeye yer bırakmazsa uzantı da gövdenin parçası sayılıp bütünüyle kısaltılır.
        if (extPart.length > TargetNameRules.MAX_FILE_NAME / 2) {
            return sanitizeDirComponent(baseName + extPart).let { truncate(it, TargetNameRules.MAX_FILE_NAME) }
        }
        var body = clean(baseName)
        body = truncate(body, TargetNameRules.MAX_FILE_NAME - extPart.length)
        body = body.ifEmpty { FALLBACK }
        body = guardReserved(body)
        if (body.startsWith('.')) body = "_$body"
        val maxBody = TargetNameRules.MAX_FILE_NAME - extPart.length
        if (body.length > maxBody) body = truncate(body, maxBody).ifEmpty { FALLBACK }
        return body + extPart
    }

    /** Adım 1-3: NFC, yasak/kontrol karakterleri, boşluk birleştirme, baş/son kırpma. */
    private fun clean(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val sb = StringBuilder(nfc.length)
        var i = 0
        while (i < nfc.length) {
            val c = nfc[i]
            when {
                Character.isHighSurrogate(c) && i + 1 < nfc.length && Character.isLowSurrogate(nfc[i + 1]) -> {
                    sb.append(c).append(nfc[i + 1]); i++
                }
                Character.isSurrogate(c) -> sb.append(TargetNameRules.REPLACEMENT)
                Character.isISOControl(c) || TargetNameRules.FORBIDDEN_CHARS.indexOf(c) >= 0 ->
                    sb.append(TargetNameRules.REPLACEMENT)
                c == ' ' && sb.isNotEmpty() && sb[sb.length - 1] == ' ' -> Unit
                else -> sb.append(c)
            }
            i++
        }
        return trimEdges(sb.toString())
    }

    /** Baştaki boşlukları, sondaki boşluk ve noktaları siler. */
    private fun trimEdges(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && s[start] == ' ') start++
        while (end > start && (s[end - 1] == ' ' || s[end - 1] == '.')) end--
        return s.substring(start, end)
    }

    /** UTF-16 sınırı; vekil çift bölünmez; kırpma sonrası kenarlar yeniden temizlenir. */
    private fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        var cut = max
        if (cut > 0 && Character.isHighSurrogate(s[cut - 1])) cut--
        return trimEdges(s.substring(0, cut))
    }

    /** Uzantısız gövde (ilk noktadan öncesi) ayrılmış DOS adıysa sonuna '_' eklenir. */
    private fun guardReserved(s: String): String {
        val stem = s.substringBefore('.')
        if (stem.uppercase(Locale.ROOT) !in TargetNameRules.RESERVED_NAMES) return s
        return stem + "_" + s.substring(stem.length)
    }

    private fun cleanExtension(raw: String): String {
        val sb = StringBuilder()
        for (c in Normalizer.normalize(raw, Normalizer.Form.NFC)) {
            if (Character.isISOControl(c) || Character.isSurrogate(c) || c == ' ' || c == '.' ||
                TargetNameRules.FORBIDDEN_CHARS.indexOf(c) >= 0
            ) continue
            sb.append(c)
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    private companion object {
        const val FALLBACK = "_"
    }
}
