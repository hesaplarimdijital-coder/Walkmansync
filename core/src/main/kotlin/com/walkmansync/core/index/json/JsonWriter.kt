package com.walkmansync.core.index.json

/** Kararlı çıktı üreten küçük JSON yazıcı yardımcıları. */
internal object JsonWriter {
    /** [s]'yi tırnaklı JSON dizesi olarak ekler. Kontrol karakterleri ve eşlenmemiş vekiller \uXXXX ile kaçırılır. */
    fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' || c == '\u007F' || c == ' ' || c == ' ' -> unicode(sb, c)
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1]); i++
                }
                Character.isSurrogate(c) -> unicode(sb, c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    fun appendNullableString(sb: StringBuilder, s: String?) {
        if (s == null) sb.append("null") else appendString(sb, s)
    }

    private fun unicode(sb: StringBuilder, c: Char) {
        sb.append("\\u")
        val hex = Integer.toHexString(c.code)
        for (k in hex.length until 4) sb.append('0')
        sb.append(hex)
    }
}
