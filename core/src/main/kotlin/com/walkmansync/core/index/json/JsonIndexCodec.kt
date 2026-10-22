package com.walkmansync.core.index.json

import com.walkmansync.core.index.ContentKey
import com.walkmansync.core.index.IndexCodec
import com.walkmansync.core.index.IndexEntry
import com.walkmansync.core.index.IndexFormatException
import com.walkmansync.core.index.SyncIndex
import com.walkmansync.core.index.TargetKeyEntry
import com.walkmansync.core.index.UnsupportedIndexVersionException
import com.walkmansync.core.model.VolumeId

/**
 * `.walkman_sync_index.json` codec'i (şema sürüm 1, bkz. [SyncIndex]). Durumsuz, thread-safe.
 * Bilinmeyen anahtarlar yok sayılır; eksik zorunlu alan [IndexFormatException]; daha yeni şema sürümünde gövde
 * ayrıştırılmaz ([UnsupportedIndexVersionException]).
 */
class JsonIndexCodec : IndexCodec {

    override fun encode(index: SyncIndex): String {
        val sb = StringBuilder(256 + index.entries.size * 192 + index.targetKeys.size * 128)
        sb.append("{\n")
        sb.append("  \"schemaVersion\": ").append(index.schemaVersion).append(",\n")
        sb.append("  \"app\": "); JsonWriter.appendString(sb, SyncIndex.APP_ID); sb.append(",\n")
        sb.append("  \"targetVolume\": "); JsonWriter.appendString(sb, index.targetVolume.uuid); sb.append(",\n")
        sb.append("  \"lastSourceVolume\": "); JsonWriter.appendNullableString(sb, index.lastSourceVolume?.uuid); sb.append(",\n")
        sb.append("  \"createdAt\": ").append(index.createdAtMillis).append(",\n")
        sb.append("  \"updatedAt\": ").append(index.updatedAtMillis).append(",\n")
        sb.append("  \"entries\": [")
        index.entries.forEachIndexed { i, e ->
            sb.append(if (i == 0) "\n    " else ",\n    ")
            sb.append("{\"src\": "); JsonWriter.appendString(sb, e.sourcePath)
            sb.append(", \"srcSize\": ").append(e.sourceSize)
            sb.append(", \"srcMtime\": ").append(e.sourceModified)
            sb.append(", \"dst\": "); JsonWriter.appendString(sb, e.targetPath)
            sb.append(", \"dstSize\": ").append(e.targetSize)
            sb.append(", \"key\": "); JsonWriter.appendNullableString(sb, e.contentKey?.value)
            sb.append(", \"at\": ").append(e.syncedAtMillis).append('}')
        }
        sb.append(if (index.entries.isEmpty()) "],\n" else "\n  ],\n")
        sb.append("  \"targetKeys\": [")
        index.targetKeys.forEachIndexed { i, t ->
            sb.append(if (i == 0) "\n    " else ",\n    ")
            sb.append("{\"dst\": "); JsonWriter.appendString(sb, t.targetPath)
            sb.append(", \"dstSize\": ").append(t.targetSize)
            sb.append(", \"key\": "); JsonWriter.appendNullableString(sb, t.contentKey?.value); sb.append('}')
        }
        sb.append(if (index.targetKeys.isEmpty()) "]\n" else "\n  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    override fun decode(json: String): SyncIndex {
        try {
            val version = readSchemaVersion(json)
            if (version > SyncIndex.CURRENT_SCHEMA.toLong()) {
                throw UnsupportedIndexVersionException(version.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            if (version < 1) throw IndexFormatException("Geçersiz schemaVersion: $version")
            return readIndex(json, version.toInt())
        } catch (e: IndexFormatException) {
            throw e
        } catch (e: UnsupportedIndexVersionException) {
            throw e
        } catch (e: RuntimeException) {
            throw IndexFormatException("İndeks ayrıştırılamadı: ${e.message}", e)
        }
    }

    /** İlk geçiş: yalnızca `schemaVersion`'ı bulur, gerisini atlar (sözdizimi yine doğrulanır). */
    private fun readSchemaVersion(json: String): Long {
        val r = JsonPullReader(json)
        if (r.peek() != JsonPullReader.Token.BEGIN_OBJECT) throw IndexFormatException("Kök nesne bekleniyor")
        r.beginObject()
        var version: Long? = null
        while (r.hasNext()) {
            val name = r.nextName()
            if (name == "schemaVersion") {
                if (r.peek() != JsonPullReader.Token.NUMBER) throw IndexFormatException("schemaVersion sayı değil")
                version = r.nextLong()
            } else {
                r.skipValue()
            }
        }
        r.endObject()
        r.endDocument()
        return version ?: throw IndexFormatException("schemaVersion eksik")
    }

    private fun readIndex(json: String, version: Int): SyncIndex {
        val r = JsonPullReader(json)
        r.beginObject()
        var targetVolume: VolumeId? = null
        var lastSource: VolumeId? = null
        var createdAt: Long? = null
        var updatedAt: Long? = null
        var entries: List<IndexEntry>? = null
        var targetKeys: List<TargetKeyEntry> = emptyList()
        while (r.hasNext()) {
            when (r.nextName()) {
                "targetVolume" -> targetVolume = volume(r.nextString(), "targetVolume")
                "lastSourceVolume" ->
                    if (r.peek() == JsonPullReader.Token.NULL) r.nextNull() else lastSource = volume(r.nextString(), "lastSourceVolume")
                "createdAt" -> createdAt = r.nextLong()
                "updatedAt" -> updatedAt = r.nextLong()
                "entries" -> entries = readEntries(r)
                "targetKeys" -> targetKeys = readTargetKeys(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        r.endDocument()
        return SyncIndex(
            schemaVersion = version,
            targetVolume = targetVolume ?: missing("targetVolume"),
            lastSourceVolume = lastSource,
            createdAtMillis = createdAt ?: missing("createdAt"),
            updatedAtMillis = updatedAt ?: missing("updatedAt"),
            entries = entries ?: missing("entries"),
            targetKeys = targetKeys,
        )
    }

    private fun readEntries(r: JsonPullReader): List<IndexEntry> {
        r.beginArray()
        val list = ArrayList<IndexEntry>()
        while (r.hasNext()) {
            r.beginObject()
            var src: String? = null
            var srcSize: Long? = null
            var srcMtime: Long? = null
            var dst: String? = null
            var dstSize: Long? = null
            var key: ContentKey? = null
            var at: Long? = null
            while (r.hasNext()) {
                when (r.nextName()) {
                    "src" -> src = r.nextString()
                    "srcSize" -> srcSize = r.nextLong()
                    "srcMtime" -> srcMtime = r.nextLong()
                    "dst" -> dst = r.nextString()
                    "dstSize" -> dstSize = r.nextLong()
                    "key" -> key = readKey(r)
                    "at" -> at = r.nextLong()
                    else -> r.skipValue()
                }
            }
            r.endObject()
            list.add(
                IndexEntry(
                    sourcePath = src ?: missing("entries[].src"),
                    sourceSize = srcSize ?: missing("entries[].srcSize"),
                    sourceModified = srcMtime ?: missing("entries[].srcMtime"),
                    targetPath = dst ?: missing("entries[].dst"),
                    targetSize = dstSize ?: missing("entries[].dstSize"),
                    contentKey = key,
                    syncedAtMillis = at ?: missing("entries[].at"),
                ),
            )
        }
        r.endArray()
        return list
    }

    private fun readTargetKeys(r: JsonPullReader): List<TargetKeyEntry> {
        r.beginArray()
        val list = ArrayList<TargetKeyEntry>()
        while (r.hasNext()) {
            r.beginObject()
            var dst: String? = null
            var dstSize: Long? = null
            var key: ContentKey? = null
            while (r.hasNext()) {
                when (r.nextName()) {
                    "dst" -> dst = r.nextString()
                    "dstSize" -> dstSize = r.nextLong()
                    "key" -> key = readKey(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            list.add(
                TargetKeyEntry(
                    targetPath = dst ?: missing("targetKeys[].dst"),
                    targetSize = dstSize ?: missing("targetKeys[].dstSize"),
                    contentKey = key,
                ),
            )
        }
        r.endArray()
        return list
    }

    private fun readKey(r: JsonPullReader): ContentKey? =
        if (r.peek() == JsonPullReader.Token.NULL) {
            r.nextNull(); null
        } else {
            r.nextString().takeIf { it.isNotEmpty() }?.let { ContentKey(it) }
        }

    private fun volume(raw: String, field: String): VolumeId =
        VolumeId.of(raw) ?: throw IndexFormatException("Geçersiz $field: '$raw'")

    private fun missing(field: String): Nothing = throw IndexFormatException("Zorunlu alan eksik: $field")
}
