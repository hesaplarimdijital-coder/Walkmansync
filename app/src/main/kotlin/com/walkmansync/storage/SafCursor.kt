package com.walkmansync.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** Tek sorguda gelen belge satırı. [size] bilinmiyorsa -1, [lastModified] bilinmiyorsa 0. */
internal data class SafDoc(
    val docId: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
) {
    val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
}

/**
 * `DocumentsContract` cursor sorguları. Projection sabittir (ad/boyut/mtime/mime tek sorguda); düğüm
 * başına ek sorgu yapılmaz. Cursor her zaman `use {}` ile kapatılır. Bloklayıcıdır (IO dispatcher).
 */
internal object SafCursor {
    private const val COL_ID = 0
    private const val COL_NAME = 1
    private const val COL_SIZE = 2
    private const val COL_MODIFIED = 3
    private const val COL_MIME = 4

    private val PROJECTION = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_MIME_TYPE,
    )

    /**
     * [parentDocId]'in doğrudan alt öğeleri. Sağlayıcı cursor vermezse (kart çıkmış / dizin yok / sağlayıcı
     * çökmüş) `null`; çağıran nedeni ayırt eder.
     */
    fun queryChildren(resolver: ContentResolver, treeUri: Uri, parentDocId: String): List<SafDoc>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: return null
        cursor.use { c ->
            val out = ArrayList<SafDoc>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) readRow(c)?.let(out::add)
            return out
        }
    }

    /** Tek belge; yoksa (ya da sağlayıcı cursor vermezse) `null`. */
    fun queryDocument(resolver: ContentResolver, treeUri: Uri, docId: String): SafDoc? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null) ?: return null
        cursor.use { c ->
            return if (c.moveToFirst()) readRow(c) else null
        }
    }

    private fun readRow(c: android.database.Cursor): SafDoc? {
        val id = c.getString(COL_ID) ?: return null
        val name = c.getString(COL_NAME) ?: return null
        val mime = if (c.isNull(COL_MIME)) null else c.getString(COL_MIME)
        val isDir = mime == Document.MIME_TYPE_DIR
        val size = when {
            isDir -> 0L
            c.isNull(COL_SIZE) -> -1L
            else -> c.getLong(COL_SIZE)
        }
        val modified = if (c.isNull(COL_MODIFIED)) 0L else c.getLong(COL_MODIFIED)
        return SafDoc(id, name, size, modified, mime)
    }
}
