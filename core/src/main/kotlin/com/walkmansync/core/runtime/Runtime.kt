// MİMAR — değiştirilmez. Sözleşme dosyası; değişiklik ihtiyacı Team Lead'e bildirilir.
package com.walkmansync.core.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Zaman kaynağı. Hız/ETA hesapları ve kayıt zaman damgaları yalnızca bunu kullanır
 * (System.currentTimeMillis / nanoTime doğrudan ÇAĞRILMAZ) — testlerde sahte saat verilir.
 */
interface Clock {
    /** Duvar saati, epoch ms (kayıt zaman damgaları). */
    fun nowMillis(): Long

    /** Monoton saat, ns (süre ölçümü, hız). */
    fun monotonicNanos(): Long

    companion object {
        val System: Clock = object : Clock {
            override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
            override fun monotonicNanos(): Long = java.lang.System.nanoTime()
        }
    }
}

/** Testte elle ilerletilen saat. */
class ManualClock(var millis: Long = 0L, var nanos: Long = 0L) : Clock {
    override fun nowMillis(): Long = millis
    override fun monotonicNanos(): Long = nanos

    fun advanceMillis(ms: Long) {
        millis += ms
        nanos += ms * 1_000_000L
    }
}

/**
 * Dispatcher'lar enjekte edilir; core kodu `Dispatchers.*`'a doğrudan başvurmaz.
 *
 * @property io Bloklayıcı G/Ç (SAF cursor, akış okuma/yazma, fd).
 * @property cpu Hesaplama (fark motoru, etiket ayrıştırma yoğun döngüleri, JSON).
 */
class AppDispatchers(
    val io: CoroutineDispatcher,
    val cpu: CoroutineDispatcher,
) {
    companion object {
        val Default: AppDispatchers = AppDispatchers(io = Dispatchers.IO, cpu = Dispatchers.Default)
    }
}

/** Günlük portu (app: android.util.Log; test: println/no-op). */
interface Logger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)

    companion object {
        val None: Logger = object : Logger {
            override fun d(tag: String, message: String) = Unit
            override fun w(tag: String, message: String, error: Throwable?) = Unit
            override fun e(tag: String, message: String, error: Throwable?) = Unit
        }
    }
}
