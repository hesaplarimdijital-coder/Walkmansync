// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.fs

import java.io.Closeable
import java.io.EOFException
import java.io.IOException

/**
 * Konuma göre okunabilen, salt-okunur bayt kaynağı. Etiket ayrıştırıcıları yalnızca bunun üzerinden
 * çalışır. Android tarafında `ParcelFileDescriptor` + `FileChannel.read(buf, pos)` ile uygulanır ve
 * `MediaDataSource`'a birebir uyar (yedek okuyucu için).
 *
 * İş parçacığı: Metotlar BLOKLAYICIDIR; yalnızca IO dispatcher'da çağrılır. Bir örnek aynı anda tek
 * coroutine tarafından kullanılır (thread-safe olması gerekmez).
 *
 * Sahiplik: Açan ([com.walkmansync.core.fs.ReadableTree.openRandomAccess] çağıran) kapatır — her zaman
 * `use { }` ile. [close] idempotenttir.
 */
interface RandomAccessSource : Closeable {
    /** Toplam boyut (bayt). */
    val size: Long

    /**
     * [position]'dan itibaren en fazla [length] bayt okuyup [buffer]'a [offset]'ten yazar.
     * @return okunan bayt sayısı (0 < n ≤ length) ya da [position] ≥ [size] ise -1.
     * @throws IOException G/Ç hatası (ör. kart çıkarıldı).
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** Tam [length] bayt okur; erken dosya sonunda [EOFException]. */
fun RandomAccessSource.readFully(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
    require(offset >= 0 && length >= 0 && offset + length <= buffer.size) { "Geçersiz aralık" }
    var done = 0
    while (done < length) {
        val n = readAt(position + done, buffer, offset + done, length - done)
        if (n <= 0) throw EOFException("Beklenmeyen dosya sonu: konum=${position + done}, boyut=$size")
        done += n
    }
}

/** [position]'dan [length] bayt okuyup yeni dizi döndürür. */
fun RandomAccessSource.readBytes(position: Long, length: Int): ByteArray =
    ByteArray(length).also { readFully(position, it) }

/** Bellek içi uygulama (testler ve küçük gömülü bloklar için). */
class ByteArrayRandomAccessSource(private val data: ByteArray) : RandomAccessSource {
    @Volatile private var closed = false

    override val size: Long get() = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Kaynak kapalı")
        require(position >= 0 && offset >= 0 && length >= 0 && offset + length <= buffer.size) { "Geçersiz aralık" }
        if (position >= data.size) return -1
        if (length == 0) return 0
        val n = minOf(length.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun close() {
        closed = true
    }
}
