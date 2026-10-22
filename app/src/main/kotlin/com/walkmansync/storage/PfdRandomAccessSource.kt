package com.walkmansync.storage

import android.os.ParcelFileDescriptor
import com.walkmansync.core.fs.RandomAccessSource
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * `ParcelFileDescriptor` üzerinde konuma göre okuma. Kanal, tanıtıcıyı sahiplenen
 * [ParcelFileDescriptor.AutoCloseInputStream]'den alınır; [close] akışı kapatır ve bu hem kanalı hem de
 * fd'yi bırakır. Kurucu başarısız olursa tanıtıcı burada kapatılır (sızıntı olmaz).
 */
internal class PfdRandomAccessSource private constructor(
    private val stream: ParcelFileDescriptor.AutoCloseInputStream,
    private val channel: FileChannel,
    override val size: Long,
) : RandomAccessSource {

    @Volatile private var closed = false

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Kaynak kapalı")
        require(position >= 0 && offset >= 0 && length >= 0 && offset + length <= buffer.size) { "Geçersiz aralık" }
        if (position >= size) return -1
        if (length == 0) return 0
        return channel.read(ByteBuffer.wrap(buffer, offset, length), position)
    }

    override fun close() {
        if (closed) return
        closed = true
        stream.close()
    }

    companion object {
        /** [pfd]'nin sahipliğini devralır; hata durumunda kapatır. */
        fun open(pfd: ParcelFileDescriptor): PfdRandomAccessSource {
            val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            try {
                val channel = stream.channel
                val statSize = pfd.statSize
                val size = if (statSize >= 0) statSize else channel.size()
                return PfdRandomAccessSource(stream, channel, size)
            } catch (e: Throwable) {
                try {
                    stream.close()
                } catch (ignored: IOException) {
                    // kapanış hatası yutulur; asıl hata fırlatılır
                }
                throw e
            }
        }
    }
}
