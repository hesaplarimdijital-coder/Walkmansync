package com.walkmansync.core.index.json

import com.walkmansync.core.index.IndexFormatException

/**
 * Küçük, KATI, bellek dostu JSON çekme (pull) okuyucusu. Gson'un JsonReader'ına benzer ama yalnızca gerekenleri içerir:
 * tüm girdi tek `String`; yorum, sondaki virgül, tek tırnak, kaçışsız kontrol karakteri YOKTUR; çıktı sonrası artık
 * karakter hatadır. Sayılar tam sayı ise `Long` olarak okunur ([nextLong]); kesirli/üslü sayılar bilinmeyen alanlarda
 * [skipValue] ile atlanabilir. Biçim hataları [IndexFormatException] fırlatır.
 */
internal class JsonPullReader(private val s: String) {
    enum class Token { BEGIN_OBJECT, BEGIN_ARRAY, STRING, NUMBER, BOOLEAN, NULL }

    private var pos = 0
    private var depth = 0
    private val isObject = BooleanArray(MAX_DEPTH)
    private val count = IntArray(MAX_DEPTH)
    private var separatorDone = false

    init {
        if (s.isNotEmpty() && s[0] == '﻿') pos = 1
        skipWs()
    }

    /** Sıradaki değerin türü (tüketmez). */
    fun peek(): Token {
        valueSeparator()
        skipWs()
        return when (val c = charAt()) {
            '{' -> Token.BEGIN_OBJECT
            '[' -> Token.BEGIN_ARRAY
            '"' -> Token.STRING
            't', 'f' -> Token.BOOLEAN
            'n' -> Token.NULL
            '-', in '0'..'9' -> Token.NUMBER
            else -> fail("beklenmeyen karakter '$c'")
        }
    }

    fun beginObject() = begin('{', true)
    fun beginArray() = begin('[', false)

    private fun begin(open: Char, obj: Boolean) {
        valueSeparator()
        skipWs()
        if (charAt() != open) fail("'$open' bekleniyor")
        if (depth >= MAX_DEPTH) fail("iç içe geçme çok derin")
        pos++
        isObject[depth] = obj
        count[depth] = 0
        depth++
        separatorDone = false
    }

    fun endObject() = end('}')
    fun endArray() = end(']')

    private fun end(close: Char) {
        skipWs()
        if (charAt() != close) fail("'$close' bekleniyor")
        pos++
        depth--
        separatorDone = false
        if (depth > 0) count[depth - 1]++ // kapsayıcının bir öğesi tamamlandı
    }

    /** Geçerli kapsayıcıda başka öğe var mı ('}' / ']' görülürse false). Tekrar çağrılabilir. */
    fun hasNext(): Boolean {
        check(depth > 0) { "kapsayıcı yok" }
        skipWs()
        val c = charAt()
        if (c == '}' || c == ']') {
            if (separatorDone) fail("sondaki virgül")
            return false
        }
        if (count[depth - 1] > 0 && !separatorDone) {
            if (c != ',') fail("',' bekleniyor")
            pos++
            skipWs()
            separatorDone = true
            val d = charAt()
            if (d == '}' || d == ']') fail("sondaki virgül")
        }
        return true
    }

    fun nextName(): String {
        check(depth > 0 && isObject[depth - 1]) { "nesne içinde değil" }
        if (!hasNext()) fail("üye adı bekleniyor")
        skipWs()
        if (charAt() != '"') fail("üye adı bekleniyor")
        val name = readString()
        skipWs()
        if (charAt() != ':') fail("':' bekleniyor")
        pos++
        separatorDone = false
        skipWs()
        return name
    }

    fun nextString(): String {
        valueSeparator()
        skipWs()
        if (charAt() != '"') fail("dize bekleniyor")
        val v = readString()
        valueDone()
        return v
    }

    /** Tam sayıyı Long olarak okur; kesirli/üslü gösterim (ör. 5.0) kabul edilmez. */
    fun nextLong(): Long {
        valueSeparator()
        skipWs()
        val tok = readNumberToken()
        val v = tok.toLongOrNull() ?: fail("tam sayı bekleniyor: $tok")
        valueDone()
        return v
    }

    fun nextNull() {
        valueSeparator()
        skipWs()
        expectLiteral("null")
        valueDone()
    }

    /** Sıradaki değeri (iç içe dahil) okumadan geçer; yine de sözdizimini doğrular. */
    fun skipValue() {
        when (peek()) {
            Token.BEGIN_OBJECT -> {
                beginObject()
                while (hasNext()) { nextName(); skipValue() }
                endObject()
            }
            Token.BEGIN_ARRAY -> {
                beginArray()
                while (hasNext()) skipValue()
                endArray()
            }
            Token.STRING -> { valueSeparator(); skipWs(); readString(); valueDone() }
            Token.NUMBER -> { valueSeparator(); skipWs(); readNumberToken(); valueDone() }
            Token.BOOLEAN -> {
                valueSeparator(); skipWs()
                if (charAt() == 't') expectLiteral("true") else expectLiteral("false")
                valueDone()
            }
            Token.NULL -> nextNull()
        }
    }

    /** Belge sonu: yalnızca boşluk kalmış olmalı. */
    fun endDocument() {
        if (depth != 0) fail("kapanmamış kapsayıcı")
        skipWs()
        if (pos != s.length) fail("belge sonunda artık karakter")
    }

    // --- iç mekanizma ---

    /** Dizi öğesi başlıyorsa virgül/sayaç kuralını uygular (nesne üyesi değerleri nextName'de işlenir). */
    private fun valueSeparator() {
        if (depth > 0 && !isObject[depth - 1]) {
            if (!hasNext()) fail("değer bekleniyor")
        }
    }

    private fun valueDone() {
        if (depth > 0) count[depth - 1]++
        separatorDone = false
    }

    private fun skipWs() {
        while (pos < s.length) {
            val c = s[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else break
        }
    }

    private fun charAt(): Char {
        if (pos >= s.length) fail("beklenmeyen girdi sonu")
        return s[pos]
    }

    private fun expectLiteral(lit: String) {
        if (!s.startsWith(lit, pos)) fail("'$lit' bekleniyor")
        pos += lit.length
        if (pos < s.length && s[pos].isLetterOrDigit()) fail("geçersiz sabit")
    }

    private fun readNumberToken(): String {
        val start = pos
        if (charAt() == '-') pos++
        if (pos >= s.length) fail("geçersiz sayı")
        if (s[pos] == '0') {
            pos++
        } else if (s[pos] in '1'..'9') {
            while (pos < s.length && s[pos] in '0'..'9') pos++
        } else fail("geçersiz sayı")
        if (pos < s.length && s[pos] == '.') {
            pos++
            val f = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == f) fail("geçersiz sayı")
        }
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            pos++
            if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
            val f = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == f) fail("geçersiz sayı")
        }
        return s.substring(start, pos) // tam sayı olup olmadığı nextLong'da toLongOrNull ile denetlenir
    }

    private fun readString(): String {
        pos++ // açılış tırnağı
        var sb: StringBuilder? = null
        var chunk = pos
        while (true) {
            if (pos >= s.length) fail("kapanmamış dize")
            val c = s[pos]
            when {
                c == '"' -> {
                    val out = if (sb == null) s.substring(chunk, pos) else sb.append(s, chunk, pos).toString()
                    pos++
                    return out
                }
                c == '\\' -> {
                    if (sb == null) sb = StringBuilder()
                    sb.append(s, chunk, pos)
                    pos++
                    if (pos >= s.length) fail("kapanmamış kaçış")
                    when (val e = s[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 >= s.length) fail("geçersiz \\u kaçışı")
                            var v = 0
                            for (k in 1..4) {
                                val d = Character.digit(s[pos + k], 16)
                                if (d < 0) fail("geçersiz \\u kaçışı")
                                v = (v shl 4) or d
                            }
                            sb.append(v.toChar())
                            pos += 4
                        }
                        else -> fail("geçersiz kaçış '\\$e'")
                    }
                    pos++
                    chunk = pos
                }
                c < ' ' -> fail("dize içinde kaçışsız kontrol karakteri")
                else -> pos++
            }
        }
    }

    private fun fail(msg: String): Nothing = throw IndexFormatException("JSON hatası (konum $pos): $msg")

    private companion object {
        const val MAX_DEPTH = 32
    }
}
